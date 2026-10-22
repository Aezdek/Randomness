package xyz.aezdek.salah.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

data class QadaLine(
    val prayer: Prayer,
    /** Old misses the user added. */
    val added: Int,
    /** Days this prayer is marked missed in the log. */
    val missedInLog: Int,
    val madeUp: Int,
    val madeUpToday: Int,
) {
    val owed: Int get() = added + missedInLog
    val remaining: Int get() = (owed - madeUp).coerceAtLeast(0)
}

data class QadaSummary(
    val lines: List<QadaLine>,
    /** Qada prayers made up per day, averaged over the last [PACE_WINDOW_DAYS] days. */
    val pacePerDay: Double,
) {
    val owed: Int get() = lines.sumOf { it.owed }
    val madeUp: Int get() = lines.sumOf { it.madeUp }
    val remaining: Int get() = lines.sumOf { it.remaining }
    val madeUpToday: Int get() = lines.sumOf { it.madeUpToday }

    /** Share of everything owed that has been made up, 0 to 1. */
    val progress: Float get() = if (owed == 0) 0f else (madeUp.toFloat() / owed).coerceIn(0f, 1f)

    /** Days left at the recent pace, or null when nothing is left or nothing was made up recently. */
    val daysLeft: Int? get() = if (remaining == 0 || pacePerDay <= 0.0) null else ceil(remaining / pacePerDay).toInt()

    fun line(prayer: Prayer): QadaLine = lines.first { it.prayer == prayer }
}

const val PACE_WINDOW_DAYS = 30

fun SalahData.qada(today: LocalDate): QadaSummary {
    val missed = Prayer.entries.associateWith { 0 }.toMutableMap()
    log.values.forEach { day -> day.forEach { (prayer, status) -> if (status == PrayerStatus.MISSED) missed[prayer] = missed.getValue(prayer) + 1 } }
    val made = Prayer.entries.associateWith { 0 }.toMutableMap()
    madeUp.values.forEach { day -> day.forEach { (prayer, count) -> made[prayer] = made.getValue(prayer) + count } }
    val madeUpToday = madeUp[today].orEmpty()
    val lines = Prayer.entries.map {
        QadaLine(it, owed[it] ?: 0, missed.getValue(it), made.getValue(it), madeUpToday[it] ?: 0)
    }
    return QadaSummary(lines, pace(today))
}

/**
 * Made-up prayers per day over the last [PACE_WINDOW_DAYS] days. When the user only started making up
 * prayers within that window, the average runs from their first day, so a fresh start isn't diluted.
 */
private fun SalahData.pace(today: LocalDate): Double {
    val windowStart = today.minusDays(PACE_WINDOW_DAYS - 1L)
    val first = madeUp.keys.minOrNull() ?: return 0.0
    val start = maxOf(first, windowStart)
    if (start > today) return 0.0
    val total = madeUp.filterKeys { it in start..today }.values.sumOf { it.values.sum() }
    return total.toDouble() / (ChronoUnit.DAYS.between(start, today) + 1)
}
