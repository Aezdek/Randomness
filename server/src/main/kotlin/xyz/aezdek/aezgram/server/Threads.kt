package xyz.aezdek.aezgram.server

import xyz.aezdek.aezgram.protocol.AddMembersRequest
import xyz.aezdek.aezgram.protocol.CreateThreadRequest
import xyz.aezdek.aezgram.protocol.Kind
import xyz.aezdek.aezgram.protocol.MAX_MEMBERS
import xyz.aezdek.aezgram.protocol.ObjectUpload
import xyz.aezdek.aezgram.protocol.ThreadDto
import xyz.aezdek.aezgram.protocol.ThreadMemberDto

/**
 * Chats. A thread has a `thread` object (name/photo, keyed for all members) and a chain of `epoch` objects; messages
 * derive their keys from the newest epoch. Adding people starts a new epoch, so newcomers cannot read earlier
 * messages. When someone leaves, the remaining members' clients start a new epoch before their next message, so the
 * leaver cannot read later ones even from ciphertext.
 */
class Threads(private val db: Db, private val clock: Clock, private val objects: Objects) {

    fun Tx.thread(id: String): ThreadDto? = one("SELECT * FROM threads WHERE id = ?", id) {
        Triple(it.bool("is_group"), it.str("created_by"), it.getLong("created_at"))
    }?.let { (isGroup, createdBy, createdAt) ->
        ThreadDto(
            id = id, isGroup = isGroup, createdBy = createdBy, createdAt = createdAt,
            members = query("SELECT * FROM thread_members WHERE thread_id = ? ORDER BY joined_at", id) {
                ThreadMemberDto(it.str("user_id"), it.str("role"), it.getLong("joined_at"), it.getLong("last_read_at"))
            },
        )
    }

    fun Tx.members(threadId: String) = strings("SELECT user_id FROM thread_members WHERE thread_id = ?", threadId)

    fun list(me: String): List<ThreadDto> = db.tx {
        strings("SELECT thread_id FROM thread_members WHERE user_id = ?", me).mapNotNull { thread(it) }
    }

    fun get(me: String, id: String): ThreadDto = db.tx {
        if (!exists("SELECT 1 FROM thread_members WHERE thread_id = ? AND user_id = ?", id, me)) notFound()
        thread(id)!!
    }

    fun create(me: String, req: CreateThreadRequest, fx: Effects): ThreadDto = db.tx {
        val now = clock.now()
        val members = (req.memberIds + me).distinct()
        if (members.size < 2) badRequest("A chat needs someone else in it")
        if (members.size > MAX_MEMBERS) badRequest("Chats hold up to $MAX_MEMBERS people")
        if (!req.isGroup && members.size != 2) badRequest("A one-to-one chat has two people")
        for (member in members) {
            if (!exists("SELECT 1 FROM users WHERE id = ? AND deleted = 0", member)) badRequest("Unknown member")
            if (member != me && exists(
                    "SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)", me, member, member, me,
                )
            ) {
                forbidden("You cannot message this account")
            }
        }
        val id = req.thread.id
        if (req.thread.kind != Kind.THREAD || req.thread.threadId != id) badRequest("The thread object's id is the thread id")
        if (req.epoch.kind != Kind.EPOCH || req.epoch.threadId != id) badRequest("bad epoch")
        val pairKey = if (req.isGroup) null else members.sorted().joinToString("|")
        if (pairKey != null) {
            one("SELECT id FROM threads WHERE pair_key = ?", pairKey) { it.str("id") }?.let { conflict(it, "thread_exists") }
        }
        if (exists("SELECT 1 FROM threads WHERE id = ?", id)) conflict("Thread exists", "exists")
        exec("INSERT INTO threads (id, is_group, created_by, created_at, pair_key) VALUES (?, ?, ?, ?, ?)", id, req.isGroup, me, now, pairKey)
        for (member in members) {
            exec(
                "INSERT INTO thread_members (thread_id, user_id, role, joined_at, last_read_at) VALUES (?, ?, ?, ?, ?)",
                id, member, if (member == me) "admin" else "member", now, if (member == me) now else 0,
            )
        }
        requireWrapsForAll(req.thread.wraps.map { it.recipientId }, members)
        requireWrapsForAll(req.epoch.wraps.map { it.recipientId }, members)
        objects.createIn(this, me, req.thread, fx, allowThreadKinds = true)
        objects.createIn(this, me, req.epoch, fx, allowThreadKinds = true)
        change(members, ChangeType.THREAD, id, fx, now)
        thread(id)!!
    }

