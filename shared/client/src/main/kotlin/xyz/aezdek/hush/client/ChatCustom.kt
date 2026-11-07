package xyz.aezdek.hush.client

import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType

/** How a chat looks right now: every [ChatEvent] in it applied in order (mirrors web/src/core/chat.ts). */
data class ChatCustom(
    val theme: String = ChatThemes.DEFAULT,
    /** Nicknames by user id. */
    val nicknames: Map<String, String> = emptyMap(),
    val quickReaction: String = DEFAULT_REACTION,
    /** Pinned message ids, oldest pin first. */
    val pinned: List<String> = emptyList(),
) {
    fun apply(event: ChatEvent): ChatCustom {
        var next = this
        event.theme?.let { next = next.copy(theme = if (ChatThemes.byId(it) != null) it else ChatThemes.DEFAULT) }
        event.nicknameFor?.let { userId ->
            val name = event.nickname?.trim()?.take(MAX_NICKNAME)
            next = next.copy(nicknames = if (name.isNullOrEmpty()) next.nicknames - userId else next.nicknames + (userId to name))
        }
        event.quickReaction?.takeIf { it.isNotBlank() }?.let { next = next.copy(quickReaction = it.take(16)) }
        event.pin?.let { id -> next = next.copy(pinned = next.pinned.filter { it != id } + id) }
        event.unpin?.let { id -> next = next.copy(pinned = next.pinned - id) }
        return next
    }

    companion object {
        const val DEFAULT_REACTION = "❤️"
        const val MAX_NICKNAME = 40

        /** Folds the chat's events; pins of messages that no longer exist (unsent, vanished) are dropped. */
        fun of(messages: List<Entity>): ChatCustom {
            var custom = ChatCustom()
            val ids = HashSet<String>()
            for (message in messages) {
                ids += message.id
                val p = message.payloadAs<MessagePayload>() ?: continue
                if (p.type == MessageType.SYSTEM) p.event?.let { custom = custom.apply(it) }
            }
            return custom.copy(pinned = custom.pinned.filter { it in ids })
        }
    }
}

/** One side of a theme: how it looks while the app is dark, or while it is light. */
data class ChatThemeLook(
    /** The chat's background, top to bottom. */
    val background: List<Long>,
    /** Their messages, and the text on them. */
    val received: Long,
    val receivedText: Long,
    /** The chat's buttons. */
    val accent: Long,
)

/** A faint pattern some themes draw over their background. */
enum class ChatPattern { DOTS, PETALS, SPARKLES, WAVES, GRID }

/**
 * A chat theme: your messages in its gradient, and (except for the default, which keeps the app's own look) a
 * background, a tint for their messages and an accent, in a dark and a light version that follow the app's
 * appearance. Some add a faint pattern. Same ids and colours as the web app's list (web/src/core/chat.ts).
 */
data class ChatTheme(
    val id: String,
    val name: String,
    /** Your messages: a gradient across the screen. */
    val colors: List<Long>,
    /** The text on your messages. */
    val mineText: Long = 0xFFFFFFFF,
    val dark: ChatThemeLook? = null,
    val light: ChatThemeLook? = null,
    val pattern: ChatPattern? = null,
) {
    val hasBackground: Boolean get() = dark != null && light != null
    /** The look for the app's current appearance; null for the default theme. */
    fun look(darkMode: Boolean): ChatThemeLook? = if (darkMode) dark else light
}

object ChatThemes {
    const val DEFAULT = "default"

