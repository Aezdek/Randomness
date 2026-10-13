package xyz.aezdek.aria.watch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import xyz.aezdek.aria.core.Updater

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(DOWNLOADS_CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        Downloads.schedulePeriodicSync(this)
        PhoneLink.importPending(this) // songs the phone sent while the app wasn't running
        Updater.schedule(this, "aria-watch.apk")
    }

    companion object {
        const val DOWNLOADS_CHANNEL = "downloads"
    }
}
