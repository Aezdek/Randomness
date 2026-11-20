package xyz.aezdek.daybook.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.data.SqliteLocalStore
import java.util.concurrent.TimeUnit

class DaybookApplication : Application() {

    /** One per process, opened before any screen exists. */
    lateinit var daybook: Daybook
        private set

    override fun onCreate() {
        super.onCreate()
        daybook = runBlocking {
            Daybook.open(
                store = SqliteLocalStore.open(
                    driver = AndroidSQLiteDriver(),
                    path = getDatabasePath("daybook.db").also { it.parentFile?.mkdirs() }.absolutePath,
                    dispatcher = Dispatchers.IO.limitedParallelism(1),
                ),
                http = HttpClient(OkHttp),
                configStore = AndroidConfigStore(this@DaybookApplication),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
        daybook.start()

        // Long-poll only while the app is on screen; when it goes to the background, make sure anything
        // unsent still reaches the server, even if the process is then killed.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = daybook.sync.setForeground(true)

            override fun onStop(owner: LifecycleOwner) {
                daybook.sync.setForeground(false)
                daybook.scope.launch {
                    if (daybook.store.pendingCount() > 0 && daybook.sync.config.value.isConfigured) {
                        SyncWorker.enqueue(this@DaybookApplication)
                    }
                }
            }
        })
    }
}

/** Pushes unsent edits once there is a network, retrying with backoff. */
class SyncWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as DaybookApplication
        return if (app.daybook.sync.syncNow()) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(context: android.content.Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("daybook-sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
