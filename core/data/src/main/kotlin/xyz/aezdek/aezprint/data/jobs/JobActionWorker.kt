package xyz.aezdek.aezprint.data.jobs

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import xyz.aezdek.aezprint.domain.repository.PrintServerException
import kotlin.coroutines.cancellation.CancellationException

/** Delivers a continue or cancel that couldn't be sent immediately, e.g. from a notification while offline. */
@HiltWorker
class JobActionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: PrintJobRunner,
    private val notifier: JobNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val localId = inputData.getString(KEY_LOCAL_ID) ?: return Result.failure()
        return try {
            when (inputData.getString(KEY_ACTION)) {
                ACTION_CONTINUE -> runner.continueRemote(localId, notifier::update)
                ACTION_CANCEL -> runner.cancelRemote(localId, notifier::update)
                else -> return Result.failure()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrintServerException.Offline) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (e: PrintServerException) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_LOCAL_ID = "localId"
        const val KEY_ACTION = "action"
        const val ACTION_CONTINUE = "continue"
        const val ACTION_CANCEL = "cancel"
        private const val MAX_ATTEMPTS = 20
    }
}
