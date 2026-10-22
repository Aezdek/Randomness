package xyz.aezdek.salah.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HijriTest {
    @Test
    fun `formats the Umm al-Qura date`() {
        // Ramadan 1446 began on 1 March 2025 in the Umm al-Qura calendar, after a 29-day Sha'ban.
        assertEquals("1 Ramadan 1446 AH", Hijri.format(LocalDate.of(2025, 3, 1)))
        assertEquals("29 Sha'ban 1446 AH", Hijri.format(LocalDate.of(2025, 3, 1), offsetDays = -1))
        assertTrue(Hijri.isRamadan(LocalDate.of(2025, 3, 15)))
        assertFalse(Hijri.isRamadan(LocalDate.of(2025, 4, 15)))
    }

    @Test
    fun `dates outside the calendar give null`() {
        assertNull(Hijri.format(LocalDate.of(1700, 1, 1)))
    }
}
