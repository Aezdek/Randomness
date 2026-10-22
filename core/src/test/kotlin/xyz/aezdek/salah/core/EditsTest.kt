package xyz.aezdek.salah.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class EditsTest {
    private val day = LocalDate.of(2026, 10, 22)

    @Test
    fun `setting and clearing a status leaves no empty day behind`() {
        val prayed = SalahData().withStatus(day, Prayer.FAJR, PrayerStatus.PRAYED)
        assertEquals(PrayerStatus.PRAYED, prayed.statusOf(day, Prayer.FAJR))

        val cleared = prayed.withStatus(day, Prayer.FAJR, null)
        assertNull(cleared.statusOf(day, Prayer.FAJR))
        assertFalse(day in cleared.log)
    }

    @Test
    fun `marking the rest prayed keeps missed prayers missed`() {
        val data = SalahData().withStatus(day, Prayer.ASR, PrayerStatus.MISSED).withRestPrayed(day)
        assertEquals(PrayerStatus.MISSED, data.statusOf(day, Prayer.ASR))
        assertEquals(4, data.day(day).prayed)
        assertEquals(1, data.day(day).missed)
    }

    @Test
    fun `old missed prayers never go below zero`() {
        val data = SalahData()
            .withOwedAdded(mapOf(Prayer.FAJR to 10, Prayer.ISHA to 3))
            .withOwedAdded(mapOf(Prayer.ISHA to -5))
        assertEquals(mapOf(Prayer.FAJR to 10), data.owed)
    }

    @Test
    fun `undoing a made up prayer stops at zero for that day`() {
        val data = SalahData()
            .withMadeUp(day, Prayer.DHUHR, 1)
            .withMadeUp(day, Prayer.DHUHR, -1)
            .withMadeUp(day, Prayer.DHUHR, -1)
        assertFalse(day in data.madeUp)
    }
}
