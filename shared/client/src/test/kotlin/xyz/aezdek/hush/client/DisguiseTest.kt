package xyz.aezdek.hush.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.client.DecoyInput.CORNER
import xyz.aezdek.hush.client.DecoyInput.MESSAGE
import xyz.aezdek.hush.client.DecoyInput.VOLUME_DOWN
import xyz.aezdek.hush.client.DecoyInput.VOLUME_UP
import xyz.aezdek.hush.crypto.KdfParams

class DisguiseTest {
    private fun TriggerDetector.run(vararg steps: Pair<DecoyInput, Long>): List<Boolean> = steps.map { (input, at) -> feed(input, at) }

    @Test
    fun `four quick taps on the message get in, three don't`() {
        val d = TriggerDetector(DisguiseTrigger.TAP_MESSAGE)
        assertEquals(listOf(false, false, false, true), d.run(MESSAGE to 0, MESSAGE to 300, MESSAGE to 600, MESSAGE to 900))
        val three = TriggerDetector(DisguiseTrigger.TAP_MESSAGE)
        assertEquals(listOf(false, false, false), three.run(MESSAGE to 0, MESSAGE to 300, MESSAGE to 600))
    }

    @Test
    fun `a pause too long starts the count again`() {
        val d = TriggerDetector(DisguiseTrigger.TAP_MESSAGE)
        // Two taps, a pause, then four more: only the fourth after the pause gets in.
        val results = d.run(MESSAGE to 0, MESSAGE to 400, MESSAGE to 3_000, MESSAGE to 3_400, MESSAGE to 3_800, MESSAGE to 4_200)
        assertEquals(listOf(false, false, false, false, false, true), results)
    }

    @Test
    fun `taps at a steady relaxed pace still count`() {
        val d = TriggerDetector(DisguiseTrigger.TAP_MESSAGE)
        val gap = TriggerDetector.MAX_GAP_MS
        assertTrue(d.run(MESSAGE to 0, MESSAGE to gap, MESSAGE to 2 * gap, MESSAGE to 3 * gap).last())
    }

    @Test
    fun `a clock that jumps back starts the count again`() {
        val d = TriggerDetector(DisguiseTrigger.TAP_MESSAGE)
        assertFalse(d.run(MESSAGE to 5_000, MESSAGE to 5_300, MESSAGE to 100, MESSAGE to 400).last())
        assertTrue(d.run(MESSAGE to 700, MESSAGE to 1_000).last())
    }

    @Test
    fun `after getting in, the next try needs the whole move again`() {
        val d = TriggerDetector(DisguiseTrigger.TAP_MESSAGE)
        assertTrue(d.run(MESSAGE to 0, MESSAGE to 100, MESSAGE to 200, MESSAGE to 300).last())
        assertFalse(d.feed(MESSAGE, 400))
    }

    @Test
    fun `only the chosen move works`() {
        // The corner move does nothing when the message move was chosen, and the other way round.
        val message = TriggerDetector(DisguiseTrigger.TAP_MESSAGE)
        assertFalse(message.run(CORNER to 0, CORNER to 100, CORNER to 200, CORNER to 300, VOLUME_DOWN to 400, VOLUME_DOWN to 500, VOLUME_UP to 600).any { it })
        assertFalse(message.held(10_000))
        val corner = TriggerDetector(DisguiseTrigger.CORNER)
        assertFalse(corner.run(MESSAGE to 0, MESSAGE to 100, MESSAGE to 200, MESSAGE to 300).any { it })
        assertTrue(corner.run(CORNER to 1_000, CORNER to 1_500, CORNER to 2_000).last())
    }

    @Test
    fun `taps elsewhere between corner taps don't spoil them`() {
        val d = TriggerDetector(DisguiseTrigger.CORNER)
        assertTrue(d.run(CORNER to 0, MESSAGE to 200, CORNER to 400, CORNER to 800).last())
    }

    @Test
    fun `volume down, down, up, and nothing else`() {
        val d = TriggerDetector(DisguiseTrigger.VOLUME)
        assertTrue(d.run(VOLUME_DOWN to 0, VOLUME_DOWN to 300, VOLUME_UP to 600).last())
        assertFalse(TriggerDetector(DisguiseTrigger.VOLUME).run(VOLUME_DOWN to 0, VOLUME_UP to 300, VOLUME_UP to 600).any { it })
        assertFalse(TriggerDetector(DisguiseTrigger.VOLUME).run(VOLUME_UP to 0, VOLUME_DOWN to 300, VOLUME_DOWN to 600).any { it })
        // An extra press first is forgiven: the last three are what count.
        assertTrue(TriggerDetector(DisguiseTrigger.VOLUME).run(VOLUME_DOWN to 0, VOLUME_DOWN to 200, VOLUME_DOWN to 400, VOLUME_UP to 600).last())
        assertTrue(TriggerDetector(DisguiseTrigger.VOLUME).run(VOLUME_UP to 0, VOLUME_DOWN to 200, VOLUME_DOWN to 400, VOLUME_UP to 600).last())
    }

