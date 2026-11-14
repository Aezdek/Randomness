package xyz.aezdek.hush.update

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import xyz.aezdek.hush.BuildConfig
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.BuildCheck
import xyz.aezdek.hush.protocol.Health
import xyz.aezdek.hush.protocol.Wire
import xyz.aezdek.hush.service.Notifier

/**
 * Compares the web app your server hands out with the public record of the build it says it runs (BuildCheck).
 *
 * A browser runs whatever the server sends, so a server that was taken over could send a page that copies passwords.
 * A phone can't stop that, but it can notice: it fetches the page and its scripts the way a browser would and checks
 * every byte. It looks when the server's version changes and once a week after that, on Wi-Fi, or when asked.
 */
object WebAppCheck {
    /** How the last check went: [result] is 1 when the web app matches, -1 when it differs, 0 when it couldn't be told. */
    data class Last(val at: Long, val version: String, val result: Int, val text: String)

    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Files checked so far and in all, while a check runs. */
    val progress: StateFlow<Pair<Int, Int>?> = _progress

    private val _last = MutableStateFlow(read())
    val last: StateFlow<Last> = _last

    val available: Boolean get() = BuildConfig.BUILDS_URL.isNotBlank()

    private fun read() = Last(Graph.prefs.webCheckAt, Graph.prefs.webCheckVersion, Graph.prefs.webCheckResult, Graph.prefs.webCheckText)

    private fun save(last: Last) {
        Graph.prefs.webCheckAt = last.at
        Graph.prefs.webCheckVersion = last.version
        Graph.prefs.webCheckResult = last.result
        Graph.prefs.webCheckText = last.text
        _last.value = last
    }

    private suspend fun serverVersion(): String? = withContext(Dispatchers.IO) {
        runCatching {
            Graph.http.newCall(Request.Builder().url(Graph.serverUrl + "/healthz").build()).execute().use { response ->
                if (response.isSuccessful) Wire.json.decodeFromString(Health.serializer(), response.body.string()).version else null
            }
        }.getOrNull()
    }

    /** In the background: only when it is due, and not over a connection that is paid for by the megabyte. */
    suspend fun maybeRun(context: Context) {
        if (!available || !Graph.prefs.verifyUpdates || Graph.serverUrl.isBlank()) return
        val metered = runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)
        if (metered) return
        val version = serverVersion() ?: return
        val before = _last.value
        if (before.version == version && System.currentTimeMillis() - before.at < WEEK) return
        run(context, version)
    }

    /** Checks now. [known]: the server's version, if the caller already asked for it. */
    suspend fun run(context: Context, known: String? = null) {
        if (!available || Graph.serverUrl.isBlank()) return
        mutex.withLock {
            _progress.value = 0 to 0
            try {
                val version = known ?: serverVersion()
                val build = version?.let(BuildCheck::buildNumber)
                val now = System.currentTimeMillis()
                if (version == null || build == null) {
                    save(Last(now, version.orEmpty(), 0, if (version == null) "Your server couldn't be reached." else "Your server runs a build with no public record ($version)."))
                    return
                }
                val record = try {
                    BuildCheck.fetchRecord(Graph.http, BuildConfig.BUILDS_URL, build)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    save(Last(now, version, 0, "The public record couldn't be fetched. Check your connection and try again."))
                    return
                }
                val report = BuildCheck.verifyWeb(Graph.http, Graph.serverUrl, record) { done, total -> _progress.value = done to total }
                when (val verdict = report.verdict) {
                    BuildCheck.Verdict.Matches -> save(Last(now, version, 1, "It is the public build $version: all ${report.checked} files match."))
                    is BuildCheck.Verdict.Unknown -> save(Last(now, version, 0, verdict.why))
                    is BuildCheck.Verdict.Differs -> {
                        val files = report.changed.take(4).joinToString(", ")
                        save(Last(now, version, -1, "${verdict.what} Changed: $files. Don't sign in through a browser until whoever runs your server has looked into it."))
                        Graph.notifier.security(
                            Notifier.ID_SECURITY_WEB, "Your server's web app was changed",
                            "It is not the public build. Don't sign in through a browser until it has been looked into.", "settings:updates",
                        )
                    }
                }
            } finally {
                _progress.value = null
            }
        }
    }
}
