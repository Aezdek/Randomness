package xyz.aezdek.aria.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhonePrefs.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TRANSFERS_CHANNEL, "Sending to watch", NotificationManager.IMPORTANCE_LOW)
        )
        YtDlp.init(this)
    }

    companion object {
        const val TRANSFERS_CHANNEL = "transfers"
    }
}