    @Test
    fun `holding Close app gets in after three seconds, and only for that move`() {
        val hold = TriggerDetector(DisguiseTrigger.HOLD_CLOSE)
        assertFalse(hold.held(TriggerDetector.HOLD_MS - 1))
        assertTrue(hold.held(TriggerDetector.HOLD_MS))
        assertFalse(hold.run(MESSAGE to 0, MESSAGE to 100, MESSAGE to 200, MESSAGE to 300).any { it })
        assertFalse(TriggerDetector(DisguiseTrigger.VOLUME).held(60_000))
    }

    @Test
    fun `an unknown trigger id falls back to the default`() {
        assertEquals(DisguiseTrigger.TAP_MESSAGE, DisguiseTrigger.of("from-a-newer-app"))
        assertEquals(DisguiseTrigger.VOLUME, DisguiseTrigger.of("volume"))
        assertEquals(DisguiseTrigger.entries.size, DisguiseTrigger.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `PIN rules`() {
        assertNull(DisguiseSecret.problem("2468", pin = true))
        assertNull(DisguiseSecret.problem("135790", pin = true))
        assertNotNull(DisguiseSecret.problem("", pin = true))
        assertNotNull(DisguiseSecret.problem("246", pin = true))
        assertNotNull(DisguiseSecret.problem("24a8", pin = true))
        assertNotNull(DisguiseSecret.problem("1234567890123", pin = true))
        for (easy in listOf("0000", "1111", "1234", "4321", "0123", "98765", "123456")) {
            assertNotNull("$easy should be refused", DisguiseSecret.problem(easy, pin = true))
        }
        assertNull(DisguiseSecret.problem("moonlit", pin = false))
        assertNotNull(DisguiseSecret.problem("short", pin = false))
        assertNotNull(DisguiseSecret.problem("      ", pin = false))
    }

    private val fast = KdfParams.FAST_FOR_TESTS

    @Test
    fun `the PIN is kept as a salted hash that only the right PIN matches`() {
        val kept = DisguiseSecret.hash("2468", fast)
        assertFalse(kept.contains("2468"))
        assertTrue(DisguiseSecret.verify("2468", kept))
        assertFalse(DisguiseSecret.verify("2469", kept))
        assertFalse(DisguiseSecret.verify("", kept))
        // A new salt every time: the same PIN never looks the same twice.
        assertNotEquals(kept, DisguiseSecret.hash("2468", fast))
    }

    @Test
    fun `passwords in any script work`() {
        val kept = DisguiseSecret.hash("kahve molası ☕", fast)
        assertTrue(DisguiseSecret.verify("kahve molası ☕", kept))
        assertFalse(DisguiseSecret.verify("kahve molasi ☕", kept))
    }

    @Test
    fun `a damaged or hostile kept value is a no, not a crash or a hang`() {
        val kept = DisguiseSecret.hash("2468", fast)
        val parts = kept.split(':')
        for (bad in listOf(
            "", "2468", "a2id", parts.drop(1).joinToString(":"),
            (listOf("scrypt") + parts.drop(1)).joinToString(":"),
            (parts.take(1) + "x" + parts.drop(2)).joinToString(":"),
            // A cost that would take minutes, or more memory than a phone has.
            (parts.take(1) + "4000000" + parts.drop(2)).joinToString(":"),
            (parts.take(2) + "2147483647" + parts.drop(3)).joinToString(":"),
            (parts.take(3) + "not base64!" + parts.drop(4)).joinToString(":"),
            (parts.take(3) + "AAAA" + parts.drop(4)).joinToString(":"),
            (parts.take(4) + "AAAA").joinToString(":"),
            "$kept:extra",
        )) {
            assertFalse("\"$bad\" should not verify", DisguiseSecret.verify("2468", bad))
        }
    }

    @Test
    fun `wrong guesses make you wait, longer each time, up to a limit`() {
        repeat(DisguiseThrottle.FREE_TRIES) { assertEquals(0L, DisguiseThrottle.waitAfter(it)) }
        assertEquals(30_000L, DisguiseThrottle.waitAfter(5))
        assertEquals(60_000L, DisguiseThrottle.waitAfter(6))
        assertEquals(120_000L, DisguiseThrottle.waitAfter(7))
        assertEquals(15 * 60_000L, DisguiseThrottle.waitAfter(12))
        assertEquals(15 * 60_000L, DisguiseThrottle.waitAfter(Int.MAX_VALUE))
    }

    @Test
    fun `the wait runs down, and a clock set back doesn't end it`() {
        assertEquals(0L, DisguiseThrottle.remaining(4, lastFailAt = 1_000, now = 1_000))
        assertEquals(30_000L, DisguiseThrottle.remaining(5, lastFailAt = 1_000, now = 1_000))
        assertEquals(20_000L, DisguiseThrottle.remaining(5, lastFailAt = 1_000, now = 11_000))
        assertEquals(0L, DisguiseThrottle.remaining(5, lastFailAt = 1_000, now = 100_000))
        assertEquals(30_000L, DisguiseThrottle.remaining(5, lastFailAt = 1_000_000, now = 5_000))
    }
}
