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

/**
 * A chat theme, as on Instagram: a background behind the whole conversation, a gradient for your messages, a translucent
 * tint for theirs and an accent for the chat's buttons. The default theme keeps the app's own look. Same ids and colours
 * as the web app's list (web/src/core/chat.ts).
 */
data class ChatTheme(
    val id: String,
    val name: String,
    /** Your messages, one gradient across the screen. */
    val colors: List<Long>,
    /** Top to bottom; empty keeps the app's background. */
    val background: List<Long> = emptyList(),
    /** Their messages. */
    val received: Long = 0x24FFFFFF,
    val accent: Long = colors[1],
) {
    val hasBackground: Boolean get() = background.isNotEmpty()
}

object ChatThemes {
    const val DEFAULT = "default"

    val all = listOf(
        ChatTheme(DEFAULT, "Hush", listOf(0xFF7C5CFF, 0xFFB34FE0, 0xFFE84393)),
        ChatTheme("love", "Love", listOf(0xFFFF2D55, 0xFFFF5E7E, 0xFFFF8FA3), listOf(0xFF8A1240, 0xFF4A0820, 0xFF1E030C), accent = 0xFFFF7A9A),
        ChatTheme("sunset", "Sunset", listOf(0xFFF83600, 0xFFFF6A3D, 0xFFF9A23D), listOf(0xFF8C2F4E, 0xFF4A1838, 0xFF1C0A18), accent = 0xFFFF8A4D),
        ChatTheme("ocean", "Ocean", listOf(0xFF0061FF, 0xFF2F9BFF, 0xFF60EFFF), listOf(0xFF0B4A86, 0xFF07284E, 0xFF020C1C), accent = 0xFF5CC8FF),
        ChatTheme("mint", "Mint", listOf(0xFF0BA360, 0xFF1FB97A, 0xFF3CBA92), listOf(0xFF0E5C46, 0xFF06302A, 0xFF02120F), accent = 0xFF4FE0A8),
        ChatTheme("forest", "Forest", listOf(0xFF2E7D5B, 0xFF3D8F6E, 0xFF71B280), listOf(0xFF24432F, 0xFF12241A, 0xFF060D09), accent = 0xFF8FD3A0),
        ChatTheme("lavender", "Lavender", listOf(0xFF8E7CF0, 0xFFB894E8, 0xFFE8A6D9), listOf(0xFF574394, 0xFF2C2152, 0xFF110C22), accent = 0xFFC9B2FF),
        ChatTheme("berry", "Berry", listOf(0xFFA43CF0, 0xFF7B2BE0, 0xFF5A1DE0), listOf(0xFF5A1878, 0xFF2C0A3C, 0xFF10031A), accent = 0xFFC77DFF),
        ChatTheme("midnight", "Midnight", listOf(0xFF2D5BD8, 0xFF3B6FE8, 0xFF4F86F7), listOf(0xFF16234D, 0xFF0A1028, 0xFF03050E), accent = 0xFF7FA6FF),
        ChatTheme("citrus", "Citrus", listOf(0xFFF76B1C, 0xFFF7931E, 0xFFF9A825), listOf(0xFF7A4300, 0xFF3A2000, 0xFF140B00), accent = 0xFFFFB84D),
        ChatTheme("mono", "Mono", listOf(0xFF6A6A73, 0xFF55555D, 0xFF45454C), listOf(0xFF2E2E33, 0xFF161619, 0xFF050506), accent = 0xFFD0D0D6),
    )

    fun byId(id: String?): ChatTheme? = all.firstOrNull { it.id == id }
}
