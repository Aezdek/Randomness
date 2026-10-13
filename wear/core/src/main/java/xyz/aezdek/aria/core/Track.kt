package xyz.aezdek.aria.core

import org.json.JSONArray
import org.json.JSONObject

/** A YouTube song, as the Aria server, the phone and the watch all describe it. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Double = 0.0,
    /** Square album art (iTunes, 600x600) when known. */
    val artwork: String? = null,
    /** [start, end] seconds of non-music parts to skip (SponsorBlock). */
    val skip: List<Pair<Double, Double>> = emptyList(),
) {
    val subtitle: String get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "YouTube" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        if (album.isNotEmpty()) put("album", album)
        put("duration", duration)
        artwork?.let { put("artwork", it) }
        if (skip.isNotEmpty()) put("skip", JSONArray(skip.map { JSONArray(listOf(it.first, it.second)) }))
    }

    companion object {
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        fun isVideoId(id: String) = VIDEO_ID.matches(id)

        fun fromJson(o: JSONObject): Track? {
            val id = o.optString("id")
            if (!isVideoId(id)) return null
            val skip = mutableListOf<Pair<Double, Double>>()
            o.optJSONArray("skip")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val seg = arr.optJSONArray(i) ?: continue
                    if (seg.length() == 2) skip += seg.optDouble(0) to seg.optDouble(1)
                }
            }
            return Track(
                id = id,
                title = o.optString("title").ifBlank { "Untitled" },
                artist = o.optString("artist"),
                album = o.optString("album"),
                duration = o.optDouble("duration", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                artwork = o.optString("artwork").takeIf { it.startsWith("https://") || it.startsWith("http://") },
                skip = skip,
            )
        }

        fun listFromJson(arr: JSONArray?): List<Track> =
            (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it)?.let(::fromJson) }

        fun listToJson(tracks: List<Track>) = JSONArray(tracks.map { it.toJson() })
    }
}

fun formatTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
