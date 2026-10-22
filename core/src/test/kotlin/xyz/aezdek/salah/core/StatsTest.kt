package xyz.aezdek.salah.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    private val today = LocalDate.of(2026, 10, 22)

    private fun SalahData.allPrayed(vararg daysAgo: Long) =
        daysAgo.fold(this) { data, ago -> data.withRestPrayed(today.minusDays(ago)) }

    @Test
    fun `an unfinished today keeps yesterday's streak`() {
        val data = SalahData().allPrayed(1, 2, 3, 5).withStatus(today, Prayer.FAJR, PrayerStatus.PRAYED)
        assertEquals(3, data.streak(today))
    }

    @Test
    fun `a finished today counts`() {
        assertEquals(4, SalahData().allPrayed(0, 1, 2, 3).streak(today))
    }

    @Test
    fun `a missed prayer today ends the streak`() {
        val data = SalahData().allPrayed(1, 2).withStatus(today, Prayer.MAGHRIB, PrayerStatus.MISSED)
        assertEquals(0, data.streak(today))
    }

    @Test
    fun `summary counts only days in range`() {
        val data = SalahData()
            .allPrayed(0, 1)
            .withStatus(today.minusDays(2), Prayer.FAJR, PrayerStatus.MISSED)
            .withStatus(today.minusDays(40), Prayer.FAJR, PrayerStatus.MISSED)
        val summary = data.summary(today.minusDays(29), today)
        assertEquals(10, summary.prayed)
        assertEquals(1, summary.missed)
        assertEquals(2, summary.daysAllPrayed)
        assertEquals(1, summary.perPrayer.first { it.prayer == Prayer.FAJR }.missed)
        assertEquals(10f / 11f, summary.prayedShare!!, 0.0001f)
        assertNull(SalahData().summary(today, today).prayedShare)
    }

    @Test
    fun `unlogged lists prayers with nothing logged`() {
        val data = SalahData()
            .withStatus(today, Prayer.FAJR, PrayerStatus.PRAYED)
            .withStatus(today, Prayer.ASR, PrayerStatus.MISSED)
        assertEquals(listOf(Prayer.DHUHR, Prayer.MAGHRIB, Prayer.ISHA), data.unlogged(today))
    }
}
