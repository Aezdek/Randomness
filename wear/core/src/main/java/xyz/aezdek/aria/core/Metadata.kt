package xyz.aezdek.aria.core

/**
 * Turns video titles like "Artist - Song (Official Video) [4K]" by "ArtistVEVO" into song and artist.
 * The same rules as guess_metadata() in aria_proxy.py and the iPod app.
 */
object Metadata {
    private const val NOISE_WORDS = "official|lyric|lyrics|audio|video|visuali[sz]er|music video|hd|hq|4k|8k|1080p|" +
        "720p|mv|m/v|clip officiel|videoclip|video oficial|letra|explicit|clean|full song"
    private val NOISE = Regex("""\s*[(\[{][^)\]}]*\b(?:$NOISE_WORDS)\b[^)\]}]*[)\]}]""", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex("""\s*[|｜/]\s*.*$""")
    private val DASHES = Regex("""\s+[-–—~]\s+""")
    private val QUOTED = Regex("""^(.+?)\s+["“](.+?)["”]$""")

    fun cleanChannel(channel: String): String = channel
        .replace(Regex("""\s*-\s*Topic$"""), "")
        .replace(Regex("""\s*vevo$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*official(?:\s*(?:channel|youtube channel|music))?$""", RegexOption.IGNORE_CASE), "")
        .trim()

    fun cleanSong(text: String): String = NOISE.replace(text, "")
        .replace(TRAILING, "")
        .trim().trim('"', '“', '”', '\'').trim()
        .replace(Regex("""\s{2,}"""), " ")

    /** (song, artist) */
    fun guess(title: String, channel: String): Pair<String, String> {
        if (channel.endsWith(" - Topic")) return (cleanSong(title).ifBlank { title }) to cleanChannel(channel)
        val stripped = NOISE.replace(title, "").trim()
        QUOTED.find(stripped)?.let { return cleanSong(it.groupValues[2]) to it.groupValues[1].trim() }
        val parts = DASHES.split(stripped, limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            return (cleanSong(parts[1]).ifBlank { title }) to parts[0].trim().trim('"')
        }
        return (cleanSong(title).ifBlank { title }) to cleanChannel(channel)
    }
}
