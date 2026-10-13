package xyz.aezdek.aria.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Talks to aria_proxy.py: search, song info, Saved songs, streams and downloads. */
class AriaServer(baseUrl: String, private val key: String) {

    val base: HttpUrl? = normalize(baseUrl)?.toHttpUrlOrNull()
    val configured get() = base != null

    fun url(path: String, vararg query: Pair<String, String>): HttpUrl? {
        val b = base ?: return null
        val builder = b.newBuilder().addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        if (key.isNotEmpty()) builder.addQueryParameter("key", key)
        return builder.build()
    }

    fun streamUrl(id: String) = url("stream/$id.m4a")?.toString()
    fun artworkUrl(track: Track, large: Boolean = true): String? =
        track.artwork?.let { if (large) it else it.replace("600x600bb", "200x200bb") }
            ?: url("thumb/${track.id}.jpg", "size" to if (large) "large" else "small")?.toString()

    private suspend fun getJson(url: HttpUrl?, timeoutSec: Long = 30): JSONObject = withContext(Dispatchers.IO) {
        url ?: throw IOException("Set the Aria server first")
        val client = http.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { res -> parse(res) }
    }

    private fun parse(res: Response): JSONObject {
        val body = res.body?.string().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (!res.isSuccessful) throw IOException(json?.optString("error")?.ifBlank { null } ?: "Server answered ${res.code}")
        return json ?: throw IOException("The server sent something Aria doesn't understand")
    }

    suspend fun ping(): JSONObject = getJson(url("api/ping"), 8)

    suspend fun search(query: String, limit: Int = 25): List<Track> =
        Track.listFromJson(getJson(url("api/search", "q" to query, "limit" to "$limit")).optJSONArray("results"))

    /** Clean title, artist, album, artwork and skip segments, from /api/track. */
    suspend fun trackInfo(track: Track): Track {
        val info = getJson(url("api/track/${track.id}"), 60)
        // The server serves the album art it found at its own thumbnail address.
        val artwork = if (info.optBoolean("artwork")) url("thumb/${track.id}.jpg", "size" to "large")?.toString() else track.artwork
        val merged = Track.fromJson(info.put("id", track.id)) ?: return track
        return merged.copy(
            duration = if (merged.duration > 0) merged.duration else track.duration,
            artwork = artwork,
            album = merged.album.ifBlank { track.album },
        )
    }

    /** {version, settings, saved, savedUpdated} */
    suspend fun config(): JSONObject = getJson(url("api/config"), 10)

    /**
     * Downloads the song's audio to [dest] in 1 MB ranges, so a dropped connection resumes
     * where it stopped and YouTube doesn't throttle one huge request.
     */
    suspend fun download(id: String, dest: File, progress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val url = url("stream/$id.m4a") ?: throw IOException("Set the Aria server first")
        val part = File(dest.path + ".part")
        var total = -1L
        RandomAccessFile(part, "rw").use { out ->
            var pos = out.length()
            out.seek(pos)
            var attempts = 0
            while (total < 0 || pos < total) {
                val end = pos + CHUNK - 1
                val req = Request.Builder().url(url).header("Range", "bytes=$pos-$end").build()
                try {
                    http.newCall(req).execute().use { res ->
                        if (res.code == 416) { total = pos; return@use }
                        if (!res.isSuccessful) parse(res) // throws with the server's message
                        total = res.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: res.body?.contentLength()?.takeIf { res.code == 200 } ?: -1
                        res.body!!.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                pos += n
                            }
                        }
                        if (res.code == 200) total = pos // the server sent everything at once
                        attempts = 0
                        progress(pos, total)
                    }
                } catch (e: IOException) {
                    if (++attempts >= 4) throw e
                    delay(1000L * attempts)
                }
            }
        }
        if (!part.renameTo(dest)) throw IOException("Couldn't save ${dest.name}")
    }

    /** Downloads any small file (artwork) to [dest]. */
    suspend fun fetch(url: String, dest: File) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            dest.outputStream().use { res.body!!.byteStream().copyTo(it) }
        }
    }

    companion object {
        private const val CHUNK = 1L shl 20
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun normalize(input: String): String? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if ("://" !in s) s = "http://$s"
            return s
        }
    }
}
