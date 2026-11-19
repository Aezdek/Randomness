package xyz.aezdek.daybook.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import xyz.aezdek.daybook.core.SettingRecord
import xyz.aezdek.daybook.core.SyncResponse
import xyz.aezdek.daybook.core.TaskRecord
import xyz.aezdek.daybook.core.sameStampAs
import xyz.aezdek.daybook.core.supersedes
import kotlin.uuid.Uuid

/** A record plus whether this device has changed it since the server last confirmed it. */
data class LocalTask(val record: TaskRecord, val dirty: Boolean)

/**
 * This device's copy of everything, in SQLite. It is the source of truth for the screens: every edit
 * lands here first and is marked dirty until the server has accepted it, so the app works the same
 * with or without a connection.
 *
 * All access runs on [dispatcher], which must run one block at a time.
 */
class LocalStore(
    driver: SQLiteDriver,
    path: String,
    private val dispatcher: CoroutineDispatcher,
) {
    private val connection: SQLiteConnection = driver.open(path).also(::migrate)
    private val _version = MutableStateFlow(0L)

    /** Changes every time a write commits. Observers re-query when it moves. */
    val version: StateFlow<Long> = _version.asStateFlow()

    suspend fun <T> read(block: SQLiteConnection.() -> T): T = withContext(dispatcher) { connection.block() }

    suspend fun <T> write(block: SQLiteConnection.() -> T): T = withContext(dispatcher) {
        connection.execSQL("BEGIN IMMEDIATE")
        val result = try {
            connection.block().also { connection.execSQL("COMMIT") }
        } catch (t: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw t
        }
        _version.update { it + 1 }
        result
    }

    // ---- Tasks ----

    suspend fun allTasks(): List<TaskRecord> = read {
        query("SELECT $TASK_COLUMNS FROM task WHERE deleted = 0 ORDER BY date, created_at, id") { it.toTask() }
    }

    suspend fun task(id: String): LocalTask? = read { taskRow(id) }

    suspend fun putLocal(record: TaskRecord) = write { upsertTask(record, dirty = true) }

    // ---- Settings ----

    suspend fun settings(): Map<String, String> = read {
        query("SELECT key, value FROM setting WHERE deleted = 0") { it.getText(0) to it.getText(1) }.toMap()
    }

    suspend fun setting(key: String): SettingRecord? = read { settingRow(key)?.first }

    suspend fun putLocalSetting(record: SettingRecord) = write { upsertSetting(record, dirty = true) }

    // ---- Device-local values ----

    suspend fun meta(key: String): String? = read { metaValue(key) }

    suspend fun setMeta(key: String, value: String) = write { putMeta(key, value) }

    /** A random id for this install, created on first use. It labels this device's edits. */
    suspend fun deviceId(): String = write {
        metaValue(META_DEVICE_ID) ?: Uuid.random().toHexString().also { putMeta(META_DEVICE_ID, it) }
    }

    // ---- Sync ----

    suspend fun lastRev(): Long = read { metaValue(META_LAST_REV)?.toLongOrNull() ?: 0 }

    suspend fun pendingCount(): Int = read {
        query("SELECT (SELECT COUNT(*) FROM task WHERE dirty = 1) + (SELECT COUNT(*) FROM setting WHERE dirty = 1)") { it.getLong(0).toInt() }
            .single()
    }

    suspend fun pendingTasks(limit: Int): List<TaskRecord> = read {
        query("SELECT $TASK_COLUMNS FROM task WHERE dirty = 1 ORDER BY updated_at LIMIT ?", { bindInt(1, limit) }) { it.toTask() }
    }

    suspend fun pendingSettings(): List<SettingRecord> = read {
        query("SELECT key, value, updated_at, updated_by, deleted FROM setting WHERE dirty = 1") { it.toSetting() }
    }

    /**
     * Applies a server answer in one transaction.
     *
     * A server record replaces the local one unless this device holds a newer, unsent edit of it. A pushed
     * record is confirmed (no longer dirty) only if it is still exactly what was sent, so an edit made
     * while the request was in flight is kept for the next push.
     */
    suspend fun applySync(response: SyncResponse, pushedTasks: List<TaskRecord>, pushedSettings: List<SettingRecord>) = write {
        for (remote in response.tasks) {
            val local = taskRow(remote.id)
            val keepLocal = local != null && local.dirty && local.record.supersedes(remote)
            if (!keepLocal) upsertTask(remote, dirty = false)
        }
        for (remote in response.settings) {
            val local = settingRow(remote.key)
            val keepLocal = local != null && local.second && local.first.supersedes(remote)
            if (!keepLocal) upsertSetting(remote, dirty = false)
        }
        // Accepted, or refused as malformed (resending would never help): either way, done with this version.
        for (sent in pushedTasks) {
            val now = taskRow(sent.id) ?: continue
            if (now.dirty && now.record.sameStampAs(sent)) markTaskClean(sent.id)
        }
        for (sent in pushedSettings) {
            val now = settingRow(sent.key) ?: continue
            if (now.second && now.first.sameStampAs(sent)) markSettingClean(sent.key)
        }
        putMeta(META_LAST_REV, response.rev.toString())
    }

    /** After pointing at a different server: start from scratch there and upload everything this device has. */
    suspend fun resetForNewServer() = write {
        execSQL("UPDATE task SET dirty = 1")
        execSQL("UPDATE setting SET dirty = 1")
        putMeta(META_LAST_REV, "0")
    }

    // ---- SQL helpers (call only inside read/write) ----

    private fun SQLiteConnection.taskRow(id: String): LocalTask? =
        query("SELECT $TASK_COLUMNS, dirty FROM task WHERE id = ?", { bindText(1, id) }) {
            LocalTask(it.toTask(), it.getLong(TASK_COLUMN_COUNT) == 1L)
        }.firstOrNull()

    private fun SQLiteConnection.settingRow(key: String): Pair<SettingRecord, Boolean>? =
        query("SELECT key, value, updated_at, updated_by, deleted, dirty FROM setting WHERE key = ?", { bindText(1, key) }) {
            it.toSetting() to (it.getLong(5) == 1L)
        }.firstOrNull()

    private fun SQLiteConnection.upsertTask(r: TaskRecord, dirty: Boolean) {
        prepare(
            "INSERT INTO task (id, title, subject, paper, minutes, date, done, done_at, created_at, updated_at, updated_by, deleted, dirty) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET " +
                "title = excluded.title, subject = excluded.subject, paper = excluded.paper, minutes = excluded.minutes, " +
                "date = excluded.date, done = excluded.done, done_at = excluded.done_at, created_at = excluded.created_at, " +
                "updated_at = excluded.updated_at, updated_by = excluded.updated_by, deleted = excluded.deleted, dirty = excluded.dirty",
        ).use { st ->
            st.bindText(1, r.id)
            st.bindText(2, r.title)
            st.bindText(3, r.subject)
            st.bindText(4, r.paper)
            st.bindInt(5, r.minutes)
            st.bindText(6, r.date)
            st.bindBoolean(7, r.done)
            r.doneAt?.let { st.bindLong(8, it) } ?: st.bindNull(8)
            st.bindLong(9, r.createdAt)
            st.bindLong(10, r.updatedAt)
            st.bindText(11, r.updatedBy)
            st.bindBoolean(12, r.deleted)
            st.bindBoolean(13, dirty)
            st.step()
        }
    }

    private fun SQLiteConnection.upsertSetting(r: SettingRecord, dirty: Boolean) {
        prepare(
            "INSERT INTO setting (key, value, updated_at, updated_by, deleted, dirty) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, " +
                "updated_by = excluded.updated_by, deleted = excluded.deleted, dirty = excluded.dirty",
        ).use { st ->
            st.bindText(1, r.key)
            st.bindText(2, r.value)
            st.bindLong(3, r.updatedAt)
            st.bindText(4, r.updatedBy)
            st.bindBoolean(5, r.deleted)
            st.bindBoolean(6, dirty)
            st.step()
        }
    }

    private fun SQLiteConnection.markTaskClean(id: String) =
        prepare("UPDATE task SET dirty = 0 WHERE id = ?").use { it.bindText(1, id); it.step() }

    private fun SQLiteConnection.markSettingClean(key: String) =
        prepare("UPDATE setting SET dirty = 0 WHERE key = ?").use { it.bindText(1, key); it.step() }

    private fun SQLiteConnection.metaValue(key: String): String? =
        query("SELECT value FROM meta WHERE key = ?", { bindText(1, key) }) { it.getText(0) }.firstOrNull()

    private fun SQLiteConnection.putMeta(key: String, value: String) {
        prepare("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use {
            it.bindText(1, key)
            it.bindText(2, value)
            it.step()
        }
    }

    private fun <T> SQLiteConnection.query(sql: String, bind: SQLiteStatement.() -> Unit = {}, map: (SQLiteStatement) -> T): List<T> =
        prepare(sql).use { st ->
            st.bind()
            buildList { while (st.step()) add(map(st)) }
        }

    private fun SQLiteStatement.toTask() = TaskRecord(
        id = getText(0),
        title = getText(1),
        subject = getText(2),
        paper = getText(3),
        minutes = getInt(4),
        date = getText(5),
        done = getBoolean(6),
        doneAt = if (isNull(7)) null else getLong(7),
        createdAt = getLong(8),
        updatedAt = getLong(9),
        updatedBy = getText(10),
        deleted = getBoolean(11),
    )

    private fun SQLiteStatement.toSetting() = SettingRecord(
        key = getText(0),
        value = getText(1),
        updatedAt = getLong(2),
        updatedBy = getText(3),
        deleted = getBoolean(4),
    )

    private fun migrate(db: SQLiteConnection) {
        val version = db.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0 }
        if (version < 1) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS task (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, subject TEXT NOT NULL, " +
                    "paper TEXT NOT NULL, minutes INTEGER NOT NULL, date TEXT NOT NULL, done INTEGER NOT NULL, done_at INTEGER, " +
                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL, " +
                    "deleted INTEGER NOT NULL, dirty INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS task_date ON task (date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS task_dirty ON task (dirty)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL, deleted INTEGER NOT NULL, dirty INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            db.execSQL("PRAGMA user_version = 1")
        }
    }

    companion object {
        const val META_DEVICE_ID = "device_id"
        const val META_LAST_REV = "last_rev"
        const val META_LAST_SYNC = "last_sync"

        private const val TASK_COLUMNS =
            "id, title, subject, paper, minutes, date, done, done_at, created_at, updated_at, updated_by, deleted"
        private const val TASK_COLUMN_COUNT = 12
    }
}
