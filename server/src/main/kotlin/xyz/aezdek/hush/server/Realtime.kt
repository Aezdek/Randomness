package xyz.aezdek.hush.server

import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import xyz.aezdek.hush.protocol.WsFrame
import xyz.aezdek.hush.protocol.WsType
import xyz.aezdek.hush.protocol.Wire
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Live WebSocket connections, by user. */
class Presence {
    class Connection(val sessionId: String, val userId: String, val socket: DefaultWebSocketSession)

    private val byUser = ConcurrentHashMap<String, CopyOnWriteArraySet<Connection>>()

    /** False when [Connection.userId] already has [MAX_CONNECTIONS] sockets open (every device and tab is one). */
    fun register(connection: Connection): Boolean {
        val connections = byUser.computeIfAbsent(connection.userId) { CopyOnWriteArraySet() }
        if (connections.size >= MAX_CONNECTIONS) return false
        connections.add(connection)
        return true
    }

    fun unregister(connection: Connection) {
        byUser[connection.userId]?.remove(connection)
    }

    fun isOnline(userId: String) = byUser[userId]?.isNotEmpty() == true

    fun onlineUsers(): Set<String> = byUser.filterValues { it.isNotEmpty() }.keys

    fun connectedSessions(): Set<String> = byUser.values.flatMapTo(HashSet()) { set -> set.map { it.sessionId } }

    /** Sends to every connected device of [userId]; false if none is connected. */
    fun send(userId: String, frame: WsFrame): Boolean {
        val connections = byUser[userId].orEmpty()
        if (connections.isEmpty()) return false
        val text = Wire.json.encodeToString(WsFrame.serializer(), frame)
        connections.forEach { it.socket.outgoing.trySend(Frame.Text(text)) }
        return true
    }

    private companion object {
        const val MAX_CONNECTIONS = 16
    }

    fun disconnectSession(sessionId: String) {
        byUser.values.flatten().filter { it.sessionId == sessionId }.forEach { connection ->
            unregister(connection)
            connection.socket.launch { connection.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "signed out")) }
        }
    }
}

/** Applies committed [Effects]: sync nudges to connected devices, and content-free pushes to everyone else. */
class Realtime(
    private val db: Db,
    private val presence: Presence,
    private val push: WebPush,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Realtime::class.java)
    private val lastPush = ConcurrentHashMap<String, Long>()

    fun apply(fx: Effects) {
        for (userId in fx.syncUsers) presence.send(userId, WsFrame(WsType.SYNC))
        for (hint in fx.pushes.distinctBy { it.userId to it.type }) {
            if (presence.isOnline(hint.userId)) continue
            val now = clock.now()
            val last = lastPush[hint.userId] ?: 0
            if (now - last < 3_000) continue
            lastPush[hint.userId] = now
            pushTo(hint.userId, hint.type, hint.fromUserId, hint.ref, urgent = false)
        }
    }

    fun pushTo(userId: String, type: String, fromUserId: String?, ref: String?, urgent: Boolean) {
        scope.launch {
            runCatching {
                val from = fromUserId?.let { id -> db.tx { one("SELECT username FROM users WHERE id = ?", id) { it.str("username") } } }
                push.sendToUser(userId, PushMessage(type, from, fromUserId, ref), urgent)
            }.onFailure { log.warn("a push failed: {}", it.message) }
        }
    }
}
