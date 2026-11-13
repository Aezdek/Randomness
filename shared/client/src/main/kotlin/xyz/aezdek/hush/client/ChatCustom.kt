package xyz.aezdek.hush.client

import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.Disappearing
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
    /** Disappearing messages: how long new messages last, in milliseconds; 0 if they stay. */
    val disappearAfter: Long = 0,
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
        event.disappearAfter?.let { after -> next = next.copy(disappearAfter = after.takeIf { it in Disappearing.CHOICES } ?: 0) }
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

/** A line of hills, dunes, waves, peaks or rooftops along the bottom of the chat. */
data class Ridge(
    val shape: Shape,
    /** Heights across the width, each 0 to 1 of [height]. */
    val heights: List<Float>,
    /** How tall the ridge stands, in dp. */
    val height: Float,
    /** 0 is the farthest ridge (hazy), 1 the nearest (strongest). */
    val depth: Float,
) {
    enum class Shape { SMOOTH, PEAKS, STEPS }
}

/** A sun or moon: across (0 to 1), and down from the top or up from the bottom in dp. */
data class Orb(val x: Float, val top: Float? = null, val bottom: Float? = null, val r: Float)

/** Small marks scattered over the chat; an alpha of 0 leaves them out of that look. */
data class Motes(val kind: Kind, val count: Int, val seed: Int, val minSize: Float, val maxSize: Float, val darkAlpha: Float, val lightAlpha: Float) {
    enum class Kind { STAR, SPARKLE, PETAL, DOT, RING }
}

/** A wide wash of one of the theme's own two colours near the top (an aurora). [rx] is a share of the width. */
data class Glow(val x: Float, val top: Float, val rx: Float, val ry: Float, val color: Int, val darkAlpha: Float, val lightAlpha: Float)

/**
 * A theme's scenery, behind the messages. Everything hangs off an edge and is sized in dp, so the same scene suits
 * a phone and a tablet: ridges stretch along the bottom (or repeat every [span] dp), a sun or moon keeps its shape,
 * and the small marks repeat on a tile too large to notice. The web app draws the same from web/src/core/chat.ts.
 */
data class ChatScene(
    val ridges: List<Ridge> = emptyList(),
    val span: Float? = null,
    val orb: Orb? = null,
    val motes: Motes? = null,
    val glows: List<Glow> = emptyList(),
)

/** The arithmetic of a scene, shared with the web app (same names in chat.ts) and tested against it. */
object ChatScenes {
    /** The marks repeat on a square this many dp wide. */
    const val MOTE_TILE = 600f

    /** Where one mark goes on the tile ([x], [y]), how large it is between the two sizes ([s]), and its turn and strength ([t]); all 0 to 1. */
    data class Mark(val x: Float, val y: Float, val s: Float, val t: Float)

    /** The same sequence in the web app and here, so both scatter a theme's marks identically. */
    fun scatter(seed: Int, count: Int): List<Mark> {
        var state = seed
        fun next(): Float {
            state = state * 1664525 + 1013904223
            return ((state.toLong() and 0xFFFFFFFFL) / 4294967296.0).toFloat()
        }
        return List(count) { Mark(next(), next(), next(), next()) }
    }

    /** Opaque [a] with [k] (0 to 1) of [b] mixed in. */
    fun mix(a: Long, b: Long, k: Float): Long {
        fun channel(shift: Int): Long {
            val x = (a shr shift and 0xFF).toInt()
            val y = (b shr shift and 0xFF).toInt()
            return Math.round(x + (y - x) * k.toDouble()).coerceIn(0, 255) shl shift
        }
        return 0xFF000000 or channel(16) or channel(8) or channel(0)
    }

    /** The colour of a ridge: hazier the farther back it stands, like hills at a distance. */
    fun ridgeColor(look: ChatThemeLook, depth: Float, dark: Boolean): Long =
        if (dark) mix(mix(look.background[0], look.background[1], depth), look.accent, 0.17f - 0.08f * depth)
        else mix(look.background[1], look.accent, 0.08f + 0.16f * depth)
}

/**
 * A chat theme: your messages in its gradient, and (except for the default, which keeps the app's own look) a
 * background, a tint for their messages and an accent, in a dark and a light version that follow the app's
 * appearance. Most have a scene of their own. Same ids and colours as the web app's list (web/src/core/chat.ts).
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
    val scene: ChatScene? = null,
) {
    val hasBackground: Boolean get() = dark != null && light != null
    /** The look for the app's current appearance; null for the default theme. */
    fun look(darkMode: Boolean): ChatThemeLook? = if (darkMode) dark else light
}

