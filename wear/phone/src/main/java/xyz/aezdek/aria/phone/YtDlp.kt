package xyz.aezdek.aria.phone

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.aezdek.aria.core.Metadata
import xyz.aezdek.aria.core.Track
import java.io.File
import java.io.IOException

/** yt-dlp running on the phone, through youtubedl-android's bundled Python. */
object YtDlp {
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** null while starting, then the yt-dlp version, or an error message. */
    val status = MutableStateFlow<String?>(null)
    @Volatile var ready = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            status.value = try {
                YoutubeDL.getInstance().init(appContext)
                ready = true
                "yt-dlp " + (YoutubeDL.getInstance().version(appContext) ?: "ready")
            } catch (e: Exception) {
                "yt-dlp failed to start: ${e.message}"
            }
        }
    }

    private fun requireReady() {
        if (!ready) throw IOException(status.value ?: "yt-dlp is still starting")
    }

    suspend fun search(query: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        requireReady()
        val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
            addOption("--flat-playlist")
            addOption("-J")
        }
        val out = YoutubeDL.getInstance().execute(request, processId = null, callback = null).out
        val entries = JSONObject(out).optJSONArray("entries") ?: return@withContext emptyList()
        (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id")
            val duration = e.optDouble("duration", 0.0)
            if (!Track.isVideoId(id) || duration.isNaN() || duration <= 0) return@mapNotNull null // live streams
            val (song, artist) = Metadata.guess(e.optString("title"), e.optString("channel").ifBlank { e.optString("uploader") })
            Track(id, song, artist, duration = duration)
        }
    }

    /** Downloads the song's AAC audio (the format the watch plays natively) into [dir] as <id>.m4a. */
    suspend fun download(id: String, dir: File, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        requireReady()
        val dest = File(dir, "$id.m4a")
        dest.delete()
        // If YouTube refuses the default way, try the Android VR app profile, which often still works.
        val attempts = listOf<String?>(null, "youtube:player_client=android_vr,default")
        var lastError: Exception? = null
        for (extractorArgs in attempts) {
            try {
                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$id").apply {
                    addOption("-f", "140/bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]")
                    addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    extractorArgs?.let { addOption("--extractor-args", it) }
                }
                YoutubeDL.getInstance().execute(request, processId = "dl-$id", callback = { p, _, _ -> progress(p / 100f) })
                if (dest.exists()) return@withContext dest
                throw IOException("yt-dlp finished without a file")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(lastError?.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: "yt-dlp couldn't download this song")
    }

    /** Updates yt-dlp itself. YouTube changes often, and old versions stop working. */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        requireReady()
        val result = YoutubeDL.getInstance().updateYoutubeDL(appContext)
        val version = YoutubeDL.getInstance().version(appContext) ?: "?"
        status.value = "yt-dlp $version"
        "${result ?: "Done"} · yt-dlp $version"
    }
}
