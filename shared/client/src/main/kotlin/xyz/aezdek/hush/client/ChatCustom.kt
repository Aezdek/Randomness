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

/** A chat theme: the gradient behind your own messages, as on Instagram. Same ids and colours as the web app's list. */
data class ChatTheme(val id: String, val name: String, val colors: List<Long>)

object ChatThemes {
    const val DEFAULT = "default"

    val all = listOf(
        ChatTheme(DEFAULT, "Hush", listOf(0xFF7C5CFF, 0xFFB34FE0, 0xFFE84393)),
        ChatTheme("love", "Love", listOf(0xFFFF0844, 0xFFFF5E7E, 0xFFFFB199)),
        ChatTheme("sunset", "Sunset", listOf(0xFFF83600, 0xFFFF6A3D, 0xFFF9D423)),
        ChatTheme("ocean", "Ocean", listOf(0xFF0061FF, 0xFF2F9BFF, 0xFF60EFFF)),
        ChatTheme("mint", "Mint", listOf(0xFF0BA360, 0xFF1FB97A, 0xFF3CBA92)),
        ChatTheme("forest", "Forest", listOf(0xFF134E5E, 0xFF3D7F6E, 0xFF71B280)),
        ChatTheme("lavender", "Lavender", listOf(0xFF8E7CF0, 0xFFB894E8, 0xFFE8A6D9)),
        ChatTheme("berry", "Berry", listOf(0xFF8E2DE2, 0xFF6A1BD1, 0xFF4A00E0)),
        ChatTheme("midnight", "Midnight", listOf(0xFF1E3C72, 0xFF24467F, 0xFF2A5298)),
        ChatTheme("citrus", "Citrus", listOf(0xFFF7971E, 0xFFF9A825, 0xFFE8A200)),
        ChatTheme("mono", "Mono", listOf(0xFF4A4A52, 0xFF3C3C43, 0xFF2E2E34)),
    )

    fun byId(id: String?): ChatTheme? = all.firstOrNull { it.id == id }
}
