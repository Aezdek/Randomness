package xyz.aezdek.aezprint

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.data.fake.FakePrintServer
import xyz.aezdek.aezprint.data.fake.FakeSheetRenderer
import xyz.aezdek.aezprint.data.jobs.PrintJobRunner
import xyz.aezdek.aezprint.domain.model.AppSettings
import xyz.aezdek.aezprint.domain.model.FakeServerConfig
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PrintPhase
import xyz.aezdek.aezprint.domain.model.Preset
import xyz.aezdek.aezprint.domain.model.PresetLibrary
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.model.SerializablePageSize
import xyz.aezdek.aezprint.domain.model.ThemeMode
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import xyz.aezdek.aezprint.domain.repository.NetworkStatus
import xyz.aezdek.aezprint.domain.repository.PresetRepository
import xyz.aezdek.aezprint.domain.repository.PrintQueue
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import xyz.aezdek.aezprint.feature.jobs.history.JobHistoryViewModel
import xyz.aezdek.aezprint.feature.jobs.progress.JobProgressViewModel
import xyz.aezdek.aezprint.feature.print.PrintViewModel
import java.io.File
import java.util.UUID

/** The real fake server and job runner wired to in-memory stores, with timings shrunk for tests. */
class TestEnvironment(directory: File) : AutoCloseable {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> e.printStackTrace() })
    val settings = InMemorySettings()
    val server = FakePrintServer(settings, FakeSheetRenderer(), scope)
    val documents = InMemoryDocuments()
    val records = InMemoryRecords()
    val presets = InMemoryPresets()
    val network = object : NetworkStatus { override val isOnline: Flow<Boolean> = flowOf(true) }
    val runner = PrintJobRunner(server, records, documents)
    val queue = InProcessQueue(runner, records, documents, scope)

    val document: LocalDocument = run {
        val file = File(directory, "sample.pdf").apply { writeText("%PDF-1.4\n%%EOF\n") }
        val a4 = SerializablePageSize(595.28, 841.89)
        LocalDocument("doc-1", "Quarterly report.pdf", file.absolutePath, file.length(), 3, List(3) { a4 }, null, 1L)
            .also(documents::add)
    }

    fun printViewModel() = PrintViewModel(
        SavedStateHandle(mapOf(PrintViewModel.ARG_DOCUMENT_ID to document.id)),
        documents, server, presets, queue, network,
    )

    fun jobViewModel(localId: String) = JobProgressViewModel(
        SavedStateHandle(mapOf(JobProgressViewModel.ARG_LOCAL_ID to localId)), records, network, queue,
    )

    fun historyViewModel() = JobHistoryViewModel(records, queue)

    override fun close() = scope.cancel()
}

class InMemorySettings : SettingsRepository {
    private val app = MutableStateFlow(AppSettings(useFakeServer = true))
    private val fake = MutableStateFlow(FakeServerConfig(latencyMs = 0, secondsPerSheet = 0.05, queueDelayMs = 50))
    override val settings: Flow<AppSettings> = app
    override val fakeServerConfig: Flow<FakeServerConfig> = fake
    override suspend fun setServerUrl(url: String) = app.update { it.copy(serverUrl = url) }
    override suspend fun setUseFakeServer(enabled: Boolean) = app.update { it.copy(useFakeServer = enabled) }
    override suspend fun setBiometricLock(enabled: Boolean) = app.update { it.copy(biometricLock = enabled) }
    override suspend fun setThemeMode(mode: ThemeMode) = app.update { it.copy(themeMode = mode) }
    override suspend fun setDynamicColor(enabled: Boolean) = app.update { it.copy(dynamicColor = enabled) }
    override suspend fun updateFakeServer(transform: (FakeServerConfig) -> FakeServerConfig) = fake.update(transform)
}

class InMemoryDocuments : DocumentRepository {
    private val all = MutableStateFlow<List<LocalDocument>>(emptyList())
    override val recents: Flow<List<LocalDocument>> = all
    fun add(document: LocalDocument) = all.update { it + document }
    override suspend fun import(uri: String): LocalDocument = error("Not used in UI tests")
    override suspend fun get(id: String) = all.value.firstOrNull { it.id == id }
    override suspend fun touch(id: String) = Unit
    override suspend fun remove(id: String) = all.update { list -> list.filterNot { it.id == id } }
}

class InMemoryRecords : PrintRecordRepository {
    val all = MutableStateFlow<List<PrintRecord>>(emptyList())
    override val records: Flow<List<PrintRecord>> = all.map { list -> list.sortedByDescending { it.createdAt } }
    override fun record(localId: String) = all.map { list -> list.firstOrNull { it.localId == localId } }
    override suspend fun get(localId: String) = record(localId).first()
    override suspend fun upsert(record: PrintRecord) = all.update { list -> list.filterNot { it.localId == record.localId } + record }
    override suspend fun update(localId: String, transform: (PrintRecord) -> PrintRecord) =
        all.update { list -> list.map { if (it.localId == localId) transform(it) else it } }
}

class InMemoryPresets : PresetRepository {
    private val state = MutableStateFlow(PresetLibrary())
    override val library: Flow<PresetLibrary> = state
    override suspend fun save(name: String, options: PrintOptions): Preset =
        Preset(UUID.randomUUID().toString(), name, options).also { preset -> state.update { it.copy(presets = it.presets + preset) } }
    override suspend fun delete(id: String) = state.update { lib -> lib.copy(presets = lib.presets.filterNot { it.id == id }) }
    override suspend fun setDefault(id: String?) = state.update { it.copy(defaultPresetId = id) }
}

/** Same contract as the WorkManager queue, but runs the job runner directly so tests can observe it. */
class InProcessQueue(
    private val runner: PrintJobRunner,
    private val records: PrintRecordRepository,
    private val documents: DocumentRepository,
    private val scope: CoroutineScope,
) : PrintQueue {
    override suspend fun enqueue(document: LocalDocument, options: PrintOptions): String {
        val record = PrintRecord(UUID.randomUUID().toString(), document.id, document.displayName, options, System.nanoTime())
        records.upsert(record)
        scope.launch { runner.run(record.localId) }
        return record.localId
    }

    override suspend fun reprint(localId: String): String {
        val previous = checkNotNull(records.get(localId))
        return enqueue(checkNotNull(documents.get(previous.documentId)), previous.options)
    }

    override suspend fun cancel(localId: String) {
        val record = records.get(localId) ?: return
        if (record.jobId == null) records.update(localId) { it.copy(phase = PrintPhase.CANCELED) } else runner.cancelRemote(localId)
    }

    override suspend fun continueAfterFlip(localId: String) = runner.continueRemote(localId)
}
