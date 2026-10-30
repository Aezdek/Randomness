package xyz.aezdek.hush.calls

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.R
import xyz.aezdek.hush.service.Notifier

/** The ringing notification: CallStyle with a full-screen intent, so it takes over the lock screen like a phone call. */
object CallNotifications {
    fun showIncoming(context: Context, username: String, video: Boolean) {
        val person = Person.Builder().setName("@$username").setImportant(true).build()
        val screen = PendingIntent.getActivity(
            context, 10, Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answer = PendingIntent.getActivity(
            context, 11, Intent(context, CallActivity::class.java).setAction(CallActivity.ACTION_ANSWER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context, 12, Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Notifier.CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("@$username")
            .setContentText(if (video) "Incoming video call" else "Incoming audio call")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video))
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
        Graph.notifier.post(Notifier.ID_CALL, notification)
    }

    fun cancelIncoming(context: Context) = Graph.notifier.cancel(Notifier.ID_CALL)
}

/** Keeps camera and microphone available while a call is on, even with the screen off or another app in front. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hangup = PendingIntent.getBroadcast(
            this, 13, Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 14, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = Graph.calls.state.value
        val name = state.peers.keys.firstOrNull()?.let { Graph.client.state.value.users[it]?.username } ?: "Hush"
        val notification = NotificationCompat.Builder(this, Notifier.ONGOING_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(Person.Builder().setName(if (state.live != null) "Live" else "@$name").build(), hangup))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        val types = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (state.video || state.live != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else 0
        try {
            ServiceCompat.startForeground(this, Notifier.ID_ONGOING_CALL, notification, types)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DECLINE -> Graph.calls.decline()
            HANGUP -> Graph.calls.hangup()
        }
    }

    companion object {
        const val DECLINE = "xyz.aezdek.hush.DECLINE"
        const val HANGUP = "xyz.aezdek.hush.HANGUP"
    }
}
