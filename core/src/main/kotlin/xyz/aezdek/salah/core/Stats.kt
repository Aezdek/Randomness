package xyz.aezdek.salah.core

import java.time.LocalDate

data class DaySummary(val prayed: Int, val missed: Int) {
    val logged: Int get() = prayed + missed
    val allPrayed: Boolean get() = prayed == Prayer.entries.size
}

fun SalahData.day(date: LocalDate): DaySummary {
    val day = log[date].orEmpty()
    return DaySummary(day.count { it.value == PrayerStatus.PRAYED }, day.count { it.value == PrayerStatus.MISSED })
}

fun SalahData.unlogged(date: LocalDate): List<Prayer> = Prayer.entries.filter { statusOf(date, it) == null }

/**
 * Days in a row, ending today, on which all five prayers were prayed. A today that is still being
 * logged doesn't break the streak; a prayer marked missed today does.
 */
fun SalahData.streak(today: LocalDate): Int {
    val todaySummary = day(today)
    if (todaySummary.missed > 0) return 0
    var date = if (todaySummary.allPrayed) today else today.minusDays(1)
    var days = 0
    while (day(date).allPrayed) {
        days++
        date = date.minusDays(1)
    }
    return days
}

data class PrayerTally(val prayer: Prayer, val prayed: Int, val missed: Int)

data class PeriodSummary(val prayed: Int, val missed: Int, val daysAllPrayed: Int, val perPrayer: List<PrayerTally>) {
    /** Share of logged prayers that were prayed, or null with nothing logged. */
    val prayedShare: Float? get() = if (prayed + missed == 0) null else prayed.toFloat() / (prayed + missed)
}

/** Totals for the days from [from] to [to], both included. */
fun SalahData.summary(from: LocalDate, to: LocalDate): PeriodSummary {
    val days = log.filterKeys { it in from..to }.values
    val perPrayer = Prayer.entries.map { prayer ->
        PrayerTally(
            prayer,
            prayed = days.count { it[prayer] == PrayerStatus.PRAYED },
            missed = days.count { it[prayer] == PrayerStatus.MISSED },
        )
    }
    return PeriodSummary(
        prayed = perPrayer.sumOf { it.prayed },
        missed = perPrayer.sumOf { it.missed },
        daysAllPrayed = days.count { day -> Prayer.entries.all { day[it] == PrayerStatus.PRAYED } },
        perPrayer = perPrayer,
    )
}
