package xyz.aezdek.daybook.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import xyz.aezdek.daybook.core.DaybookJson
import xyz.aezdek.daybook.core.Limits
import xyz.aezdek.daybook.core.SettingRecord
import xyz.aezdek.daybook.core.Stamped
import xyz.aezdek.daybook.core.SyncRequest
import xyz.aezdek.daybook.core.SyncResponse
import xyz.aezdek.daybook.core.TaskRecord
import xyz.aezdek.daybook.core.problem
import xyz.aezdek.daybook.core.supersedes
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The server's copy of every record, in one SQLite file.
 *
 * Every accepted write gets the next value of one revision counter, so a client asks for "everything
 * after revision N" and never misses a change. Writes go through one connection under a mutex: this is a
 * single-person service and the simplest correct thing is to serialise.
 */
class ServerStore private constructor(private val connection: Connection) : AutoCloseable {

    private val mutex = Mutex()
    private val _rev = MutableStateFlow(readRev())

    /** The latest revision, for long-polling clients. */
    val rev: StateFlow<Long> = _rev.asStateFlow()

    suspend fun taskCount(): Long = mutex.withLock {
        withContext(Dispatchers.IO) {
            connection.prepareStatement("SELECT COUNT(*) FROM tasks WHERE deleted = 0").use { st ->
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
            }
        }
    }

    suspend fun sync(request: SyncRequest, now: Long): SyncResponse = mutex.withLock {
        withContext(Dispatchers.IO) { syncLocked(request, now) }
    }

