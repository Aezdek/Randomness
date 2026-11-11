package xyz.aezdek.hush.client

import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.UserDto
import org.junit.Test
import org.junit.Assert.assertEquals

class ChatCustomTest {
    private var clock = 0L

    private fun message(author: String, payload: MessagePayload, id: String = "m${++clock}") = Entity(
        ObjectDto(id, Kind.MESSAGE, author, threadId = "t", audience = "thread", createdAt = clock, version = 1, payload = "",
            commitment = "", signature = "", receivedAt = clock),
        ByteArray(0), payload,
    )

    private fun event(author: String, event: ChatEvent) = message(author, MessagePayload(MessageType.SYSTEM, event = event))

    @Test
    fun aChatWithoutEventsHasTheDefaults() {
        val custom = ChatCustom.of(listOf(message("ana", MessagePayload(MessageType.TEXT, text = "hi"))))
        assertEquals(ChatCustom(), custom)
    }

    @Test
    fun laterEventsWinAndEachChangesOnlyItsOwnSetting() {
        val text = message("bo", MessagePayload(MessageType.TEXT, text = "pin me"))
        val custom = ChatCustom.of(
            listOf(
                event("ana", ChatEvent(theme = "ocean")),
                event("bo", ChatEvent(nicknameFor = "ana", nickname = "  Captain  ")),
                text,
                event("ana", ChatEvent(quickReaction = "🔥")),
                event("bo", ChatEvent(theme = "love")),
                event("ana", ChatEvent(pin = text.id)),
            ),
        )
        assertEquals("love", custom.theme)
        assertEquals(mapOf("ana" to "Captain"), custom.nicknames)
        assertEquals("🔥", custom.quickReaction)
        assertEquals(listOf(text.id), custom.pinned)
    }

    @Test
    fun clearingANicknameUnpinningAndUnknownThemesUndoEarlierEvents() {
        val text = message("bo", MessagePayload(MessageType.TEXT, text = "x"))
        val custom = ChatCustom.of(
            listOf(
                text,
                event("ana", ChatEvent(theme = "ocean")),
                event("ana", ChatEvent(nicknameFor = "bo", nickname = "B")),
                event("ana", ChatEvent(pin = text.id)),
                event("bo", ChatEvent(nicknameFor = "bo", nickname = " ")),
                event("bo", ChatEvent(unpin = text.id)),
                event("bo", ChatEvent(theme = "from-a-newer-app")),
            ),
        )
        assertEquals(ChatThemes.DEFAULT, custom.theme)
        assertEquals(emptyMap<String, String>(), custom.nicknames)
        assertEquals(emptyList<String>(), custom.pinned)
    }

    @Test
    fun pinsOfMessagesThatAreGoneAreDropped() {
        val custom = ChatCustom.of(listOf(event("ana", ChatEvent(pin = "unsent-message"))))
        assertEquals(emptyList<String>(), custom.pinned)
    }

    @Test
    fun nicknamesNameTheChatAndItsEvents() {
        val users = mapOf(
            "me" to user("me", "aez"),
            "ana" to user("ana", "ana.p"),
        )
        val messages = listOf(event("me", ChatEvent(nicknameFor = "ana", nickname = "Nana")))
        val index = Index(emptyMap(), emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap(), mapOf("t" to messages),
            emptyMap(), emptyMap(), null, emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals("Nana", index.nameIn("t", "ana", users))
        assertEquals("ana.p", index.nameIn(null, "ana", users))
        assertEquals("You set the nickname for ana.p to Nana", index.eventText("t", "me", ChatEvent(nicknameFor = "ana", nickname = "Nana"), users, "me"))
        assertEquals("Nana changed the theme to Lagoon", index.eventText("t", "ana", ChatEvent(theme = "lagoon"), users, "me"))
        // A theme set before the redesign shows as its closest new one.
        assertEquals("Nana changed the theme to Lagoon", index.eventText("t", "ana", ChatEvent(theme = "ocean"), users, "me"))
    }

    @Test
    fun themesMatchTheWebAppAndHaveBothLooks() {
        assertEquals(
            listOf("default", "dusk", "lagoon", "moss", "ember", "lilac", "blossom", "aurora", "dune", "graphite", "citron", "cobalt"),
            ChatThemes.all.map { it.id },
        )
        assertEquals("blossom", ChatThemes.byId("love")?.id)
        assertEquals(null, ChatThemes.byId("no-such-theme"))
        ChatThemes.all.drop(1).forEach { assertEquals(it.id, true, it.hasBackground) }
    }

    @Test
    fun sceneryComesOutTheSameAsOnTheWeb() {
        // The values chat.test.ts pins for the web app's generator.
        val (a, b) = ChatScenes.scatter(7, 2)
        listOf(a.x to 0.238781f, a.y to 0.913493f, a.s to 0.612492f, a.t to 0.926981f, b.x to 0.049341f)
            .forEach { (got, want) -> assertEquals(want, got, 0.00001f) }
        assertEquals(0xFF808080, ChatScenes.mix(0xFF000000, 0xFFFFFFFF, 0.5f))
        val dusk = ChatThemes.byId("dusk")!!
        // mix(mix('#33193f', '#150a1c', 0), '#ff9a7a', 0.17) and mix('#ffdfe6', '#d9502f', 0.24) on the web.
        assertEquals(0xFF562F49, ChatScenes.ridgeColor(dusk.dark!!, 0f, dark = true))
        assertEquals(0xFFF6BDBA, ChatScenes.ridgeColor(dusk.light!!, 1f, dark = false))
        ChatThemes.all.forEach { theme -> theme.scene?.ridges?.forEach { ridge -> assertEquals(theme.id, true, ridge.heights.all { it in 0f..1f }) } }
    }

    private fun user(id: String, name: String) =
        UserDto(id = id, username = name, signPk = "", boxPk = "", boxPkSig = "", keyVersion = 1, isPrivate = false, createdAt = 0,
            followState = "none", followsMe = "none", blockedByMe = false)
}
