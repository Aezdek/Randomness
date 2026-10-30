package xyz.aezdek.hush.server

/**
 * Side effects a transaction produces, applied only after it commits: which users should re-sync, and which of
 * them deserve a push notification if none of their devices is online.
 */
class Effects {
    val syncUsers = linkedSetOf<String>()
    val pushes = mutableListOf<PushHint>()

    fun merge(other: Effects) {
        syncUsers += other.syncUsers
        pushes += other.pushes
    }
}

/** What a push may say. Never content: only who did what kind of thing, which the server knows anyway. */
data class PushHint(val userId: String, val type: String, val fromUserId: String?, val ref: String? = null)

object ChangeType {
    const val OBJECT = "obj"
    const val DELETED = "del"
    const val USER = "user"
    const val THREAD = "thread"
}

/** Appends to the per-user change log that /sync reads, and marks the users for a realtime nudge. */
fun Tx.change(userIds: Collection<String>, type: String, ref: String, fx: Effects, now: Long) {
    for (userId in userIds.toSet()) {
        exec("INSERT INTO changes (user_id, type, ref, created_at) VALUES (?, ?, ?, ?)", userId, type, ref, now)
        fx.syncUsers += userId
    }
}

fun Tx.change(userId: String, type: String, ref: String, fx: Effects, now: Long) = change(listOf(userId), type, ref, fx, now)
