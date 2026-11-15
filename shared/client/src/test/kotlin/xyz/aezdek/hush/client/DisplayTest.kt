package xyz.aezdek.hush.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.protocol.LiveLocation

class DisplayTest {
    @Test
    fun textIsCutAndBlankLinesCollapse() {
        assertEquals("hi", Display.text("hi"))
        assertEquals(Display.MAX_TEXT + 1, Display.text("W".repeat(200_000)).length)
        assertTrue(Display.text("W".repeat(200_000)).endsWith("…"))
        assertEquals("a\n\nb", Display.text("a" + "\n".repeat(400) + "b"))
        assertEquals("a\nb", Display.text("a\nb"))
        assertEquals("", Display.text(null))
    }

    @Test
    fun longMessagesFold() {
        assertEquals("short", Display.fold("short"))
        assertEquals(Display.FOLD_CHARS + 1, Display.fold("W".repeat(8_000)).length)
        val tall = (1..100).joinToString("\n") { "line $it" }
        assertEquals(Display.FOLD_LINES, Display.fold(tall).lines().size)
        assertTrue(Display.fold(tall).endsWith("…"))
        val exactly = "x".repeat(Display.FOLD_CHARS)
        assertEquals(exactly, Display.fold(exactly))
    }

    @Test
    fun previewsAreOneShortLine() {
        val long = Display.preview("line one\nline two " + "x".repeat(10_000))
        assertFalse(long.contains('\n'))
        assertEquals(Display.MAX_PREVIEW + 1, long.length)
    }

    @Test
    fun aHeartIsOneEmojiOrADefaultHeart() {
        assertEquals("🔥", Display.heart("🔥", "❤️"))
        assertEquals("👩‍👩‍👧‍👦", Display.heart("👩‍👩‍👧‍👦", "❤️"))
        assertEquals("❤️", Display.heart("❤️".repeat(2_000), "❤️"))
        assertEquals("🔥", Display.heart("🔥".repeat(500), "❤️"))
        assertEquals("🇵🇹", Display.firstGrapheme("🇵🇹🇧🇷"))
        assertEquals("👍🏽", Display.firstGrapheme("👍🏽👍🏽"))
        assertEquals("e\u0301", Display.firstGrapheme("e\u0301tude"))
        assertEquals("❤️", Display.heart(null, "❤️"))
        assertEquals("❤️", Display.heart("   ", "❤️"))
    }

    @Test
    fun durationsAndPlacesStayReal() {
        assertEquals(0, Display.duration(Long.MIN_VALUE))
        assertEquals(24 * 60 * 60 * 1000L, Display.duration(Long.MAX_VALUE))
        assertEquals(65_000, Display.duration(65_000))
        assertTrue(Display.plausible(LiveLocation(38.7, -9.1, 20f, 1, 1)))
        assertFalse(Display.plausible(LiveLocation(1e308, 0.0, null, 1, 1)))
        assertFalse(Display.plausible(LiveLocation(91.5, 181.5, null, 1, 1)))
        assertFalse(Display.plausible(LiveLocation(Double.NaN, 0.0, null, 1, 1)))
        assertFalse(Display.plausible(null))
        assertFalse(Display.plausible(LiveLocation(1.0, 1.0, null, Long.MAX_VALUE, 1)))
        assertFalse(Display.plausible(LiveLocation(1.0, 1.0, null, 2, Long.MIN_VALUE)))
        assertTrue(Display.plausibleTime(1_800_000_000_000))
        assertFalse(Display.plausibleTime(Long.MAX_VALUE))
        assertFalse(Display.plausibleTime(-1))
    }
}
