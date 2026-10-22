package xyz.aezdek.salah.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class PeriodUnit(val label: String) { DAYS("Days"), WEEKS("Weeks"), MONTHS("Months"), YEARS("Years") }

/** The number of days in [amount] [unit]s ending on [end]. Months and years are calendar ones (a year is 365 or 366 days). */
fun daysIn(amount: Int, unit: PeriodUnit, end: LocalDate): Int {
    if (amount <= 0) return 0
    val start = when (unit) {
        PeriodUnit.DAYS -> return amount
        PeriodUnit.WEEKS -> return amount * 7
        PeriodUnit.MONTHS -> end.minusMonths(amount.toLong())
        PeriodUnit.YEARS -> end.minusYears(amount.toLong())
    }
    return ChronoUnit.DAYS.between(start, end).toInt()
}
