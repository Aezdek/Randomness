package xyz.aezdek.aria.watch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import xyz.aezdek.aria.core.Track
import java.io.File

/** Songs stored on the watch: music/<id>.m4a, art/<id>.jpg and library.json with their details. */
object Library {
    private lateinit var dir: File
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    /** Download progress by song id, 0..1, for songs on their way. */
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun init(context: Context) {
        dir = context.filesDir
        File(dir, "music").mkdirs()
        File(dir, "art").mkdirs()
        _tracks.value = runCatching { Track.listFromJson(JSONArray(index.readText())) }.getOrDefault(emptyList())
            .filter { audioFile(it.id).exists() }
    }

    private val index get() = File(dir, "library.json")

    fun audioFile(id: String) = File(dir, "music/$id.m4a")
    fun artFile(id: String) = File(dir, "art/$id.jpg")
    fun has(id: String) = _tracks.value.any { it.id == id }
    fun get(id: String) = _tracks.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(track: Track) {
        _tracks.value = listOf(track) + _tracks.value.filterNot { it.id == track.id }
        save()
    }

    @Synchronized
    fun remove(id: String) {
        audioFile(id).delete()
        artFile(id).delete()
        _tracks.value = _tracks.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun removeAll() = _tracks.value.map { it.id }.forEach(::remove)

    fun bytesUsed(): Long = (File(dir, "music").listFiles().orEmpty() + File(dir, "art").listFiles().orEmpty()).sumOf { it.length() }

    fun setProgress(id: String, value: Float?) {
        progress.value = if (value == null) progress.value - id else progress.value + (id to value)
    }

    private fun save() {
        val tmp = File(dir, "library.json.tmp")
        tmp.writeText(Track.listToJson(_tracks.value).toString())
        tmp.renameTo(index)
    }
}
