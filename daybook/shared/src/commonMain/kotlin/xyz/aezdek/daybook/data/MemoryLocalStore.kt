package xyz.aezdek.daybook.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.aezdek.daybook.core.DaybookJson
import xyz.aezdek.daybook.core.SettingRecord
import xyz.aezdek.daybook.core.SyncResponse
import xyz.aezdek.daybook.core.TaskRecord
import kotlin.uuid.Uuid

/** Where [MemoryLocalStore] keeps its saved copy: one text document. */
interface SnapshotStorage {
    fun load(): String?
    fun save(snapshot: String)
}

/**
 * [LocalStore] held in memory and written through to [storage] after every change, as one JSON document.
 * The web app uses it with the browser's local storage; with no storage it is a plain in-memory store.
 * A year of daily tasks is well under a megabyte.
 */
class MemoryLocalStore(private val storage: SnapshotStorage? = null) : LocalStore {

    @Serializable
    private data class Snapshot(
        val tasks: List<LocalTask> = emptyList(),
        val settings: List<LocalSetting> = emptyList(),
        val meta: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class LocalSetting(val record: SettingRecord, val dirty: Boolean)

    private val mutex = Mutex()
    private val tasks = LinkedHashMap<String, LocalTask>()
    private val settings = LinkedHashMap<String, LocalSetting>()
    private val meta = LinkedHashMap<String, String>()
    private val _version = MutableStateFlow(0L)
    override val version: StateFlow<Long> = _version.asStateFlow()

    init {
        val saved = runCatching { storage?.load()?.let { DaybookJson.decodeFromString(Snapshot.serializer(), it) } }.getOrNull()
        if (saved != null) {
            saved.tasks.forEach { tasks[it.record.id] = it }
            saved.settings.forEach { settings[it.record.key] = it }
            meta.putAll(saved.meta)
        }
    }

    private suspend fun <T> read(block: () -> T): T = mutex.withLock { block() }

    private suspend fun <T> write(block: () -> T): T = mutex.withLock {
        val result = block()
        storage?.save(
            DaybookJson.encodeToString(
                Snapshot.serializer(),
                Snapshot(tasks.values.toList(), settings.values.toList(), meta.toMap()),
            ),
        )
        result
    }.also { _version.update { it + 1 } }

    override suspend fun allTasks(): List<TaskRecord> = read {
        tasks.values.map { it.record }.filter { !it.deleted }.sortedWith(compareBy({ it.date }, { it.createdAt }, { it.id }))
    }

    override suspend fun task(id: String): LocalTask? = read { tasks[id] }

    override suspend fun putLocal(record: TaskRecord) = write { tasks[record.id] = LocalTask(record, dirty = true) }

    override suspend fun settings(): Map<String, String> = read {
        settings.values.filter { !it.record.deleted }.associate { it.record.key to it.record.value }
    }

    override suspend fun setting(key: String): SettingRecord? = read { settings[key]?.record }

    override suspend fun putLocalSetting(record: SettingRecord) = write { settings[record.key] = LocalSetting(record, dirty = true) }

    override suspend fun meta(key: String): String? = read { meta[key] }

    override suspend fun setMeta(key: String, value: String) = write { meta[key] = value }

    override suspend fun deviceId(): String = write {
        meta.getOrPut(LocalStore.META_DEVICE_ID) { Uuid.random().toHexString() }
    }

    override suspend fun lastRev(): Long = read { meta[LocalStore.META_LAST_REV]?.toLongOrNull() ?: 0 }

    override suspend fun pendingCount(): Int = read { tasks.values.count { it.dirty } + settings.values.count { it.dirty } }

    override suspend fun pendingTasks(limit: Int): List<TaskRecord> = read {
        tasks.values.filter { it.dirty }.map { it.record }.sortedBy { it.updatedAt }.take(limit)
    }

    override suspend fun pendingSettings(): List<SettingRecord> = read { settings.values.filter { it.dirty }.map { it.record } }

    override suspend fun applySync(response: SyncResponse, pushedTasks: List<TaskRecord>, pushedSettings: List<SettingRecord>) = write {
        for (remote in response.tasks) {
            val local = tasks[remote.id]
            if (!SyncMerge.keepLocal(local?.record, local?.dirty == true, remote)) tasks[remote.id] = LocalTask(remote, dirty = false)
        }
        for (remote in response.settings) {
            val local = settings[remote.key]
            if (!SyncMerge.keepLocal(local?.record, local?.dirty == true, remote)) settings[remote.key] = LocalSetting(remote, dirty = false)
        }
        for (sent in pushedTasks) {
            val now = tasks[sent.id]
            if (now != null && SyncMerge.isConfirmed(now.record, now.dirty, sent)) tasks[sent.id] = now.copy(dirty = false)
        }
        for (sent in pushedSettings) {
            val now = settings[sent.key]
            if (now != null && SyncMerge.isConfirmed(now.record, now.dirty, sent)) settings[sent.key] = now.copy(dirty = false)
        }
        meta[LocalStore.META_LAST_REV] = response.rev.toString()
    }

    override suspend fun resetForNewServer() = write {
        for (id in tasks.keys.toList()) tasks[id] = tasks.getValue(id).copy(dirty = true)
        for (key in settings.keys.toList()) settings[key] = settings.getValue(key).copy(dirty = true)
        meta[LocalStore.META_LAST_REV] = "0"
    }
}
