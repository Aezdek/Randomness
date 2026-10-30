package xyz.aezdek.aezgram

import android.app.Application
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import xyz.aezdek.aezgram.calls.CallManager
import xyz.aezdek.aezgram.client.AezClient
import xyz.aezdek.aezgram.client.ApiException
import xyz.aezdek.aezgram.client.UserFacingException
import xyz.aezdek.aezgram.data.KeystoreSecrets
import xyz.aezdek.aezgram.data.Prefs
import xyz.aezdek.aezgram.data.SqliteTables
import xyz.aezdek.aezgram.media.MediaCache
import xyz.aezdek.aezgram.service.ConnectionService
import xyz.aezdek.aezgram.service.Notifier
import xyz.aezdek.aezgram.protocol.DeviceInfo
import xyz.aezdek.aezgram.protocol.WsType
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A short message for the snackbar-style toast at the bottom of the screen. */
data class Toast(val text: String, val error: Boolean = false)

/** The process-wide objects. Small enough that a service locator beats a DI framework. */
object Graph {
    lateinit var app: AezGramApp
        private set
    lateinit var client: AezClient
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var media: MediaCache
        private set
    lateinit var calls: CallManager
        private set
    lateinit var notifier: Notifier
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once the stored session (if any) has been restored, so the UI never flashes the sign-in screen. */
    val booted = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val _toasts = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val toasts: SharedFlow<Toast> = _toasts

    fun toast(text: String, error: Boolean = false) {
        _toasts.tryEmit(Toast(text, error))
    }

    /** Runs [block], turning failures into a toast; returns null on failure. */
    suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        toast(messageOf(e), error = true)
        null
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { attempt(block) }
    }

    fun messageOf(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Something went wrong"
        is UserFacingException -> e.message ?: "Something went wrong"
        is IOException -> "Can't reach AezGram. Check your connection."
        else -> e.message ?: "Something went wrong"
    }

    fun init(app: AezGramApp) {
        this.app = app
        prefs = Prefs(app)
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
        client = AezClient(
            BuildConfig.SERVER_URL,
            http,
            SqliteTables(app),
            KeystoreSecrets(app),
            DeviceInfo("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", "android"),
            scope,
        )
        media = MediaCache(client)
        notifier = Notifier(app)
        calls = CallManager(app, client)
        client.onSignedOut = { ConnectionService.stop(app) }
        // Call signals and test rings, whether or not the background service is running.
        scope.launch {
            client.realtime.frames.collect { frame ->
                when (frame.t) {
                    WsType.SIGNAL -> calls.onSignal(frame)
                    WsType.RING_TEST -> calls.testRing()
                }
            }
        }
    }
}

class AezGramApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Graph.notifier.createChannels()
        Graph.scope.launch {
            val signedIn = runCatching { Graph.client.restore() }.getOrDefault(false)
            Graph.booted.value = true
            if (signedIn) ConnectionService.start(this@AezGramApp)
        }
    }
}
