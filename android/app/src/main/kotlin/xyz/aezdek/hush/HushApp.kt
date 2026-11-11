package xyz.aezdek.hush

import android.app.Application
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import xyz.aezdek.hush.calls.CallManager
import xyz.aezdek.hush.client.AezClient
import xyz.aezdek.hush.client.ApiException
import xyz.aezdek.hush.client.UserFacingException
import xyz.aezdek.hush.data.KeystoreSecrets
import xyz.aezdek.hush.data.Prefs
import xyz.aezdek.hush.data.SqliteTables
import xyz.aezdek.hush.media.MediaCache
import xyz.aezdek.hush.service.ConnectionService
import xyz.aezdek.hush.service.Notifier
import xyz.aezdek.hush.update.AutoUpdater
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.WsType
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A short message for the snackbar-style toast at the bottom of the screen. */
data class Toast(val text: String, val error: Boolean = false)

/** The process-wide objects. Small enough that a service locator beats a DI framework. */
object Graph {
    lateinit var app: HushApp
        private set
    lateinit var client: AezClient
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var media: MediaCache
        private set
    /** Shared HTTP client: the API, media and the in-app update download. */
    lateinit var http: OkHttpClient
        private set
    lateinit var calls: CallManager
        private set
    lateinit var notifier: Notifier
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashLog.handler)

    /** True once the stored session (if any) has been restored, so the UI never flashes the sign-in screen. */
    val booted = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Whether any Hush screen is on show (updates wait until it isn't, so they never close the app under you). */
    val visible = kotlinx.coroutines.flow.MutableStateFlow(false)

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
        is IOException -> "Can't reach Hush. Check your connection."
        else -> e.message ?: "Something went wrong"
    }

    /** Screenshot tests: what the screens read, without the Keystore, WebRTC or the background service. */
    @androidx.annotation.VisibleForTesting
    internal fun initForTest(context: android.content.Context, client: AezClient) {
        prefs = Prefs(context)
        http = OkHttpClient()
        this.client = client
        media = MediaCache(client)
        notifier = Notifier(context)
        booted.value = true
    }

    /** This app's Hush server: the one chosen on this phone, else the one the build was made for; blank if neither. */
    val serverUrl: String get() = (prefs.serverUrl ?: BuildConfig.SERVER_URL).trim().trimEnd('/')

    /** Whether the server's address was typed in here (it can then be changed), not built in. */
    val serverIsChosen: Boolean get() = BuildConfig.SERVER_URL.isBlank()

    /**
     * Points the app at another server ("" to ask again) and starts it afresh: everything it holds, from the
     * connection to the local cache, was set up around the old address.
     */
    fun useServer(url: String) {
        prefs.serverUrl = url
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
            app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        Runtime.getRuntime().exit(0)
    }

    fun init(app: HushApp) {
        this.app = app
        prefs = Prefs(app)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
        client = AezClient(
            serverUrl,
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
                // One bad frame must not stop the calls that come after it.
                runCatching {
                    when (frame.t) {
                        WsType.SIGNAL -> calls.onSignal(frame)
                        WsType.RING_TEST -> calls.testRing()
                    }
                }.onFailure { android.util.Log.w("Hush", "Call signal failed", it) }
            }
        }
    }
}

class HushApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Graph.init(this)
        Graph.notifier.createChannels()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    Graph.visible.value = true
                    AutoUpdater.checkSoon(this)
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> Graph.visible.value = false
                else -> Unit
            }
        })
        Graph.scope.launch {
            val signedIn = runCatching { Graph.client.restore() }.getOrDefault(false)
            Graph.booted.value = true
            if (signedIn) ConnectionService.start(this@HushApp)
            AutoUpdater.checkSoon(this@HushApp)
        }
    }
}