object ChatThemes {
    const val DEFAULT = "default"

    val all = listOf(
        ChatTheme(DEFAULT, "Hush", listOf(0xFF7C5CFF, 0xFFE84393)),
        ChatTheme(
            "dusk", "Dusk", listOf(0xFFFF7A59, 0xFFF2497C),
            dark = ChatThemeLook(listOf(0xFF33193F, 0xFF150A1C), received = 0x21FFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFFF9A7A),
            light = ChatThemeLook(listOf(0xFFFFF1E6, 0xFFFFDFE6), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFFD9502F),
            scene = ChatScene(
                orb = Orb(x = 0.68f, bottom = 132f, r = 46f),
                ridges = listOf(
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.55f, 0.7f, 0.5f, 0.8f, 0.62f, 0.9f, 0.7f, 0.6f, 0.78f), height = 172f, depth = 0f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.8f, 0.55f, 0.7f, 0.45f, 0.62f, 0.5f, 0.75f, 0.6f, 0.5f), height = 120f, depth = 0.5f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.4f, 0.6f, 0.5f, 0.7f, 0.45f, 0.55f, 0.8f, 0.6f, 0.7f), height = 70f, depth = 1f),
                ),
                motes = Motes(Motes.Kind.STAR, count = 39, seed = 7, minSize = 0.6f, maxSize = 1.5f, darkAlpha = 0.5f, lightAlpha = 0f),
            ),
        ),
        ChatTheme(
            "lagoon", "Lagoon", listOf(0xFF12B5A5, 0xFF1192E8),
            dark = ChatThemeLook(listOf(0xFF06343F, 0xFF03161C), received = 0x1EFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFF5FDCCD),
            light = ChatThemeLook(listOf(0xFFE4FAF7, 0xFFDCEEFF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF0B8579),
            scene = ChatScene(
                ridges = listOf(
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.72f, 0.92f, 0.72f, 0.92f, 0.72f, 0.92f, 0.72f, 0.92f, 0.72f, 0.92f, 0.72f, 0.92f, 0.72f), height = 112f, depth = 0f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.92f, 0.7f, 0.92f, 0.7f, 0.92f, 0.7f, 0.92f, 0.7f, 0.92f, 0.7f, 0.92f, 0.7f, 0.92f), height = 80f, depth = 0.5f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.68f, 0.94f, 0.68f, 0.94f, 0.68f, 0.94f, 0.68f, 0.94f, 0.68f, 0.94f, 0.68f, 0.94f, 0.68f), height = 46f, depth = 1f),
                ),
                motes = Motes(Motes.Kind.RING, count = 21, seed = 3, minSize = 3f, maxSize = 9f, darkAlpha = 0.16f, lightAlpha = 0.2f),
            ),
        ),
        ChatTheme(
            "moss", "Moss", listOf(0xFF7FB85E, 0xFF4C9A6A),
            dark = ChatThemeLook(listOf(0xFF1B2B1E, 0xFF0A120C), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFA9DC8C),
            light = ChatThemeLook(listOf(0xFFF1F8EA, 0xFFE2EDD8), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF437F34),
            scene = ChatScene(
                ridges = listOf(
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.6f, 0.85f, 0.7f, 0.5f, 0.65f, 0.9f, 0.75f), height = 180f, depth = 0f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.9f, 0.6f, 0.5f, 0.75f, 0.85f, 0.55f, 0.6f), height = 125f, depth = 0.5f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.55f, 0.7f, 0.8f, 0.62f, 0.5f, 0.62f, 0.78f), height = 72f, depth = 1f),
                ),
                motes = Motes(Motes.Kind.DOT, count = 24, seed = 11, minSize = 1.2f, maxSize = 2.6f, darkAlpha = 0.5f, lightAlpha = 0.22f),
            ),
        ),
        ChatTheme(
            "ember", "Ember", listOf(0xFFFFA246, 0xFFEC5A2A),
            dark = ChatThemeLook(listOf(0xFF321811, 0xFF130807), received = 0x1EFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFFFAD70),
            light = ChatThemeLook(listOf(0xFFFFF4E8, 0xFFFFE4D1), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFFC9561A),
            scene = ChatScene(
                ridges = listOf(
                    Ridge(Ridge.Shape.PEAKS, listOf(0.3f, 0.75f, 0.5f, 0.95f, 0.55f, 0.8f, 0.4f, 0.7f, 0.35f), height = 190f, depth = 0f),
                    Ridge(Ridge.Shape.PEAKS, listOf(0.6f, 0.4f, 0.8f, 0.5f, 0.65f, 0.45f, 0.9f, 0.5f, 0.6f), height = 120f, depth = 0.6f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.6f, 0.7f, 0.5f, 0.65f, 0.55f, 0.75f), height = 54f, depth = 1f),
                ),
                motes = Motes(Motes.Kind.DOT, count = 33, seed = 5, minSize = 1f, maxSize = 3f, darkAlpha = 0.55f, lightAlpha = 0.3f),
            ),
        ),
        ChatTheme(
            "lilac", "Lilac", listOf(0xFF9F86FF, 0xFFC77DFF),
            dark = ChatThemeLook(listOf(0xFF221B40, 0xFF0C0A19), received = 0x1EFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFC8B8FF),
            light = ChatThemeLook(listOf(0xFFF5F0FF, 0xFFEBE2FF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF6B4FE0),
            scene = ChatScene(
                orb = Orb(x = 0.2f, top = 96f, r = 26f),
                ridges = listOf(
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.5f, 0.8f, 0.55f, 0.9f, 0.6f, 0.85f, 0.5f, 0.75f, 0.6f, 0.9f, 0.55f), height = 120f, depth = 0f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.8f, 0.5f, 0.85f, 0.55f, 0.75f, 0.5f, 0.9f, 0.6f, 0.8f, 0.5f, 0.7f), height = 78f, depth = 0.7f),
                ),
                motes = Motes(Motes.Kind.SPARKLE, count = 21, seed = 9, minSize = 3f, maxSize = 7f, darkAlpha = 0.32f, lightAlpha = 0.28f),
            ),
        ),
        ChatTheme(
            "blossom", "Blossom", listOf(0xFFF472B6, 0xFFFB6F84),
            dark = ChatThemeLook(listOf(0xFF301326, 0xFF12070E), received = 0x1EFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFF9A8D4),
            light = ChatThemeLook(listOf(0xFFFFF0F6, 0xFFFFE1EC), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFFC8256A),
            scene = ChatScene(
                ridges = listOf(
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.5f, 0.7f, 0.9f, 0.75f, 0.55f, 0.6f), height = 110f, depth = 0.3f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.8f, 0.6f, 0.5f, 0.65f, 0.85f, 0.7f), height = 64f, depth = 1f),
                ),
                motes = Motes(Motes.Kind.PETAL, count = 42, seed = 4, minSize = 4f, maxSize = 8f, darkAlpha = 0.3f, lightAlpha = 0.32f),
            ),
        ),
        ChatTheme(
            "aurora", "Aurora", listOf(0xFF2FD3A0, 0xFF5B6CF0),
            dark = ChatThemeLook(listOf(0xFF0B1736, 0xFF050914), received = 0x1EFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFF6FF0CF),
            light = ChatThemeLook(listOf(0xFFECFCF5, 0xFFECEEFF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF4148CC),
            scene = ChatScene(
                glows = listOf(
                    Glow(x = 0.3f, top = 70f, rx = 0.62f, ry = 120f, color = 0, darkAlpha = 0.34f, lightAlpha = 0.22f),
                    Glow(x = 0.72f, top = 150f, rx = 0.55f, ry = 110f, color = 1, darkAlpha = 0.3f, lightAlpha = 0.2f),
                    Glow(x = 0.5f, top = 250f, rx = 0.7f, ry = 90f, color = 0, darkAlpha = 0.14f, lightAlpha = 0.1f),
                ),
                ridges = listOf(
                    Ridge(Ridge.Shape.PEAKS, listOf(0.4f, 0.8f, 0.55f, 0.95f, 0.6f, 0.75f, 0.45f, 0.85f, 0.5f), height = 150f, depth = 0.4f),
                    Ridge(Ridge.Shape.PEAKS, listOf(0.7f, 0.45f, 0.85f, 0.5f, 0.7f, 0.4f, 0.8f, 0.55f, 0.65f), height = 88f, depth = 1f),
                ),
                motes = Motes(Motes.Kind.STAR, count = 51, seed = 21, minSize = 0.6f, maxSize = 1.6f, darkAlpha = 0.6f, lightAlpha = 0f),
            ),
        ),
        ChatTheme(
            "dune", "Dune", listOf(0xFFCF8048, 0xFFA85A30),
            dark = ChatThemeLook(listOf(0xFF2B1F14, 0xFF110C08), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFE9B07E),
            light = ChatThemeLook(listOf(0xFFFBF3E6, 0xFFF1E2C9), received = 0xFFFFFDF8, receivedText = 0xFF14141A, accent = 0xFF8F4C26),
            scene = ChatScene(
                orb = Orb(x = 0.78f, top = 120f, r = 24f),
                ridges = listOf(
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.35f, 0.5f, 0.8f, 0.95f, 0.7f, 0.45f, 0.4f), height = 170f, depth = 0f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.9f, 0.7f, 0.45f, 0.4f, 0.6f, 0.85f, 0.95f), height = 120f, depth = 0.5f),
                    Ridge(Ridge.Shape.SMOOTH, listOf(0.4f, 0.55f, 0.75f, 0.6f, 0.4f, 0.5f, 0.7f), height = 64f, depth = 1f),
                ),
            ),
        ),
        ChatTheme(
            "graphite", "Graphite", listOf(0xFF5A5A63, 0xFF34343B),
            dark = ChatThemeLook(listOf(0xFF1C1C20, 0xFF0B0B0D), received = 0x1AFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFD8D8DE),
            light = ChatThemeLook(listOf(0xFFF5F5F6, 0xFFE9E9EC), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF3D3D45),
        ),
        ChatTheme(
            "citron", "Citron", listOf(0xFFD9F24F, 0xFF9EE03A), mineText = 0xFF18200A,
            dark = ChatThemeLook(listOf(0xFF181F08, 0xFF080B02), received = 0x1CFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFFDCFA8F),
            light = ChatThemeLook(listOf(0xFFFBFDE6, 0xFFF0F7CB), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF557F10),
            scene = ChatScene(
                motes = Motes(Motes.Kind.DOT, count = 14, seed = 13, minSize = 16f, maxSize = 44f, darkAlpha = 0.07f, lightAlpha = 0.11f),
            ),
        ),
        ChatTheme(
            "cobalt", "Cobalt", listOf(0xFF3F7FF6, 0xFF6A5CF0),
            dark = ChatThemeLook(listOf(0xFF0F1B40, 0xFF060A18), received = 0x1EFFFFFF, receivedText = 0xFFFFFFFF, accent = 0xFF9CC3FF),
            light = ChatThemeLook(listOf(0xFFEDF3FF, 0xFFE0E9FF), received = 0xFFFFFFFF, receivedText = 0xFF14141A, accent = 0xFF2453D0),
            scene = ChatScene(
                orb = Orb(x = 0.82f, top = 92f, r = 18f),
                span = 420f,
                ridges = listOf(
                    Ridge(Ridge.Shape.STEPS, listOf(0.5f, 0.8f, 0.6f, 0.95f, 0.4f, 0.7f, 0.85f, 0.55f, 0.75f, 0.45f, 0.9f, 0.6f, 0.7f, 0.5f), height = 130f, depth = 0.6f),
                    Ridge(Ridge.Shape.STEPS, listOf(0.7f, 0.4f, 0.85f, 0.5f, 0.65f, 0.9f, 0.45f, 0.6f, 0.8f, 0.5f, 0.7f, 0.4f, 0.6f, 0.75f), height = 80f, depth = 1f),
                ),
                motes = Motes(Motes.Kind.STAR, count = 45, seed = 17, minSize = 0.6f, maxSize = 1.5f, darkAlpha = 0.6f, lightAlpha = 0f),
            ),
        ),
    )

    /** Themes from before the redesign map to the closest new one, so chats that used them keep a similar look. */
    private val renamed = mapOf("love" to "blossom", "sunset" to "dusk", "ocean" to "lagoon", "mint" to "aurora", "forest" to "moss", "lavender" to "lilac", "berry" to "lilac", "midnight" to "cobalt", "citrus" to "ember", "mono" to "graphite")

    fun byId(id: String?): ChatTheme? = (renamed[id] ?: id).let { key -> all.firstOrNull { it.id == key } }
}
