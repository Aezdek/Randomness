package xyz.aezdek.aezprint.data.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.data.di.ApplicationScope
import xyz.aezdek.aezprint.domain.job.JobEvent
import xyz.aezdek.aezprint.domain.job.JobStateMachine
import xyz.aezdek.aezprint.domain.layout.Imposer
import xyz.aezdek.aezprint.domain.layout.Imposition
import xyz.aezdek.aezprint.domain.model.DuplexSupport
import xyz.aezdek.aezprint.domain.model.FakeServerConfig
import xyz.aezdek.aezprint.domain.model.Job
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.PageSize
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.PrinterCapabilities
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.RemoteDocument
import xyz.aezdek.aezprint.domain.model.Render
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.PrintServerException
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import xyz.aezdek.aezprint.domain.validation.OptionField
import xyz.aezdek.aezprint.domain.validation.OptionsValidator
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * An in-process implementation of the v1 contract modelled on the ML-2165: mono, 600/1200 dpi and
 * manual duplex. Jobs run on a timer and pause in `awaiting_flip` exactly like the real server.
 */
@Singleton
class FakePrintServer @Inject constructor(
    private val settings: SettingsRepository,
    private val renderer: FakeSheetRenderer,
    @ApplicationScope private val scope: CoroutineScope,
) : PrintServer {

    private data class StoredDocument(val file: File, val pages: List<PageSize>)
    private data class StoredRender(val documentId: String, val options: PrintOptions, val imposition: Imposition)
    private class FakeJob(val job: MutableStateFlow<Job>, val flip: MutableStateFlow<Boolean>, var runner: CoroutineJob? = null)

    private val documents = ConcurrentHashMap<String, StoredDocument>()
    private val renders = ConcurrentHashMap<String, StoredRender>()
    private val jobs = ConcurrentHashMap<String, FakeJob>()

    private val media = listOf(
        "iso_a4_210x297mm",
        "na_letter_8.5x11in",
        "na_legal_8.5x14in",
        "iso_a5_148x210mm",
        "jis_b5_182x257mm",
        "na_executive_7.25x10.5in",
        "na_foolscap_8.5x13in",
    ).mapNotNull(MediaSize::fromPwg)

    private suspend fun config(): FakeServerConfig = settings.fakeServerConfig.first()

    private suspend fun latency(factor: Double = 1.0) = delay((config().latencyMs * factor).roundToLong())

    override suspend fun printer(): Printer {
        latency(0.5)
        val configured = config().printerStatus
        val busy = jobs.values.any { it.job.value.state == JobState.PROCESSING }
        val status = if (configured == PrinterStatus.IDLE && busy) PrinterStatus.PRINTING else configured
        return Printer(
            name = "Samsung ML-2165 (fake)",
            status = status,
            stateReasons = reasonsFor(status),
            accepting = status != PrinterStatus.OFFLINE,
            media = media,
            capabilities = PrinterCapabilities(color = false, duplex = DuplexSupport.MANUAL, resolutions = listOf(600, 1200)),
        )
    }

    private fun reasonsFor(status: PrinterStatus) = when (status) {
        PrinterStatus.OUT_OF_PAPER -> listOf("media-empty-error")
        PrinterStatus.JAM -> listOf("media-jam-error")
        PrinterStatus.OFFLINE -> listOf("offline-report")
        else -> emptyList()
    }

    override suspend fun upload(document: LocalDocument, onProgress: (Float) -> Unit): RemoteDocument {
        val steps = 10
        repeat(steps) { step ->
            latency(0.15)
            if (config().failUploads && step == steps / 2) throw PrintServerException.Offline(IOException("Simulated network drop"))
            onProgress((step + 1).toFloat() / steps)
        }
        val id = "doc_" + UUID.randomUUID().toString().take(8)
        val pages = document.pages.map { it.toPageSize() }
        documents[id] = StoredDocument(File(document.path), pages)
        return RemoteDocument(id, pages.size, pages, expiresAt = System.currentTimeMillis() + 24 * 3_600_000L)
    }

    override suspend fun render(documentId: String, options: PrintOptions): Render {
        latency()
        val document = documents[documentId] ?: throw PrintServerException.NotFound("Document expired")
        reject(options, document)
        val imposition = Imposer.impose(options, document.pages, media)
        val id = "render_" + UUID.randomUUID().toString().take(8)
        renders[id] = StoredRender(documentId, options, imposition)
        return Render(id, imposition.sheetCount, imposition.sides)
    }

    private suspend fun reject(options: PrintOptions, document: StoredDocument) {
        val errors = OptionsValidator.validate(options, document.pages.size, printer())
        if (errors.isNotEmpty()) {
            throw PrintServerException.Unsupported(errors.first().message, errors.map { it.field })
        }
        if (config().rejectPosterOptions && options.sizing.mode == SizingMode.POSTER) {
            throw PrintServerException.Unsupported(
                "Poster tiling isn't supported for this paper",
                listOf(OptionField.POSTER_SCALE, OptionField.POSTER_OVERLAP),
            )
        }
    }

    override suspend fun sheet(renderId: String, face: Int, widthPx: Int): ByteArray {
        latency(0.5)
        val render = renders[renderId] ?: throw PrintServerException.NotFound("Render expired")
        val target = render.imposition.faces.getOrNull(face - 1) ?: throw PrintServerException.NotFound("No sheet $face")
        val document = documents.getValue(render.documentId)
        return renderer.render(document.file, target, render.options, widthPx)
    }

    override suspend fun submit(documentId: String, options: PrintOptions): String {
        latency()
        val document = documents[documentId] ?: throw PrintServerException.NotFound("Document expired")
        reject(options, document)
        val imposition = Imposer.impose(options, document.pages, media)
        val id = "job_" + UUID.randomUUID().toString().take(8)
        val sheets = imposition.sheetCount * options.copies
        val twoSided = imposition.sides == 2
        val total = if (twoSided) sheets * 2 else sheets
        val fake = FakeJob(
            job = MutableStateFlow(Job(id, JobState.QUEUED, 0, total, "Waiting for the printer")),
            flip = MutableStateFlow(false),
        )
        jobs[id] = fake
        fake.runner = scope.launch { simulate(fake, sheets, twoSided) }
        return id
    }

    private suspend fun simulate(fake: FakeJob, sheets: Int, twoSided: Boolean) {
        delay(config().queueDelayMs)
        advance(fake, JobEvent.Start, "Printing")
        printPass(fake, sheets, if (twoSided) "Printing front sides" else "Printing")
        if (twoSided) {
            advance(fake, JobEvent.FirstPassDone, "Flip the stack and reinsert it")
            fake.flip.first { it }
            advance(fake, JobEvent.Continue, "Printing back sides")
            printPass(fake, sheets, "Printing back sides")
        }
        advance(fake, JobEvent.Finish, null)
    }

    private suspend fun printPass(fake: FakeJob, sheets: Int, label: String) {
        repeat(sheets) {
            waitWhilePrinterNeedsHelp(fake, label)
            delay((config().secondsPerSheet * 1000).roundToLong())
            fake.job.update { it.copy(sheetsCompleted = it.sheetsCompleted + 1, message = label) }
        }
    }

    private suspend fun waitWhilePrinterNeedsHelp(fake: FakeJob, label: String) {
        while (true) {
            val problem = when (config().printerStatus) {
                PrinterStatus.JAM -> "Paper jam: clear it to continue"
                PrinterStatus.OUT_OF_PAPER -> "Out of paper: load the tray to continue"
                PrinterStatus.OFFLINE -> "Printer is offline"
                else -> null
            } ?: break
            fake.job.update { it.copy(message = problem) }
            delay(500)
        }
        fake.job.update { it.copy(message = label) }
    }

    private fun advance(fake: FakeJob, event: JobEvent, message: String?) {
        fake.job.update { it.copy(state = JobStateMachine.reduce(it.state, event), message = message) }
    }

    override suspend fun job(jobId: String): Job {
        latency(0.25)
        return lookup(jobId).job.value
    }

    override suspend fun continueJob(jobId: String) {
        latency(0.5)
        val fake = lookup(jobId)
        if (!JobStateMachine.canContinue(fake.job.value.state)) {
            throw PrintServerException.Server(409, "not_awaiting_flip", "This job isn't waiting for a flip")
        }
        fake.flip.value = true
    }

    override suspend fun cancel(jobId: String) {
        latency(0.5)
        val fake = lookup(jobId)
        if (!JobStateMachine.canCancel(fake.job.value.state)) return
        fake.runner?.cancel()
        advance(fake, JobEvent.Cancel, "Canceled")
    }

    override suspend fun jobs(limit: Int): List<Job> {
        latency(0.5)
        return jobs.values.map { it.job.value }.takeLast(limit)
    }

    private fun lookup(jobId: String) = jobs[jobId] ?: throw PrintServerException.NotFound("No job $jobId")
}
