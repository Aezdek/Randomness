package xyz.aezdek.hush.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.protocol.CommentPayload
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType

/**
 * Keeps the WebSocket open while the app is in the background (a `remoteMessaging` foreground service), so calls ring
 * and messages arrive without any third-party push service. Turns what the sync engine decrypts into notifications.
 */
class ConnectionService : Service() {
    private var scope: CoroutineScope? = null
    private var network: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = Graph.notifier.connection()
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_CONNECTION, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        this.scope = scope
        val client = Graph.client
        client.realtime.connect()
        scope.launch { watchForNotifications() }
        // Reconnect as soon as the network comes back instead of waiting out the backoff.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = client.realtime.nudge()
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }.onSuccess { network = callback }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Graph.client.realtime.nudge()
        return START_STICKY
    }

    override fun onDestroy() {
        network?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope?.cancel()
        super.onDestroy()
    }

    private suspend fun watchForNotifications() {
        val client = Graph.client
        var lastRequests = client.state.value.followRequests.toSet()
        client.index
            .map { it to client.state.value }
            .distinctUntilChanged { a, b -> a.first === b.first && a.second.followRequests == b.second.followRequests }
            .collect { (index, state) ->
                val me = state.me?.id ?: return@collect
                if (!state.initialSyncDone) return@collect
                val prefs = Graph.prefs
                val newest = state.entities.values.maxOfOrNull { it.dto.receivedAt } ?: 0L
                if (prefs.notifiedUpTo == 0L) {
                    // First run on this device: don't replay history as notifications.
                    prefs.notifiedUpTo = newest
                    lastRequests = state.followRequests.toSet()
                    return@collect
                }
                val since = prefs.notifiedUpTo
                val fresh = state.entities.values.filter { it.dto.receivedAt > since && it.dto.authorId != me }
                if (fresh.isNotEmpty()) {
                    notifyMessages(fresh, index, state, me)
                    notifyActivity(fresh, index, state, me)
                }
                prefs.notifiedUpTo = maxOf(since, newest)
                val requests = state.followRequests.toSet()
                if (index.settings.notifyFollows) {
                    for (id in requests - lastRequests) {
                        val name = state.users[id]?.username ?: continue
                        Graph.notifier.activity("req:$id".hashCode(), "Follow request", "@$name wants to follow you", "activity")
                    }
                }
                lastRequests = requests
            }
    }

    private fun foregroundOn(threadId: String?): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            (threadId == null || visibleThread == threadId)

    private fun notifyMessages(fresh: List<Entity>, index: Index, state: AppState, me: String) {
        val settings = index.settings
        if (!settings.notifyMessages) return
        // A theme or nickname change shows in the chat but doesn't buzz anyone's phone.
        fun notifies(e: Entity) = e.dto.kind == Kind.MESSAGE && e.payloadAs<MessagePayload>()?.type != MessageType.SYSTEM
        val byThread = fresh.filter(::notifies).groupBy { it.dto.threadId }
        for ((threadId, _) in byThread) {
            threadId ?: continue
            if (threadId in settings.mutedThreads || foregroundOn(threadId)) continue
            val thread = state.threads[threadId] ?: continue
            val mine = thread.members.firstOrNull { it.userId == me }
            val unread = index.messages[threadId].orEmpty().filter { it.dto.authorId != me && it.dto.receivedAt > (mine?.lastReadAt ?: 0) && notifies(it) }
            if (unread.isEmpty()) continue
            val lines = unread.map { entity ->
                index.nameIn(threadId, entity.dto.authorId, state.users) to preview(entity.payloadAs<MessagePayload>())
            }
            Graph.notifier.message(threadId, index.threadTitle(thread, state.users, me), lines, thread.isGroup)
        }
    }

    private fun notifyActivity(fresh: List<Entity>, index: Index, state: AppState, me: String) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val settings = index.settings
        for (entity in fresh) {
            val dto = entity.dto
            val who = "@" + (state.users[dto.authorId]?.username ?: "someone")
            val parent = dto.parentId?.let { state.entities[it] }
            when {
                dto.kind == Kind.LIKE && parent?.dto?.authorId == me && settings.notifyLikes && parent.dto.kind != Kind.MESSAGE ->
                    Graph.notifier.activity(dto.id.hashCode(), "New like", "$who liked your ${what(parent)}", "post:${rootOf(parent, state).id}")
                dto.kind == Kind.COMMENT && parent != null && rootOf(parent, state).dto.authorId == me && settings.notifyComments ->
                    Graph.notifier.activity(dto.id.hashCode(), "New comment", "$who: ${entity.payloadAs<CommentPayload>()?.text.orEmpty()}",
                        "post:${rootOf(parent, state).id}")
                dto.kind == Kind.ACTIVITY && settings.notifyComments ->
                    Graph.notifier.activity(dto.id.hashCode(), "You were mentioned", "$who mentioned you", "activity")
                dto.kind == Kind.ANSWER && settings.notifyStories ->
                    Graph.notifier.activity(dto.id.hashCode(), "New answer", "$who answered your question", "activity")
            }
        }
    }

    private fun what(parent: Entity) = when (parent.dto.kind) {
        Kind.COMMENT -> "comment"
        Kind.STORY -> "story"
        Kind.REEL -> "reel"
        else -> "post"
    }

    private fun rootOf(entity: Entity, state: AppState): Entity {
        var current = entity
        while (true) {
            val parent = current.dto.parentId?.let { state.entities[it] } ?: return current
            current = parent
        }
    }

    companion object {
        /** The chat on screen right now, so its messages don't also pop up as notifications. */
        @Volatile
        var visibleThread: String? = null

        fun preview(payload: MessagePayload?): String = when (payload?.type) {
            null -> "Sent a message"
            MessageType.TEXT -> payload.text.orEmpty()
            MessageType.MEDIA -> if (payload.media.any { it.mime.startsWith("video/") }) "Sent a video" else "Sent a photo"
            MessageType.VOICE -> "Sent a voice message"
            MessageType.SHARE -> "Shared a post"
            MessageType.STORY_REPLY -> "Replied to your story: ${payload.text.orEmpty()}"
            MessageType.STORY_REACTION -> "Reacted to your story ${payload.text.orEmpty()}"
            MessageType.CALL -> if (payload.call?.missed == true) "Missed call" else "Call"
            MessageType.HEART -> payload.text ?: "❤️"
            else -> payload.text ?: "Sent a message"
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background right now (no battery exemption); the app starts it when opened.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}

/** Reconnects after a reboot or an app update, so calls ring without opening the app first. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            Graph.scope.launch {
                try {
                    while (!Graph.booted.value) kotlinx.coroutines.delay(100)
                    if (Graph.client.signedIn) ConnectionService.start(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
