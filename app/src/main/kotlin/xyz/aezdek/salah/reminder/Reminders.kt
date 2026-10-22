package xyz.aezdek.salah.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import xyz.aezdek.salah.MainActivity
import xyz.aezdek.salah.R
import xyz.aezdek.salah.core.Prayer
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.core.nextReminder
import xyz.aezdek.salah.core.unlogged
import java.time.LocalDate
import java.time.ZonedDateTime

/** The daily "log your prayers" notification. It only shows when something today is still unlogged. */
class Reminders(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Reminds you to log today's prayers"
        notifications.createNotificationChannel(channel)
    }

    /**
     * Sets the alarm for the next [minuteOfDay] (minutes after midnight), or cancels it when null. The alarm is
     * inexact, so no special permission is needed; it is allowed to fire while the phone is idle.
     */
    fun schedule(minuteOfDay: Int?) {
        val intent = alarmIntent()
        if (minuteOfDay == null) {
            alarms.cancel(intent)
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        val at = nextReminder(minuteOfDay, ZonedDateTime.now()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    fun notifyIfUnlogged(data: SalahData, today: LocalDate) {
        val unlogged = data.unlogged(today)
        if (unlogged.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (unlogged.size == Prayer.entries.size) {
            "Nothing logged yet today."
        } else {
            "Not logged yet: " + unlogged.joinToString { it.title }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_salah)
            .setContentTitle("Log today's prayers")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_REMIND = "xyz.aezdek.salah.action.REMIND"
        private const val CHANNEL = "reminder"
        private const val NOTIFICATION_ID = 1
    }
}
