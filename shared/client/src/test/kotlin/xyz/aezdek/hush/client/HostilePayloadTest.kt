package xyz.aezdek.hush.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.MessagePayload

/**
 * What a member with a modified app can put inside an encrypted payload. The server can't check it, so the app's own
 * decoding has to refuse it without falling over: one such object must never stop the app or the ones after it.
 */
class HostilePayloadTest {
    private val blob = "a".repeat(64)

    @Test
    fun wrongShapesAreRefused() {
        val bad = listOf(
            "null", "5", "\"text\"", "[]", "{}", """{"type":5}""", """{"type":"text","text":{"a":1}}""", """{"type":"media","media":"x"}""",
            """{"type":"poll","poll":{"question":"q","options":"ab"}}""", """{"type":"plan","plan":{"title":"x","at":"soon"}}""",
            """{"type":"location","location":{"lat":1,"lng":2,"until":3}}""", """{"type":"plan","plan":{"title":"x","at":1e999}}""",
            """{"type":"media","media":[{"blobId":"$blob","key":"k","mime":"image/jpeg","size":1,"width":99999999999999}]}""",
        )
        for (text in bad) assertNull(text, Payloads.decodeOrNull(Kind.MESSAGE, text))
        val ok = Payloads.decodeOrNull(Kind.MESSAGE, """{"type":"text","text":"hi","somethingNew":{"x":1}}""") as MessagePayload
        assertEquals("hi", ok.text)
    }

    @Test
    fun aPayloadNestedThousandsDeepIsRefusedNotFatal() {
        // A poster inside a poster inside a poster: 256 KB of payload is room for some twenty thousand levels.
        val depth = 20_000
        val media = buildString {
            repeat(depth) { append("""{"blobId":"$blob","key":"k","mime":"image/jpeg","size":1,"poster":""") }
            append("null")
            repeat(depth) { append("}") }
        }
        assertNull(Payloads.decodeOrNull(Kind.MESSAGE, """{"type":"media","media":[$media]}"""))
        // The same for a kind this app doesn't know, which is read as plain JSON.
        val nested = "[".repeat(depth) + "]".repeat(depth)
        assertNull(Payloads.decodeOrNull("a-kind-from-a-newer-app", """{"x":$nested}"""))
        // And the app is still standing: an ordinary payload decodes afterwards.
        assertTrue(Payloads.decodeOrNull(Kind.MESSAGE, """{"type":"text","text":"still here"}""") is MessagePayload)
    }
}
