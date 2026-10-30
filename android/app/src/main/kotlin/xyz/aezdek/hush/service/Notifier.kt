package xyz.aezdek.hush.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import xyz.aezdek.hush.MainActivity
import xyz.aezdek.hush.R

/**
 * Notification channels and builders. Everything shown is decrypted on this phone; the server's WebSocket nudge says
 * only "something changed", so nothing readable ever passes through Google or Samsung push services.
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val channels = listOf(
            NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Direct messages and reactions"
            },
            NotificationChannel(ACTIVITY, "Activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Likes, comments, mentions and follow requests"
            },
            NotificationChannel(CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming calls"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            },
            NotificationChannel(ONGOING_CALL, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you're in a call"
                setSound(null, null)
            },
            NotificationChannel(CONNECTION, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Hush connected so calls can ring. You can hide this channel."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannels(channels)
    }

    fun openIntent(target: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun connection(): Notification =
        NotificationCompat.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hush is connected")
            .setContentText("So calls and messages reach you right away")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent("readiness", 1))
            .build()

    /** One conversation-style notification per chat, with every unread message in it. */
    fun message(threadId: String, title: String, lines: List<Pair<String, String>>, isGroup: Boolean) {
        if (!canPost()) return
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(if (isGroup) title else null).setGroupConversation(isGroup)
        val now = System.currentTimeMillis()
        lines.takeLast(8).forEachIndexed { i, (from, text) ->
            style.addMessage(text, now - (lines.size - i), Person.Builder().setName(from).build())
        }
        val notification = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent("chat:$threadId", threadId.hashCode()))
            .build()
        post(threadId.hashCode(), notification)
    }

    fun activity(id: Int, title: String, text: String, target: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openIntent(target, id))
            .build()
        post(id, notification)
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun cancelChat(threadId: String) = cancel(threadId.hashCode())

    fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications were turned off between the check and the post.
        }
    }

    fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 14+: a sideloaded app needs this granted by hand for calls to ring over the lock screen. */
    fun canUseFullScreen(): Boolean = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()

    companion object {
        const val MESSAGES = "messages"
        const val ACTIVITY = "activity"
        const val CALLS = "calls"
        const val ONGOING_CALL = "ongoing_call"
        const val CONNECTION = "connection"
        const val ID_CONNECTION = 1
        const val ID_CALL = 2
        const val ID_ONGOING_CALL = 3
    }
}
