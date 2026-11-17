package xyz.aezdek.hush.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.aezdek.hush.protocol.FollowState
import java.util.concurrent.ConcurrentHashMap

/**
 * Who follows someone and whom they follow, kept for a while the way Instagram's app keeps a profile around: opening a
 * profile again, or going back to it from a list, shows what was already loaded instead of asking the server again.
 * A list is asked for again once it is [FRESH_MS] old, or as soon as what this app knows about the follows changes
 * (see [relation]): following or unfollowing, a new follower, a removed one.
 */
class FollowLists(private val fetch: suspend (path: String) -> List<String>, private val clock: () -> Long = System::currentTimeMillis) {
    private class Kept(val at: Long, val ids: List<String>, val relation: String)

    private val kept = ConcurrentHashMap<String, Kept>()
    private val lock = Mutex()

    private fun path(userId: String, followers: Boolean) = "/users/$userId/${if (followers) "followers" else "following"}"

    /** What is kept for [userId], however old, or null. */
    fun peek(userId: String, followers: Boolean): List<String>? = kept[path(userId, followers)]?.ids

    /** The list, from what is kept while it is fresh and [relation] hasn't changed, otherwise from the server. */
    suspend fun get(userId: String, followers: Boolean, relation: String): List<String> {
        val path = path(userId, followers)
        // One at a time, so a profile and its list opening together ask the server once.
        return lock.withLock {
            kept[path]?.takeIf { it.relation == relation && clock() - it.at in 0 until FRESH_MS }?.ids
                ?: fetch(path).also { kept[path] = Kept(clock(), it, relation) }
        }
    }

    /** Forgets everything (on sign-out). */
    fun clear() = kept.clear()

    companion object {
        const val FRESH_MS = 2 * 60_000L

        /**
         * A short summary of what this app knows about [userId]'s follows, which changes when a follow does: our own
         * follows and followers for our profile, our relationship to them for anyone else's.
         */
        fun relation(state: AppState, userId: String): String {
            if (userId == state.me?.id) {
                val users = state.users.values
                val followers = users.filter { it.followsMe == FollowState.FOLLOWING }.map { it.id }.sorted()
                val following = users.filter { it.followState == FollowState.FOLLOWING }.map { it.id }.sorted()
                return followers.joinToString(",") + "|" + following.joinToString(",")
            }
            val user = state.users[userId] ?: return ""
            return "${user.followState}|${user.followsMe}|${user.blockedByMe}|${user.deleted}"
        }
    }
}
