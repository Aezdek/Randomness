package xyz.aezdek.aezprint

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import xyz.aezdek.aezprint.data.jobs.JobNotifier
import xyz.aezdek.aezprint.lock.AppLock
import javax.inject.Inject

@HiltAndroidApp
class AezPrintApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: JobNotifier
    @Inject lateinit var appLock: AppLock

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        appLock.start()
    }
}
