package xyz.aezdek.aezgram.server

import xyz.aezdek.aezgram.protocol.Audience
import xyz.aezdek.aezgram.protocol.FollowState
import xyz.aezdek.aezgram.protocol.Kind

/**
 * Follows, requests, blocks and account privacy. Losing a relationship removes the server-side wraps (so downloads
 * stop); anything the other device already cached stays readable there — documented in the README.
 */
class Social(private val db: Db, private val clock: Clock, private val objects: Objects) {

    private fun Tx.requireUser(id: String) {
        if (!exists("SELECT 1 FROM users WHERE id = ? AND deleted = 0", id)) notFound("No such user")
    }

    private fun Tx.isPrivate(userId: String) = one("SELECT is_private FROM users WHERE id = ?", userId) { it.bool("is_private") } ?: true

    private fun Tx.blockedEitherWay(a: String, b: String) =
        exists("SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)", a, b, b, a)

    fun follow(me: String, target: String, fx: Effects): String = db.tx {
        if (me == target) badRequest("You cannot follow yourself")
        requireUser(target)
        if (blockedEitherWay(me, target)) forbidden("You cannot follow this account")
        val now = clock.now()
        val current = followState(me, target)
        if (current != FollowState.NONE) return@tx current
        val state = if (isPrivate(target)) FollowState.REQUESTED else FollowState.FOLLOWING
        exec("INSERT INTO follows (follower_id, followee_id, state, created_at) VALUES (?, ?, ?, ?)", me, target, state, now)
        if (state == FollowState.FOLLOWING) addWrapTask(target, me, "follower", now)
        change(listOf(me, target), ChangeType.USER, target, fx, now)
        change(listOf(me, target), ChangeType.USER, me, fx, now)
        fx.pushes += PushHint(target, if (state == FollowState.FOLLOWING) "follow" else "follow_request", me)
        state
    }

    fun unfollow(me: String, target: String, fx: Effects) = db.tx {
        val now = clock.now()
        if (exec("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", me, target) == 0) return@tx
        with(objects) {
            revokeWraps(target, me, if (isPrivate(target)) setOf(Audience.FOLLOWERS, Audience.CLOSE_FRIENDS) else setOf(Audience.CLOSE_FRIENDS), fx, now)
        }
        change(listOf(me, target), ChangeType.USER, target, fx, now)
        change(listOf(me, target), ChangeType.USER, me, fx, now)
    }

    fun incomingRequests(me: String): List<String> = db.tx {
        strings("SELECT follower_id FROM follows WHERE followee_id = ? AND state = ? ORDER BY created_at DESC", me, FollowState.REQUESTED)
    }

    fun accept(me: String, follower: String, fx: Effects) = db.tx {
        val now = clock.now()
        if (exec(
                "UPDATE follows SET state = ? WHERE follower_id = ? AND followee_id = ? AND state = ?",
                FollowState.FOLLOWING, follower, me, FollowState.REQUESTED,
            ) == 0
        ) {
            notFound("No such request")
        }
        addWrapTask(me, follower, "follower", now)
        change(listOf(me, follower), ChangeType.USER, me, fx, now)
        change(listOf(me, follower), ChangeType.USER, follower, fx, now)
        fx.pushes += PushHint(follower, "follow_accepted", me)
    }

    fun decline(me: String, follower: String, fx: Effects) = db.tx {
        val now = clock.now()
        exec("DELETE FROM follows WHERE follower_id = ? AND followee_id = ? AND state = ?", follower, me, FollowState.REQUESTED)
        change(listOf(me, follower), ChangeType.USER, me, fx, now)
    }

    fun removeFollower(me: String, follower: String, fx: Effects) = unfollow(follower, me, fx)

    fun followers(viewer: String, userId: String): List<String> = db.tx {
        requireVisibleGraph(viewer, userId)
        strings("SELECT follower_id FROM follows WHERE followee_id = ? AND state = ? ORDER BY created_at DESC", userId, FollowState.FOLLOWING)
    }

    fun following(viewer: String, userId: String): List<String> = db.tx {
        requireVisibleGraph(viewer, userId)
        strings("SELECT followee_id FROM follows WHERE follower_id = ? AND state = ? ORDER BY created_at DESC", userId, FollowState.FOLLOWING)
    }

    private fun Tx.requireVisibleGraph(viewer: String, userId: String) {
        requireUser(userId)
        if (viewer == userId || !isPrivate(userId) || followState(viewer, userId) == FollowState.FOLLOWING) return
        forbidden("This account is private")
    }

    fun block(me: String, target: String, fx: Effects) = db.tx {
        if (me == target) badRequest("You cannot block yourself")
        requireUser(target)
        val now = clock.now()
        exec("INSERT OR IGNORE INTO blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)", me, target, now)
        exec("DELETE FROM follows WHERE (follower_id = ? AND followee_id = ?) OR (follower_id = ? AND followee_id = ?)", me, target, target, me)
        val everything = setOf(Audience.MEMBERS, Audience.FOLLOWERS, Audience.CLOSE_FRIENDS, Audience.DIRECT)
        with(objects) {
            revokeWraps(me, target, everything, fx, now, exceptKinds = setOf(Kind.PROFILE))
            revokeWraps(target, me, everything, fx, now, exceptKinds = setOf(Kind.PROFILE))
        }
        exec("DELETE FROM wrap_tasks WHERE done_at IS NULL AND ((author_id = ? AND recipient_id = ?) OR (author_id = ? AND recipient_id = ?))", me, target, target, me)
        change(listOf(me, target), ChangeType.USER, target, fx, now)
        change(listOf(me, target), ChangeType.USER, me, fx, now)
    }

    fun unblock(me: String, target: String, fx: Effects) = db.tx {
        val now = clock.now()
        exec("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", me, target)
        if (!blockedEitherWay(me, target)) {
            // Public content flows again once each side's client handles the task.
            addWrapTask(me, target, "member", now)
            addWrapTask(target, me, "member", now)
        }
        change(listOf(me, target), ChangeType.USER, target, fx, now)
    }

    fun blocked(me: String): List<String> = db.tx { strings("SELECT blocked_id FROM blocks WHERE blocker_id = ?", me) }

    /**
     * Public → private: non-followers lose access to everything but the profile.
     * Private → public: every non-follower gets a wrap task, so the author's client shares their posts with them.
     */
    fun setPrivate(tx: Tx, me: String, private: Boolean, fx: Effects) = with(tx) {
        val was = isPrivate(me)
        if (was == private) return@with
        val now = clock.now()
        exec("UPDATE users SET is_private = ? WHERE id = ?", private, me)
        val nonFollowers = strings(
            """SELECT id FROM users WHERE deleted = 0 AND id != ? AND id NOT IN
               (SELECT follower_id FROM follows WHERE followee_id = ? AND state = ?)""",
            me, me, FollowState.FOLLOWING,
        )
        if (private) {
            for (user in nonFollowers) {
                with(objects) { revokeWraps(me, user, setOf(Audience.MEMBERS, Audience.FOLLOWERS), fx, now, exceptKinds = setOf(Kind.PROFILE)) }
            }
        } else {
            // Pending requests become follows.
            val requesters = strings("SELECT follower_id FROM follows WHERE followee_id = ? AND state = ?", me, FollowState.REQUESTED)
            exec("UPDATE follows SET state = ? WHERE followee_id = ? AND state = ?", FollowState.FOLLOWING, me, FollowState.REQUESTED)
            requesters.forEach { change(it, ChangeType.USER, me, fx, now) }
            for (user in nonFollowers) if (!blockedEitherWay(me, user)) addWrapTask(me, user, "member", now)
        }
    }
}