    private fun syncLocked(request: SyncRequest, now: Long): SyncResponse {
        // A device whose clock runs far ahead would otherwise win every conflict until real time caught up.
        val latestAllowed = now + CLOCK_SKEW_ALLOWANCE_MS
        val rejected = mutableListOf<String>()
        val losers = mutableListOf<TaskRecord>()
        val losingSettings = mutableListOf<SettingRecord>()
        var rev = readRev()
        val startRev = rev

        connection.autoCommit = false
        try {
            for (incoming in request.tasks.take(Limits.MAX_BATCH)) {
                val record = incoming.copy(updatedAt = minOf(incoming.updatedAt, latestAllowed))
                val problem = record.problem()
                if (problem != null) {
                    log.warn("Rejected task {}: {}", incoming.id.take(Limits.MAX_ID), problem)
                    rejected += incoming.id
                    continue
                }
                val existing = loadTask(record.id)
                if (existing == null || record.supersedes(existing)) {
                    rev += 1
                    upsert("tasks", "id", record.id, rev, record, DaybookJson.encodeToString(TaskRecord.serializer(), record), if (record.deleted) 1 else 0)
                } else {
                    losers += existing
                }
            }
            for (incoming in request.settings.take(Limits.MAX_BATCH)) {
                val record = incoming.copy(updatedAt = minOf(incoming.updatedAt, latestAllowed))
                val problem = record.problem()
                if (problem != null) {
                    log.warn("Rejected setting {}: {}", incoming.key.take(Limits.MAX_SETTING_KEY), problem)
                    rejected += incoming.key
                    continue
                }
                val existing = loadSetting(record.key)
                if (existing == null || record.supersedes(existing)) {
                    rev += 1
                    upsert("settings", "key", record.key, rev, record, DaybookJson.encodeToString(SettingRecord.serializer(), record), if (record.deleted) 1 else 0)
                } else {
                    losingSettings += existing
                }
            }
            if (rev != startRev) writeRev(rev)
            connection.commit()
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
        if (rev != startRev) _rev.value = rev

        // Pull: everything after `since`, a page at a time. Both tables share one counter, so the page is
        // cut at a revision and settings are limited to the same cut.
        val since = request.since.coerceAtLeast(0)
        val page = changedTasks(since, Limits.PAGE + 1)
        val hasMore = page.size > Limits.PAGE
        val tasks = if (hasMore) page.take(Limits.PAGE) else page
        val cut = if (hasMore) tasks.last().first else rev
        val settings = changedSettings(since, cut)

        val sentTaskIds = tasks.mapTo(HashSet()) { it.second.id }
        val sentSettingKeys = settings.mapTo(HashSet()) { it.key }
        return SyncResponse(
            rev = cut,
            tasks = tasks.map { it.second } + losers.filter { it.id !in sentTaskIds },
            settings = settings + losingSettings.filter { it.key !in sentSettingKeys },
            rejected = rejected,
            hasMore = hasMore,
            serverTime = now,
        )
    }

    private fun loadTask(id: String): TaskRecord? =
        connection.prepareStatement("SELECT body FROM tasks WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) DaybookJson.decodeFromString(TaskRecord.serializer(), rs.getString(1)) else null }
        }

    private fun loadSetting(key: String): SettingRecord? =
        connection.prepareStatement("SELECT body FROM settings WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) DaybookJson.decodeFromString(SettingRecord.serializer(), rs.getString(1)) else null }
        }

    private fun upsert(table: String, keyColumn: String, key: String, rev: Long, record: Stamped, body: String, deleted: Int) {
        connection.prepareStatement(
            "INSERT INTO $table ($keyColumn, rev, updated_at, updated_by, deleted, body) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT($keyColumn) DO UPDATE SET rev = excluded.rev, updated_at = excluded.updated_at, " +
                "updated_by = excluded.updated_by, deleted = excluded.deleted, body = excluded.body",
        ).use { st ->
            st.setString(1, key)
            st.setLong(2, rev)
            st.setLong(3, record.updatedAt)
            st.setString(4, record.updatedBy)
            st.setInt(5, deleted)
            st.setString(6, body)
            st.executeUpdate()
        }
    }

    private fun changedTasks(since: Long, limit: Int): List<Pair<Long, TaskRecord>> =
        connection.prepareStatement("SELECT rev, body FROM tasks WHERE rev > ? ORDER BY rev LIMIT ?").use { st ->
            st.setLong(1, since)
            st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getLong(1) to DaybookJson.decodeFromString(TaskRecord.serializer(), rs.getString(2)))
                }
            }
        }

    private fun changedSettings(since: Long, upTo: Long): List<SettingRecord> =
        connection.prepareStatement("SELECT body FROM settings WHERE rev > ? AND rev <= ? ORDER BY rev").use { st ->
            st.setLong(1, since)
            st.setLong(2, upTo)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(DaybookJson.decodeFromString(SettingRecord.serializer(), rs.getString(1))) }
            }
        }

    private fun readRev(): Long =
        connection.prepareStatement("SELECT value FROM meta WHERE key = 'rev'").use { st ->
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }

    private fun writeRev(rev: Long) {
        connection.prepareStatement("INSERT INTO meta (key, value) VALUES ('rev', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use { st ->
            st.setLong(1, rev)
            st.executeUpdate()
        }
    }

    override fun close() = connection.close()

    companion object {
        private val log = LoggerFactory.getLogger(ServerStore::class.java)
        const val CLOCK_SKEW_ALLOWANCE_MS: Long = 5 * 60 * 1000

        fun open(path: String): ServerStore {
            if (path != ":memory:") File(path).absoluteFile.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:$path")
            connection.createStatement().use { st ->
                if (path != ":memory:") st.execute("PRAGMA journal_mode = WAL")
                st.execute("PRAGMA synchronous = NORMAL")
                st.execute("PRAGMA busy_timeout = 5000")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY NOT NULL, rev INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL, deleted INTEGER NOT NULL, body TEXT NOT NULL)",
                )
                st.execute("CREATE INDEX IF NOT EXISTS tasks_rev ON tasks (rev)")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY NOT NULL, rev INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL, deleted INTEGER NOT NULL, body TEXT NOT NULL)",
                )
                st.execute("CREATE INDEX IF NOT EXISTS settings_rev ON settings (rev)")
                st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)")
            }
            return ServerStore(connection)
        }
    }
}
