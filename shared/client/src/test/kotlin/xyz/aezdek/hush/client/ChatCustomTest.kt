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
        assertEquals("Nana changed the theme to Ocean", index.eventText("t", "ana", ChatEvent(theme = "ocean"), users, "me"))
    }

    private fun user(id: String, name: String) =
        UserDto(id = id, username = name, signPk = "", boxPk = "", boxPkSig = "", keyVersion = 1, isPrivate = false, createdAt = 0,
            followState = "none", followsMe = "none", blockedByMe = false)
}