    private fun requireWrapsForAll(recipients: List<String>, members: List<String>) {
        if (!recipients.toSet().containsAll(members)) badRequest("Seal the key to every member")
    }

    fun addMembers(me: String, threadId: String, req: AddMembersRequest, fx: Effects): ThreadDto = db.tx {
        val now = clock.now()
        val thread = thread(threadId) ?: notFound()
        if (!thread.isGroup) badRequest("Start a group to add people")
        val current = thread.members.map { it.userId }
        if (me !in current) notFound()
        val added = req.userIds.distinct().filter { it !in current }
        if (added.isEmpty()) return@tx thread
        if (current.size + added.size > MAX_MEMBERS) badRequest("Chats hold up to $MAX_MEMBERS people")
        for (user in added) {
            if (!exists("SELECT 1 FROM users WHERE id = ? AND deleted = 0", user)) badRequest("Unknown member")
            exec("INSERT INTO thread_members (thread_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)", threadId, user, now)
        }
        val members = current + added
        if (req.epoch.kind != Kind.EPOCH || req.epoch.threadId != threadId) badRequest("bad epoch")
        requireWrapsForAll(req.epoch.wraps.map { it.recipientId }, members)
        requireWrapsForAll(req.threadWraps.map { it.recipientId }, added)
        objects.createIn(this, me, req.epoch, fx, allowThreadKinds = true)
        // The thread object belongs to its creator; any member may share its key with newcomers.
        for (wrap in req.threadWraps.filter { it.recipientId in added }) {
            exec(
                "INSERT OR REPLACE INTO wraps (object_id, recipient_id, sealed, created_at) VALUES (?, ?, ?, ?)",
                threadId, wrap.recipientId, B64u.dec(wrap.sealed, "wrap", max = 256), now,
            )
            change(wrap.recipientId, ChangeType.OBJECT, threadId, fx, now)
        }
        change(members, ChangeType.THREAD, threadId, fx, now)
        thread(threadId)!!
    }

    /**
     * A new epoch for the current members, started by whichever member next sends after someone left. The epoch's key
     * must be sealed to every current member, and to nobody else.
     */
    fun rotate(me: String, threadId: String, epoch: ObjectUpload, fx: Effects) = db.tx {
        val members = members(threadId)
        if (me !in members) notFound()
        if (epoch.kind != Kind.EPOCH || epoch.threadId != threadId) badRequest("bad epoch")
        val recipients = epoch.wraps.map { it.recipientId }.toSet()
        if (recipients != members.toSet()) badRequest("Seal the new key to exactly the current members")
        objects.createIn(this, me, epoch, fx, allowThreadKinds = true)
    }

    /** Leaving (userId == me) or removing someone (creator only). Their wraps on the thread's keys go too. */
    fun removeMember(me: String, threadId: String, userId: String, fx: Effects) = db.tx {
        val now = clock.now()
        val thread = thread(threadId) ?: notFound()
        val current = thread.members.map { it.userId }
        if (me !in current || userId !in current) notFound()
        if (userId != me && thread.createdBy != me) forbidden("Only the chat's creator can remove people")
        if (!thread.isGroup && userId != me) badRequest("Leave the chat instead")
        exec("DELETE FROM thread_members WHERE thread_id = ? AND user_id = ?", threadId, userId)
        exec(
            "DELETE FROM wraps WHERE recipient_id = ? AND object_id IN (SELECT id FROM objects WHERE thread_id = ?)", userId, threadId,
        )
        change(current, ChangeType.THREAD, threadId, fx, now)
    }

    fun markRead(me: String, threadId: String, at: Long, fx: Effects) = db.tx {
        val now = clock.now()
        val members = members(threadId)
        if (me !in members) notFound()
        val clamped = minOf(at, now)
        exec("UPDATE thread_members SET last_read_at = MAX(last_read_at, ?) WHERE thread_id = ? AND user_id = ?", clamped, threadId, me)
        change(members, ChangeType.THREAD, threadId, fx, now)
    }
}
