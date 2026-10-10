package xyz.aezdek.aezprint.data.jobs

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.PrintPhase
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import xyz.aezdek.aezprint.domain.repository.PrintQueue
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import xyz.aezdek.aezprint.domain.repository.PrintServerException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerPrintQueue @Inject constructor(
    private val workManager: WorkManager,
    private val records: PrintRecordRepository,
    private val documents: DocumentRepository,
    private val runner: PrintJobRunner,
    private val notifier: JobNotifier,
) : PrintQueue {

    override suspend fun enqueue(document: LocalDocument, options: PrintOptions): String {
        val record = PrintRecord(
            localId = UUID.randomUUID().toString(),
            documentId = document.id,
            documentName = document.displayName,
            options = options,
            createdAt = System.currentTimeMillis(),
        )
        records.upsert(record)
        documents.touch(document.id)
        start(record.localId)
        return record.localId
    }

    override suspend fun reprint(localId: String): String {
        val previous = records.get(localId) ?: throw PrintServerException.NotFound("That print is no longer in history")
        val document = documents.get(previous.documentId)
            ?: throw PrintServerException.NotFound("${previous.documentName} is no longer on this phone")
        return enqueue(document, previous.options)
    }

    override suspend fun cancel(localId: String) {
        val record = records.get(localId) ?: return
        if (record.jobId == null) {
            workManager.cancelUniqueWork(workName(localId))
            records.update(localId) { it.copy(phase = PrintPhase.CANCELED, message = "Canceled before printing") }
            records.get(localId)?.let(notifier::update)
            return
        }
        deliver(localId, JobActionWorker.ACTION_CANCEL) { runner.cancelRemote(localId, notifier::update) }
    }

    override suspend fun continueAfterFlip(localId: String) {
        deliver(localId, JobActionWorker.ACTION_CONTINUE) { runner.continueRemote(localId, notifier::update) }
    }

    /** Tries right away for a snappy UI and falls back to WorkManager when the network is down. */
    private suspend fun deliver(localId: String, action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: PrintServerException.Offline) {
            workManager.enqueue(actionRequest(localId, action))
            records.update(localId) { it.copy(message = "Will send when you're back online") }
        }
    }

    private fun start(localId: String) {
        val request = OneTimeWorkRequestBuilder<PrintJobWorker>()
            .setInputData(workDataOf(PrintJobWorker.KEY_LOCAL_ID to localId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_PRINT)
            .build()
        workManager.enqueueUniqueWork(workName(localId), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG_PRINT = "print"

        fun workName(localId: String) = "print-$localId"

        fun actionRequest(localId: String, action: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<JobActionWorker>()
                .setInputData(workDataOf(JobActionWorker.KEY_LOCAL_ID to localId, JobActionWorker.KEY_ACTION to action))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
    }
}
