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
import xyz.aezdek.hush.protocol.NotifyPreview

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
            NotificationChannel(UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A new version of Hush from your server"
                setShowBadge(false)
            },
            NotificationChannel(SECURITY, "Security alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New sign-ins to your account, and anything that looks wrong with your server"
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

    /**
     * One conversation-style notification per chat, with every unread message in it. [preview] is the person's choice
     * (Settings → Notifications): the messages themselves, only who sent them, or only that something came. Whatever
     * it is, the lock screen shows just "New message" until the phone is unlocked.
     */
    fun message(threadId: String, title: String, lines: List<Pair<String, String>>, isGroup: Boolean, preview: String = NotifyPreview.FULL) {
        if (!canPost()) return
        val builder = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent("chat:$threadId", threadId.hashCode()))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden(MESSAGES, if (lines.size > 1) "${lines.size} new messages" else "New message", "chat:$threadId", threadId.hashCode()))
        when (preview) {
            NotifyPreview.NONE -> builder.setContentTitle("Hush").setContentText(if (lines.size > 1) "${lines.size} new messages" else "New message")
            else -> {
                val me = Person.Builder().setName("You").build()
                val style = NotificationCompat.MessagingStyle(me).setConversationTitle(if (isGroup) title else null).setGroupConversation(isGroup)
                val now = System.currentTimeMillis()
                lines.takeLast(8).forEachIndexed { i, (from, text) ->
                    style.addMessage(if (preview == NotifyPreview.NAME) "New message" else text, now - (lines.size - i), Person.Builder().setName(from).build())
                }
                builder.setStyle(style)
            }
        }
        post(threadId.hashCode(), builder.build())
    }

    fun activity(id: Int, title: String, text: String, target: String, preview: String = NotifyPreview.FULL) {
        if (!canPost()) return
        val shownTitle = if (preview == NotifyPreview.NONE) "Hush" else title
        val shownText = if (preview == NotifyPreview.NONE) "New activity" else text
        val notification = NotificationCompat.Builder(context, ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownTitle)
            .setContentText(shownText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shownText))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openIntent(target, id))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden(ACTIVITY, "New activity", target, id))
            .build()
        post(id, notification)
    }

    /** What the lock screen shows instead of a notification's words. */
    private fun hidden(channel: String, text: String, target: String, id: Int): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hush")
            .setContentText(text)
            .setContentIntent(openIntent(target, id))
            .build()

    /**
     * Another device just signed in to this account. "That was me" only dismisses it; "Not me" signs that device out
     * at once (see [SecurityActionReceiver]) and opens Hush to change the password.
     */
    fun signIn(sessionId: String, device: String, newest: Long) {
        val action = { label: String, what: String, code: Int ->
            val intent = Intent(context, SecurityActionReceiver::class.java).setAction(what)
                .putExtra(SecurityActionReceiver.EXTRA_SESSION, sessionId).putExtra(SecurityActionReceiver.EXTRA_AT, newest)
            NotificationCompat.Action(0, label, PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        val text = "$device signed in to your account. If this wasn't you, sign it out now and change your password."
        val notification = NotificationCompat.Builder(context, SECURITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New sign-in. Was this you?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent("settings:security", ID_SECURITY_SIGN_IN))
            .addAction(action("Not me", SecurityActionReceiver.NOT_ME, 71))
            .addAction(action("That was me", SecurityActionReceiver.WAS_ME, 72))
            .build()
        post(ID_SECURITY_SIGN_IN, notification)
    }

    /** Something the person should know about their account or their server, whatever else is muted. */
    fun security(id: Int, title: String, text: String, target: String) {
        val notification = NotificationCompat.Builder(context, SECURITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(target, id))
            .build()
        post(id, notification)
    }

    /** Android wants a tap to install this update (the first one, or before Android 12): its prompt, one tap away. */
    fun updateReady(version: String, confirm: Intent) {
        val tap = PendingIntent.getActivity(context, ID_UPDATE, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(ID_UPDATE, update("Hush $version is ready", "Tap to install the update.", tap))
    }

    /** Android hasn't let Hush install apps yet: one switch to turn on, then updates go through by themselves. */
    fun allowUpdates(version: String) {
        val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
        val tap = PendingIntent.getActivity(context, ID_UPDATE, settings, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(ID_UPDATE, update("Hush $version is ready", "Tap, then allow Hush to install updates.", tap))
    }

    fun updated(version: String) {
        post(ID_UPDATE, update("Hush updated to $version", "Tap to open Hush.", openIntent("home", ID_UPDATE)))
    }

    private fun update(title: String, text: String, tap: PendingIntent): Notification =
        NotificationCompat.Builder(context, UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

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
        const val UPDATES = "updates"
        const val SECURITY = "security"
        const val ID_CONNECTION = 1
        const val ID_CALL = 2
        const val ID_ONGOING_CALL = 3
        const val ID_UPDATE = 4
        const val ID_SECURITY_UPDATE = 5
        const val ID_SECURITY_WEB = 6
        const val ID_SECURITY_SIGN_IN = 7
        const val ID_ADMIN_ALERT = 8
    }
}
