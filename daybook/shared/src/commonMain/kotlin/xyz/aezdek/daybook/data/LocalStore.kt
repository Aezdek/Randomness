package xyz.aezdek.daybook.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import xyz.aezdek.daybook.core.SettingRecord
import xyz.aezdek.daybook.core.Stamped
import xyz.aezdek.daybook.core.SyncResponse
import xyz.aezdek.daybook.core.TaskRecord
import xyz.aezdek.daybook.core.sameStampAs
import xyz.aezdek.daybook.core.supersedes

/** A record plus whether this device has changed it since the server last confirmed it. */
@Serializable
data class LocalTask(val record: TaskRecord, val dirty: Boolean)

/**
 * This device's copy of everything. It is the source of truth for the screens: every edit lands here
 * first and is marked dirty until the server has accepted it, so the app works the same with or without
 * a connection.
 *
 * Android and Linux keep it in SQLite ([SqliteLocalStore]); the web app keeps it in memory, saved to the
 * browser's storage ([MemoryLocalStore]). Both follow the same merge rules ([SyncMerge]).
 */
interface LocalStore {
    /** Changes every time a write commits. Observers re-query when it moves. */
    val version: StateFlow<Long>

    suspend fun allTasks(): List<TaskRecord>
    suspend fun task(id: String): LocalTask?
    suspend fun putLocal(record: TaskRecord)

    suspend fun settings(): Map<String, String>
    suspend fun setting(key: String): SettingRecord?
    suspend fun putLocalSetting(record: SettingRecord)

    suspend fun meta(key: String): String?
    suspend fun setMeta(key: String, value: String)

    /** A random id for this install, created on first use. It labels this device's edits. */
    suspend fun deviceId(): String

    suspend fun lastRev(): Long
    suspend fun pendingCount(): Int
    suspend fun pendingTasks(limit: Int): List<TaskRecord>
    suspend fun pendingSettings(): List<SettingRecord>

    /**
     * Applies a server answer atomically. A server record replaces the local one unless this device holds a
     * newer, unsent edit of it. A pushed record is confirmed (no longer dirty) only if it is still exactly
     * what was sent, so an edit made while the request was in flight is kept for the next push.
     */
    suspend fun applySync(response: SyncResponse, pushedTasks: List<TaskRecord>, pushedSettings: List<SettingRecord>)

    /** After pointing at a different server: start from scratch there and upload everything this device has. */
    suspend fun resetForNewServer()

    companion object {
        const val META_DEVICE_ID = "device_id"
        const val META_LAST_REV = "last_rev"
        const val META_LAST_SYNC = "last_sync"
    }
}

/** The merge rules every store applies, kept in one place. */
object SyncMerge {
    /** Keep this device's copy over [remote]: only an unsent local edit that is newer survives. */
    fun keepLocal(local: Stamped?, localDirty: Boolean, remote: Stamped): Boolean =
        local != null && localDirty && local.supersedes(remote)

    /** The server has dealt with [sent] (accepted it, or refused it as malformed) and it hasn't changed since. */
    fun isConfirmed(current: Stamped?, currentDirty: Boolean, sent: Stamped): Boolean =
        current != null && currentDirty && current.sameStampAs(sent)
}
