package xyz.aezdek.aria.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import xyz.aezdek.aria.core.Updater

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhonePrefs.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TRANSFERS_CHANNEL, "Sending to watch", NotificationManager.IMPORTANCE_LOW)
        )
        YtDlp.init(this)
        Updater.schedule(this, "aria-phone.apk")
    }

    companion object {
        const val TRANSFERS_CHANNEL = "transfers"
    }
}
