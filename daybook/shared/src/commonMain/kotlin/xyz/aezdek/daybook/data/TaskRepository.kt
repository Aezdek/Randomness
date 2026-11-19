package xyz.aezdek.daybook.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.core.Limits
import xyz.aezdek.daybook.core.SettingKeys
import xyz.aezdek.daybook.core.SettingRecord
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.core.TaskRecord
import xyz.aezdek.daybook.core.nextStamp
import kotlin.uuid.Uuid

/**
 * Every change the screens make. Each one is written locally first, stamped with this device, and then
 * [onLocalChange] tells the sync engine there is something to push.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepository(
    private val store: LocalStore,
    private val deviceId: String,
    private val clock: () -> Long,
    private val onLocalChange: () -> Unit,
) {
    val tasks: Flow<List<Task>> = store.version
        .mapLatest { store.allTasks().map { it.toTask() } }
        .distinctUntilChanged()

    val examDate: Flow<LocalDate?> = store.version
        .mapLatest { store.settings()[SettingKeys.EXAM_DATE]?.let(::parseDateOrNull) }
        .distinctUntilChanged()

    val pendingCount: Flow<Int> = store.version
        .mapLatest { store.pendingCount() }
        .distinctUntilChanged()

    suspend fun add(draft: TaskDraft): String {
        val now = clock()
        val record = TaskRecord(
            id = Uuid.random().toHexString(),
            title = draft.title.trim().take(Limits.MAX_TITLE),
            subject = draft.subject.id,
            paper = draft.paper,
            minutes = draft.minutes.coerceIn(0, Limits.MAX_MINUTES),
            date = draft.date.toString(),
            createdAt = now,
            updatedAt = now,
            updatedBy = deviceId,
        )
        store.putLocal(record)
        onLocalChange()
        return record.id
    }

    suspend fun edit(id: String, draft: TaskDraft) = change(id) {
        it.copy(
            title = draft.title.trim().take(Limits.MAX_TITLE),
            subject = draft.subject.id,
            paper = draft.paper,
            minutes = draft.minutes.coerceIn(0, Limits.MAX_MINUTES),
            date = draft.date.toString(),
        )
    }

    suspend fun setDone(id: String, done: Boolean) = change(id) {
        it.copy(done = done, doneAt = if (done) clock() else null)
    }

    suspend fun move(id: String, date: LocalDate) = change(id) { it.copy(date = date.toString()) }

    suspend fun delete(id: String) = change(id) { it.copy(deleted = true) }

    /** Undoes [delete]. */
    suspend fun restore(id: String) = change(id) { it.copy(deleted = false) }

    suspend fun setExamDate(date: LocalDate?) {
        val previous = store.setting(SettingKeys.EXAM_DATE)
        store.putLocalSetting(
            SettingRecord(
                key = SettingKeys.EXAM_DATE,
                value = date?.toString() ?: "",
                updatedAt = nextStamp(clock(), previous?.updatedAt),
                updatedBy = deviceId,
                deleted = date == null,
            ),
        )
        onLocalChange()
    }

    private suspend fun change(id: String, transform: (TaskRecord) -> TaskRecord) {
        val current = store.task(id)?.record ?: return
        val updated = transform(current).copy(updatedAt = nextStamp(clock(), current.updatedAt), updatedBy = deviceId)
        if (updated == current) return
        store.putLocal(updated)
        onLocalChange()
    }
}

fun TaskRecord.toTask(): Task = Task(
    id = id,
    title = title,
    subject = Subject.fromId(subject) ?: Subject.MATHS,
    paper = paper,
    minutes = minutes,
    date = parseDateOrNull(date) ?: LocalDate(1970, 1, 1),
    done = done,
    createdAt = createdAt,
)

fun parseDateOrNull(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
