package xyz.aezdek.hush.server

import xyz.aezdek.hush.protocol.FollowState
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.SyncResponse
import xyz.aezdek.hush.protocol.ThreadDto
import xyz.aezdek.hush.protocol.UserDto

/**
 * The per-user change log, replayed from a cursor. A new device starts at 0 and catches up on everything; a live one
 * gets a `{t: "sync"}` nudge over the WebSocket and asks for what is new. Readability is re-checked at read time, so
 * anything that became unreadable since it was logged comes back as deleted.
 */
class Sync(
    private val db: Db,
    private val objects: Objects,
    private val accounts: Accounts,
    private val threads: Threads,
) {
    fun sync(userId: String, cursor: Long, limit: Int): SyncResponse = db.tx {
        val rows = query(
            "SELECT seq, type, ref FROM changes WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?", userId, cursor, limit.coerceIn(1, 1000),
        ) { Triple(it.getLong("seq"), it.str("type"), it.str("ref")) }

        val lastObjectAction = linkedMapOf<String, String>()
        val userIds = linkedSetOf<String>()
        val threadIds = linkedSetOf<String>()
        for ((_, type, ref) in rows) {
            when (type) {
                ChangeType.OBJECT, ChangeType.DELETED -> {
                    lastObjectAction.remove(ref)
                    lastObjectAction[ref] = type
                }
                ChangeType.USER -> userIds += ref
                ChangeType.THREAD -> threadIds += ref
            }
        }

        val out = ArrayList<ObjectDto>()
        val deleted = ArrayList<String>()
        with(objects) {
            for ((id, action) in lastObjectAction) {
                val obj = load(id)
                when {
                    obj == null -> Unit
                    action == ChangeType.OBJECT && canRead(userId, obj) -> out += toDto(obj, userId)
                    else -> deleted += id
                }
            }
        }
        val users = ArrayList<UserDto>()
        with(accounts) { userIds.forEach { id -> user(userId, id)?.let { users += it } } }
        val threadsOut = ArrayList<ThreadDto>()
        val removed = ArrayList<String>()
        with(threads) {
            for (id in threadIds) {
                val thread = thread(id)
                if (thread != null && thread.members.any { it.userId == userId }) threadsOut += thread else removed += id
            }
        }
        SyncResponse(
            cursor = rows.lastOrNull()?.first ?: cursor,
            hasMore = rows.size >= limit,
            objects = out,
            deletedIds = deleted,
            users = users,
            threads = threadsOut,
            removedThreadIds = removed,
            pendingWrapTasks = with(objects) { openTaskCount(userId) },
            followRequests = strings(
                "SELECT follower_id FROM follows WHERE followee_id = ? AND state = ? ORDER BY created_at DESC", userId, FollowState.REQUESTED,
            ),
        )
    }
}
