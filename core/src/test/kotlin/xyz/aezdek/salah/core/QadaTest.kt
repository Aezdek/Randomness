package xyz.aezdek.salah.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class QadaTest {
    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun `owed is old misses plus logged misses minus made up`() {
        val data = SalahData()
            .withOwedAdded(mapOf(Prayer.FAJR to 100))
            .withStatus(today.minusDays(2), Prayer.FAJR, PrayerStatus.MISSED)
            .withStatus(today.minusDays(1), Prayer.FAJR, PrayerStatus.MISSED)
            .withStatus(today.minusDays(1), Prayer.ASR, PrayerStatus.PRAYED)
            .withMadeUp(today.minusDays(1), Prayer.FAJR, 5)
            .withMadeUp(today, Prayer.FAJR, 2)

        val fajr = data.qada(today).line(Prayer.FAJR)
        assertEquals(100, fajr.added)
        assertEquals(2, fajr.missedInLog)
        assertEquals(7, fajr.madeUp)
        assertEquals(2, fajr.madeUpToday)
        assertEquals(95, fajr.remaining)
        assertEquals(0, data.qada(today).line(Prayer.ASR).remaining)
    }

    @Test
    fun `remaining never goes negative when a missed day is changed to prayed`() {
        val data = SalahData()
            .withStatus(today, Prayer.ISHA, PrayerStatus.MISSED)
            .withMadeUp(today, Prayer.ISHA, 1)
            .withStatus(today, Prayer.ISHA, PrayerStatus.PRAYED)
        assertEquals(0, data.qada(today).remaining)
        assertEquals(0f, data.qada(today).progress)
    }

    @Test
    fun `pace averages from the first made up day inside the window`() {
        val data = SalahData()
            .withOwedAdded(Prayer.entries.associateWith { 100 })
            .withMadeUp(today.minusDays(2), Prayer.FAJR, 6)
            .withMadeUp(today, Prayer.DHUHR, 6)
        val summary = data.qada(today)
        assertEquals(4.0, summary.pacePerDay, 0.0001)
        assertEquals(488, summary.remaining)
        assertEquals(122, summary.daysLeft)
    }

    @Test
    fun `pace only looks at the last thirty days`() {
        val data = SalahData()
            .withOwedAdded(mapOf(Prayer.FAJR to 100))
            .withMadeUp(today.minusDays(200), Prayer.FAJR, 50)
            .withMadeUp(today, Prayer.FAJR, 30)
        assertEquals(1.0, data.qada(today).pacePerDay, 0.0001)
    }

    @Test
    fun `no estimate without a recent pace`() {
        val data = SalahData().withOwedAdded(mapOf(Prayer.FAJR to 10))
        assertNull(data.qada(today).daysLeft)
    }
}
