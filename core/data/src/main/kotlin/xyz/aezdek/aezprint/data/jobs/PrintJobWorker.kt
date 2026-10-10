package xyz.aezdek.aezprint.data.jobs

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import xyz.aezdek.aezprint.domain.repository.PrintServerException

@HiltWorker
class PrintJobWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: PrintJobRunner,
    private val records: PrintRecordRepository,
    private val notifier: JobNotifier,
) : CoroutineWorker(context, params) {

    private val localId: String get() = requireNotNull(inputData.getString(KEY_LOCAL_ID))

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.foregroundInfo(records.get(localId) ?: PrintRecord(localId, "", "Document", PrintOptions(), 0))

    override suspend fun doWork(): Result {
        val record = records.get(localId) ?: return Result.failure()
        if (!record.isActive) return Result.success()
        notifier.ensureChannels()
        runCatching { setForeground(notifier.foregroundInfo(record)) }
        return try {
            runner.run(localId, notifier::update)
            Result.success()
        } catch (e: PrintServerException.Offline) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                runner.fail(localId, "Couldn't reach the print server", notifier::update)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_LOCAL_ID = "localId"
        private const val MAX_ATTEMPTS = 12
    }
}
