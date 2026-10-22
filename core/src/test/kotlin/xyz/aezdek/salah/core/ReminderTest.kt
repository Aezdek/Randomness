package xyz.aezdek.salah.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

    @Test
    fun `goes off later today, or tomorrow once the time has passed`() {
        val nineThirty = 21 * 60 + 30
        assertEquals(at(22, 21, 30), nextReminder(nineThirty, at(22, 20, 0)))
        assertEquals(at(23, 21, 30), nextReminder(nineThirty, at(22, 21, 30)))
        assertEquals(at(23, 21, 30), nextReminder(nineThirty, at(22, 23, 59)))
    }

    @Test
    fun `keeps the wall clock time across a clock change`() {
        // The clocks go back in London on 25 October 2026.
        assertEquals(at(25, 21, 30), nextReminder(21 * 60 + 30, at(24, 22, 0)))
    }
}
