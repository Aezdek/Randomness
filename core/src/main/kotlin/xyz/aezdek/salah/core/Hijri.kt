package xyz.aezdek.salah.core

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

object Hijri {
    private val months = listOf(
        "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Jumada al-Ula", "Jumada al-Akhirah",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qa'dah", "Dhu al-Hijjah",
    )

    /**
     * The Umm al-Qura date for [date] moved by [offsetDays], like "30 Rabi' al-Thani 1448 AH", or null when the
     * date is outside the calendar's range or the platform has no Hijri calendar.
     */
    fun format(date: LocalDate, offsetDays: Int = 0): String? = try {
        val hijri = HijrahDate.from(date.plusDays(offsetDays.toLong()))
        "${hijri.get(ChronoField.DAY_OF_MONTH)} ${months[hijri.get(ChronoField.MONTH_OF_YEAR) - 1]} ${hijri.get(ChronoField.YEAR)} AH"
    } catch (e: RuntimeException) {
        null
    }

    fun isRamadan(date: LocalDate, offsetDays: Int = 0): Boolean = try {
        HijrahDate.from(date.plusDays(offsetDays.toLong())).get(ChronoField.MONTH_OF_YEAR) == 9
    } catch (e: RuntimeException) {
        false
    }
}