    val all = listOf(
        ChatTheme(DEFAULT, "Hush", listOf(0xFF7C5CFF, 0xFFE84393)),
        ChatTheme("dusk", "Dusk", listOf(0xFFFF7A59, 0xFFF2497C), dark = ChatThemeLook(listOf(0xFF2A1636, 0xFF120A19), received = 0x1FFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFFF9A7A), light = ChatThemeLook(listOf(0xFFFFF3EC, 0xFFFFE6EE), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFFD9502F)),
        ChatTheme("lagoon", "Lagoon", listOf(0xFF12B5A5, 0xFF1192E8), dark = ChatThemeLook(listOf(0xFF05303A, 0xFF03161C), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFF5FDCCD), light = ChatThemeLook(listOf(0xFFE7FBF8, 0xFFE2F2FF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF0B8579), pattern = ChatPattern.WAVES),
        ChatTheme("moss", "Moss", listOf(0xFF7FB85E, 0xFF4C9A6A), dark = ChatThemeLook(listOf(0xFF18261B, 0xFF0A120C), received = 0x1AFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFA9DC8C), light = ChatThemeLook(listOf(0xFFF2F8EC, 0xFFE5EFDC), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF437F34), pattern = ChatPattern.DOTS),
        ChatTheme("ember", "Ember", listOf(0xFFFFA246, 0xFFEC5A2A), dark = ChatThemeLook(listOf(0xFF2C1510, 0xFF130807), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFFFAD70), light = ChatThemeLook(listOf(0xFFFFF5EA, 0xFFFFE8D7), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFFC9561A)),
        ChatTheme("lilac", "Lilac", listOf(0xFF9F86FF, 0xFFC77DFF), dark = ChatThemeLook(listOf(0xFF1E1838, 0xFF0C0A19), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFC8B8FF), light = ChatThemeLook(listOf(0xFFF6F2FF, 0xFFEEE6FF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF6B4FE0), pattern = ChatPattern.SPARKLES),
        ChatTheme("blossom", "Blossom", listOf(0xFFF472B6, 0xFFFB6F84), dark = ChatThemeLook(listOf(0xFF2B1121, 0xFF12070E), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFF9A8D4), light = ChatThemeLook(listOf(0xFFFFF1F7, 0xFFFFE5EF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFFC8256A), pattern = ChatPattern.PETALS),
        ChatTheme("aurora", "Aurora", listOf(0xFF2FD3A0, 0xFF5B6CF0), dark = ChatThemeLook(listOf(0xFF0A1631, 0xFF050914), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFF6FF0CF), light = ChatThemeLook(listOf(0xFFEDFCF6, 0xFFEEF0FF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF4148CC), pattern = ChatPattern.SPARKLES),
        ChatTheme("dune", "Dune", listOf(0xFFCF8048, 0xFFA85A30), dark = ChatThemeLook(listOf(0xFF251B12, 0xFF110C08), received = 0x1AFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFE9B07E), light = ChatThemeLook(listOf(0xFFFBF4E9, 0xFFF3E6D1), received = 0xFFFFFDF8, receivedText = 0xFF14141A, accent = 0xFF8F4C26), pattern = ChatPattern.GRID),
        ChatTheme("graphite", "Graphite", listOf(0xFF5A5A63, 0xFF34343B), dark = ChatThemeLook(listOf(0xFF1B1B1F, 0xFF0B0B0D), received = 0x1AFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFD8D8DE), light = ChatThemeLook(listOf(0xFFF5F5F6, 0xFFEAEAEC), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF3D3D45)),
        ChatTheme("citron", "Citron", listOf(0xFFD9F24F, 0xFF9EE03A), mineText = 0xFF18200A, dark = ChatThemeLook(listOf(0xFF151B07, 0xFF080B02), received = 0x1AFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFDCFA8F), light = ChatThemeLook(listOf(0xFFFBFDE8, 0xFFF2F8D0), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF557F10), pattern = ChatPattern.DOTS),
        ChatTheme("cobalt", "Cobalt", listOf(0xFF3F7FF6, 0xFF6A5CF0), dark = ChatThemeLook(listOf(0xFF0D1736, 0xFF060A18), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFF9CC3FF), light = ChatThemeLook(listOf(0xFFEEF4FF, 0xFFE5ECFF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF2453D0), pattern = ChatPattern.GRID),
    )

    /** Themes from before the redesign map to the closest new one, so chats that used them keep a similar look. */
    private val renamed = mapOf("love" to "blossom", "sunset" to "dusk", "ocean" to "lagoon", "mint" to "aurora", "forest" to "moss", "lavender" to "lilac", "berry" to "lilac", "midnight" to "cobalt", "citrus" to "ember", "mono" to "graphite")

    fun byId(id: String?): ChatTheme? = (renamed[id] ?: id).let { key -> all.firstOrNull { it.id == key } }
}
