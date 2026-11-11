package xyz.aezdek.hush.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import xyz.aezdek.hush.BuildConfig
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.calls.CallStatus
import xyz.aezdek.hush.client.UserFacingException
import xyz.aezdek.hush.protocol.AppVersion
import xyz.aezdek.hush.protocol.Wire
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the app up to date by itself, from your own server: it reads `/version` there, and when that
 * build is newer than this one it downloads the APK, checks it against the SHA-256 the server lists, and installs it.
 *
 * It never interrupts: not during a call, and not while you're using the app (it asks, or waits until Hush is in the
 * background). From Android 12, once Hush has installed one of its own updates, later ones install with no tap at
 * all; before that (the first time, since a browser installed the app) Android shows its usual "Update" prompt.
 * Android itself refuses any APK not signed with the key this app was installed with.
 */
object AutoUpdater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Downloading(val version: AppVersion, val progress: Float) : State
        /** Downloaded and verified; installs as soon as nothing would be interrupted (or right away on "Update now"). */
        data class Ready(val version: AppVersion) : State
        data class Installing(val version: AppVersion) : State
        data class Failed(val message: String) : State
    }

    private const val TAG = "HushUpdate"
    /** Automatic checks at most this often; opening Settings → Updates or tapping "Check now" always checks. */
    private const val MIN_INTERVAL = 30L * 60 * 1000

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val mutex = Mutex()
    private var waiting: Job? = null
    private var ready: Pair<AppVersion, File>? = null

    /** True when [remote] is a later build than this app: the build number decides, and the version name breaks ties. */
    fun isNewer(remote: AppVersion, code: Int = BuildConfig.VERSION_CODE, name: String = BuildConfig.VERSION_NAME): Boolean =
        remote.versionCode > code || (remote.versionCode == code && compareVersions(remote.versionName, name) > 0)

    /** Compares dotted version names number by number: "1.0.10" is after "1.0.9". */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.', '-', '+', ' ').mapNotNull { it.toIntOrNull() }
        val y = b.split('.', '-', '+', ' ').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** A check in the background, throttled unless [force]d. */
    fun checkSoon(context: Context, force: Boolean = false) {
        Graph.scope.launch { runCatching { check(context.applicationContext, force) } }
    }

    suspend fun check(context: Context, force: Boolean = false) {
        if (!force && !Graph.prefs.autoUpdate) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - Graph.prefs.lastUpdateCheck < MIN_INTERVAL) return
            if (_state.value is State.Downloading || _state.value is State.Installing) return
            Graph.prefs.lastUpdateCheck = now
            if (_state.value !is State.Ready) _state.value = State.Checking
            try {
                val latest = fetchLatest()
                if (latest == null || !isNewer(latest)) {
                    _state.value = State.UpToDate
                    clearDownloads(context)
                    return
                }
                val current = ready
                val apk = if (current != null && current.first == latest && current.second.isFile) current.second else download(context, latest)
                ready = latest to apk
                _state.value = State.Ready(latest)
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                _state.value = State.Failed(Graph.messageOf(e))
                return
            }
        }
        installWhenIdle(context)
    }

    /** "Update now": install the downloaded update immediately (still never during a call). */
    fun installNow(context: Context) {
        val (version, apk) = ready ?: return
        waiting?.cancel()
        if (Graph.calls.state.value.status != CallStatus.IDLE) {
            Graph.toast("The update will install after your call")
            installWhenIdle(context)
            return
        }
        install(context.applicationContext, version, apk)
    }

    /** Waits until no call is going and Hush is in the background, then installs. "Update now" skips the wait. */
    private fun installWhenIdle(context: Context) {
        val (version, apk) = ready ?: return
        waiting?.cancel()
        waiting = Graph.scope.launch {
            Graph.calls.state.first { it.status == CallStatus.IDLE }
            Graph.visible.first { !it }
            if (ready?.first == version) install(context.applicationContext, version, apk)
        }
    }

    private suspend fun fetchLatest(): AppVersion? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(Graph.serverUrl + "/version").header("Cache-Control", "no-cache").build()
        Graph.http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null // this server has no Android build yet
                !response.isSuccessful -> throw UserFacingException("Couldn't check for updates (${response.code}).")
                else -> Wire.json.decodeFromString(AppVersion.serializer(), response.body.string())
            }
        }
    }

    private suspend fun download(context: Context, version: AppVersion): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "hush-${version.versionCode}.apk")
        _state.value = State.Downloading(version, 0f)
        // The build number in the URL keeps a cache between here and the server from handing back an older APK.
        val url = Graph.serverUrl + version.url + (if ('?' in version.url) "&" else "?") + "v=${version.versionCode}"
        Graph.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw UserFacingException("The server couldn't send the update (${response.code}).")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        _state.value = State.Downloading(version, (done.toFloat() / version.size).coerceIn(0f, 1f))
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(version.sha256, ignoreCase = true)) {
                file.delete()
                throw UserFacingException("The download didn't match what the server announced. It will try again later.")
            }
        }
        file
    }

    private fun install(context: Context, version: AppVersion, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether Hush may install apps; after that updates go through on their own.
            _state.value = State.Ready(version)
            if (Graph.visible.value) {
                Graph.toast("Allow Hush to install its updates, then tap Update")
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                Graph.notifier.allowUpdates(version.versionName)
            }
            return
        }
        _state.value = State.Installing(version)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                // Android 12+: no prompt for an app updating itself once it installed the current build.
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("hush.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS).putExtra(EXTRA_VERSION, version.versionName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Install failed", e)
            _state.value = State.Failed("Couldn't install the update: ${e.message ?: "unknown error"}")
        }
    }

    internal fun onInstallStatus(context: Context, intent: Intent) {
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ready?.let { _state.value = State.Ready(it.first) }
                // Android wants a tap this time: show its prompt now if Hush is open, otherwise a notification for it.
                if (Graph.visible.value) runCatching { context.startActivity(confirm) }.onFailure { Graph.notifier.updateReady(version, confirm) }
                else Graph.notifier.updateReady(version, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts Hush on the new version; see onUpdated.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // Dismissed the prompt: keep the update ready, and ask again later.
                ready?.let { _state.value = State.Ready(it.first) }
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install status $status: $message")
                _state.value = State.Failed("The update didn't install${message?.let { ": $it" } ?: ""}.")
            }
        }
    }

    /** After Android replaced the app (any update): tidy up and say so. */
    fun onUpdated(context: Context) {
        clearDownloads(context)
        Graph.notifier.updated(BuildConfig.VERSION_NAME)
    }

    private fun clearDownloads(context: Context) {
        ready = null
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    const val ACTION_STATUS = "xyz.aezdek.hush.UPDATE_STATUS"
    const val EXTRA_VERSION = "version"
}

/** Where Android reports how an update install went. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AutoUpdater.ACTION_STATUS) AutoUpdater.onInstallStatus(context, intent)
    }
}
