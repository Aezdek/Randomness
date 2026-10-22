package xyz.aezdek.salah.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EstimateTest {
    @Test
    fun `periods count calendar days`() {
        val end = LocalDate.of(2026, 10, 22)
        assertEquals(10, daysIn(10, PeriodUnit.DAYS, end))
        assertEquals(14, daysIn(2, PeriodUnit.WEEKS, end))
        assertEquals(30, daysIn(1, PeriodUnit.MONTHS, end))
        assertEquals(365, daysIn(1, PeriodUnit.YEARS, end))
        assertEquals(366, daysIn(1, PeriodUnit.YEARS, LocalDate.of(2028, 3, 1)))
        assertEquals(0, daysIn(-3, PeriodUnit.YEARS, end))
    }
}
