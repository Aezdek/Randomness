package xyz.aezdek.hush.server

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the newest published release is, for the admin panel's "update available". Checked in the background at most
 * hourly and never on a request's critical path: the first look may say "unknown" while the check runs.
 */
private const val CHECK_EVERY_MS = 60L * 60 * 1000

class Releases(private val url: String?, private val clock: Clock) {
    data class Latest(val version: String, val commit: String?)

    private val log = LoggerFactory.getLogger(Releases::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val checking = AtomicBoolean(false)

    @Volatile
    private var latest: Latest? = null

    @Volatile
    private var checkedAt = 0L

    fun latest(): Latest? {
        if (url != null && clock.now() - checkedAt > CHECK_EVERY_MS && checking.compareAndSet(false, true)) {
            Thread.ofVirtual().start {
                try {
                    latest = fetch(url) ?: latest
                } catch (e: Exception) {
                    log.info("Couldn't check for a newer release: {}", e.message)
                } finally {
                    checkedAt = clock.now()
                    checking.set(false)
                }
            }
        }
        return latest
    }

    private fun fetch(url: String): Latest? {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val values = response.body().lines().mapNotNull { line ->
            line.split("=", limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() }
        }.toMap()
        val version = values["HUSH_VERSION"]?.takeIf { it.matches(Regex("[0-9A-Za-z.+-]{1,40}")) } ?: return null
        return Latest(version, values["HUSH_COMMIT"]?.takeIf { it.matches(Regex("[0-9a-f]{40}")) })
    }
}
