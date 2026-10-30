package xyz.aezdek.hush.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.aezdek.hush.protocol.WsFrame
import xyz.aezdek.hush.protocol.WsType
import kotlin.math.min
import kotlin.random.Random

/**
 * The one WebSocket per device. The token travels as a subprotocol, as on the web, so it never appears in a URL or a
 * log line. Reconnects with jittered backoff; pings every 25 s keep Cloudflare's idle timer happy.
 */
class Realtime(private val api: Api, private val state: MutableStateFlow<AppState>, private val scope: CoroutineScope) {
    private val _frames = MutableSharedFlow<WsFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<WsFrame> = _frames

    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var open = false
    @Volatile
    private var wanted = false
    private var retry = 0
    private var reconnect: Job? = null
    private var ping: Job? = null

    val isOpen: Boolean get() = open

    fun connect() {
        wanted = true
        if (socket == null) start()
    }

    fun close() {
        wanted = false
        reconnect?.cancel()
        ping?.cancel()
        socket?.close(1000, null)
        socket = null
        open = false
        state.update { it.copy(connection = Connection.OFFLINE) }
    }

    /** Reconnect now (network came back, app came to the foreground). */
    fun nudge() {
        if (!wanted || open) return
        retry = 0
        reconnect?.cancel()
        start()
    }

    fun send(frame: WsFrame): Boolean {
        val ws = socket ?: return false
        if (!open) return false
        return ws.send(api.json.encodeToString(WsFrame.serializer(), frame))
    }

    private fun start() {
        val token = api.token ?: return
        if (!wanted) return
        socket?.cancel()
        state.update { it.copy(connection = Connection.CONNECTING) }
        val url = api.origin.replaceFirst("http", "ws") + "/api/v1/ws"
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "hush.v1, auth.$token").build()
        socket = api.http.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            open = true
            retry = 0
            state.update { it.copy(connection = Connection.ONLINE) }
            ping?.cancel()
            ping = scope.launch {
                while (isActive) {
                    delay(25_000)
                    send(WsFrame(WsType.PING))
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { api.json.decodeFromString(WsFrame.serializer(), text) }.getOrNull() ?: return
            _frames.tryEmit(frame)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
    }

    private fun dropped(webSocket: WebSocket) {
        if (webSocket !== socket) return
        open = false
        socket = null
        ping?.cancel()
        state.update { it.copy(connection = if (wanted) Connection.CONNECTING else Connection.OFFLINE) }
        if (!wanted) return
        val delayMs = (min(30_000.0, 1000.0 * (1 shl min(retry++, 5))) * (0.75 + Random.nextDouble() / 2)).toLong()
        reconnect?.cancel()
        reconnect = scope.launch {
            delay(delayMs)
            start()
        }
    }
}
