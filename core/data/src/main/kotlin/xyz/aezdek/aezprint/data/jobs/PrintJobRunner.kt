package xyz.aezdek.aezprint.data.jobs

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.domain.job.JobStateMachine
import xyz.aezdek.aezprint.domain.model.Job
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.PrintPhase
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.PrintServerException
import javax.inject.Inject

/**
 * Uploads, submits and then follows one print until it ends. Every step is checkpointed in the
 * [PrintRecord], so a retry after process death resumes where it stopped instead of printing twice.
 * Kept free of WorkManager so the same logic runs in UI tests.
 */
class PrintJobRunner @Inject constructor(
    private val server: PrintServer,
    private val records: PrintRecordRepository,
    private val documents: DocumentRepository,
) {

    /** Throws [PrintServerException.Offline] when the caller should retry later. */
    suspend fun run(localId: String, onUpdate: (PrintRecord) -> Unit = {}) {
        val initial = records.get(localId) ?: return
        if (!initial.isActive) return
        try {
            val jobId = initial.jobId ?: submit(initial, onUpdate)
            follow(localId, jobId, onUpdate)
        } catch (e: PrintServerException.Offline) {
            update(localId, onUpdate) { it.copy(message = "Waiting for a connection…") }
            throw e
        } catch (e: PrintServerException) {
            fail(localId, e.message ?: "The print server rejected the job", onUpdate)
        }
    }

    suspend fun fail(localId: String, message: String, onUpdate: (PrintRecord) -> Unit = {}) {
        update(localId, onUpdate) { it.copy(phase = PrintPhase.FAILED, message = message) }
    }

    suspend fun cancelRemote(localId: String, onUpdate: (PrintRecord) -> Unit = {}) {
        val record = records.get(localId) ?: return
        val jobId = record.jobId ?: return
        val state = record.jobState
        if (state != null && !JobStateMachine.canCancel(state)) return
        server.cancel(jobId)
        update(localId, onUpdate) { it.copy(jobState = JobState.CANCELED, phase = PrintPhase.CANCELED, message = "Canceled") }
    }

    suspend fun continueRemote(localId: String, onUpdate: (PrintRecord) -> Unit = {}) {
        val record = records.get(localId) ?: return
        val jobId = record.jobId ?: return
        if (record.jobState != JobState.AWAITING_FLIP) return
        server.continueJob(jobId)
        update(localId, onUpdate) { it.copy(jobState = JobState.PROCESSING, message = "Printing back sides") }
    }

    private suspend fun submit(record: PrintRecord, onUpdate: (PrintRecord) -> Unit): String {
        val localId = record.localId
        val document = documents.get(record.documentId)
            ?: throw PrintServerException.NotFound("The document is no longer on this phone")

        suspend fun upload(): String {
            update(localId, onUpdate) { it.copy(phase = PrintPhase.UPLOADING, uploadProgress = 0f, message = null) }
            val progress = MutableStateFlow(0f)
            val remote = coroutineScope {
                val writer = launch {
                    progress.collect { value ->
                        update(localId, onUpdate) { it.copy(uploadProgress = value) }
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    server.upload(document) { progress.value = it }
                } finally {
                    writer.cancel()
                }
            }
            update(localId, onUpdate) { it.copy(remoteDocumentId = remote.documentId, uploadProgress = 1f) }
            return remote.documentId
        }

        val remoteId = record.remoteDocumentId ?: upload()
        update(localId, onUpdate) { it.copy(phase = PrintPhase.SUBMITTING, message = null) }
        val jobId = try {
            server.submit(remoteId, record.options)
        } catch (e: PrintServerException.NotFound) {
            // The server-side copy expired between upload and submit (or before a retry); upload again once.
            server.submit(upload(), record.options)
        }
        update(localId, onUpdate) { it.copy(phase = PrintPhase.SUBMITTED, jobId = jobId, jobState = JobState.QUEUED) }
        return jobId
    }

    private suspend fun follow(localId: String, jobId: String, onUpdate: (PrintRecord) -> Unit) {
        while (true) {
            val job = server.job(jobId)
            val record = update(localId, onUpdate) { it.merge(job) } ?: return
            if (job.state.isTerminal || record.jobState?.isTerminal == true) return
            delay(if (record.awaitingFlip) FLIP_POLL_MS else POLL_MS)
        }
    }

    private fun PrintRecord.merge(job: Job): PrintRecord {
        val state = JobStateMachine.merge(jobState, job.state)
        if (state != job.state) return this
        // A stale poll can repeat "processing" after we already saw "awaiting_flip"; trust progress.
        if (jobState == JobState.AWAITING_FLIP && state == JobState.PROCESSING && job.sheetsCompleted < sheetsCompleted) return this
        return copy(
            jobState = state,
            sheetsCompleted = job.sheetsCompleted,
            sheetsTotal = job.sheetsTotal,
            message = job.message,
            phase = when (state) {
                JobState.CANCELED -> PrintPhase.CANCELED
                JobState.FAILED -> PrintPhase.FAILED
                else -> phase
            },
        )
    }

    private suspend fun update(localId: String, onUpdate: (PrintRecord) -> Unit, transform: (PrintRecord) -> PrintRecord): PrintRecord? {
        records.update(localId, transform)
        val record = records.get(localId) ?: return null
        onUpdate(record)
        return record
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
        const val POLL_MS = 1_000L
        const val FLIP_POLL_MS = 2_000L
    }
}
