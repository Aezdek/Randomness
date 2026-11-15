package xyz.aezdek.hush.client

import xyz.aezdek.hush.protocol.LiveLocation

/**
 * Limits on what other people's messages can make a screen draw. A modified app can send a 200,000-character text, a
 * poll with 1,500 options or 300 photos in one message: well-formed, so decoding takes them, but drawn as they are they
 * would freeze the chat (and a notification that big is refused by Android). The apps' own composers never get near
 * these limits.
 */
object Display {
    /** Characters a message's text shows in the chat (the rest is cut, with an ellipsis). */
    const val MAX_TEXT = 8_000

    /** Characters a one-line preview (the inbox, a notification, a quoted reply) uses. */
    const val MAX_PREVIEW = 300

    /** Photos and videos one message shows: what the composer lets anyone send. */
    const val MAX_MEDIA = 10

    /** Options a poll shows: what the composer lets anyone add. */
    const val MAX_POLL_OPTIONS = 6

    private val BLANK_LINES = Regex("\n{3,}")

    /** A message's text as the chat shows it: at most [max] characters, and never more than one empty line in a row. */
    fun text(text: String?, max: Int = MAX_TEXT): String {
        val value = text.orEmpty()
        val cut = if (value.length > max) value.take(max).trimEnd() + "…" else value
        return BLANK_LINES.replace(cut, "\n\n")
    }

    /** How much of a long message shows before "Read more": Instagram's limit for a whole message. */
    const val FOLD_CHARS = 1_000
    const val FOLD_LINES = 20

    /**
     * The start of a long message, up to [FOLD_CHARS] characters or [FOLD_LINES] lines, with an ellipsis; the text
     * itself when it is shorter. A modified app's wall of text then takes one screen, not twenty.
     */
    fun fold(text: String): String {
        var end = minOf(text.length, FOLD_CHARS)
        var lines = 0
        for (i in 0 until end) {
            if (text[i] == '\n' && ++lines >= FOLD_LINES) {
                end = i
                break
            }
        }
        return if (end >= text.length) text else text.take(end).trimEnd() + "…"
    }

    /** A one-line preview of [text]: line breaks become spaces, and it stops at [MAX_PREVIEW] characters. */
    fun preview(text: String?): String = text(text?.replace('\n', ' '), MAX_PREVIEW)

    /** What a heart message shows: its first emoji (one character as a person sees it), or [fallback] if it has none. */
    fun heart(text: String?, fallback: String): String = firstGrapheme(text?.trim().orEmpty()).takeIf { it.isNotEmpty() } ?: fallback

    /**
     * The first character as a person sees it: 👩‍👩‍👧‍👦, 🇵🇹 or é is one, however many code points it takes. At most 16
     * UTF-16 units, the longest a single emoji needs; anything longer is cut there.
     */
    fun firstGrapheme(text: String): String {
        if (text.isEmpty()) return ""
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = it.next().takeIf { e -> e != java.text.BreakIterator.DONE } ?: text.length
        return text.substring(0, end).take(16)
    }

    /** A duration someone else's app reported, as something a clock can show: never negative, never more than a day. */
    fun duration(ms: Long?): Long = (ms ?: 0).coerceIn(0, 24 * 60 * 60 * 1000L)

    /** Whether [at] is a moment worth drawing as a date: 1970 to the year 9999, as the web app allows. */
    fun plausibleTime(at: Long): Boolean = at in 0..253_402_300_799_999

    /** Whether a live location is a place on Earth, and so worth drawing. */
    fun plausible(location: LiveLocation?): Boolean = location != null &&
        location.lat.isFinite() && location.lng.isFinite() && location.lat in -90.0..90.0 && location.lng in -180.0..180.0 &&
        plausibleTime(location.until) && plausibleTime(location.updatedAt)
}
