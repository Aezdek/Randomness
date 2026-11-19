package xyz.aezdek.daybook.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.Task

fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

/** The Monday of this date's week. */
fun LocalDate.weekStart(): LocalDate = minus(dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

fun LocalDate.weekDays(): List<LocalDate> = weekStart().let { start -> (0..6).map { start.plusDays(it) } }

/** Tasks planned for [date], in the order they were added. */
fun List<Task>.on(date: LocalDate): List<Task> = filter { it.date == date }.sortedWith(compareBy({ it.createdAt }, { it.id }))

/** Unfinished tasks from days before [today], oldest first. They show at the top of today. */
fun List<Task>.carriedOver(today: LocalDate): List<Task> =
    filter { !it.done && it.date < today }.sortedWith(compareBy({ it.date }, { it.createdAt }, { it.id }))

data class Tally(val done: Int, val total: Int, val minutesDone: Int, val minutesPlanned: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total

    companion object {
        val Empty = Tally(0, 0, 0, 0)
    }
}

fun List<Task>.tally(): Tally = Tally(
    done = count { it.done },
    total = size,
    minutesDone = filter { it.done }.sumOf { it.minutes },
    minutesPlanned = sumOf { it.minutes },
)

/** Totals for the week containing [anyDay], per subject and overall. */
data class WeekSummary(val start: LocalDate, val bySubject: Map<Subject, Tally>, val overall: Tally)

fun List<Task>.weekSummary(anyDay: LocalDate): WeekSummary {
    val start = anyDay.weekStart()
    val end = start.plusDays(6)
    val week = filter { it.date >= start && it.date <= end }
    return WeekSummary(
        start = start,
        bySubject = Subject.entries.associateWith { subject -> week.filter { it.subject == subject }.tally() },
        overall = week.tally(),
    )
}

/**
 * Days in a row, counting back from [today], on which every planned task was ticked. Days with nothing
 * planned are skipped. Today counts once finished, and doesn't break the streak while still in progress.
 */
fun List<Task>.streak(today: LocalDate): Int {
    if (isEmpty()) return 0
    val byDate = groupBy { it.date }
    val earliest = byDate.keys.min()
    var count = 0
    var day = today
    while (day >= earliest) {
        val tasks = byDate[day]
        if (!tasks.isNullOrEmpty()) {
            when {
                tasks.all { it.done } -> count++
                day != today -> return count
            }
        }
        day = day.plusDays(-1)
    }
    return count
}

data class HeatCell(val date: LocalDate, val done: Int, val isFuture: Boolean, val isToday: Boolean) {
    /** 0 (nothing ticked) to 4 (seven or more ticked). */
    val level: Int get() = when {
        done <= 0 -> 0
        done <= 2 -> 1
        done <= 4 -> 2
        done <= 6 -> 3
        else -> 4
    }
}

/** [weeks] whole weeks ending with the current one, Monday first, column by column. */
fun List<Task>.heatmap(today: LocalDate, weeks: Int = 12): List<HeatCell> {
    val doneByDate = filter { it.done }.groupingBy { it.date }.eachCount()
    val start = today.weekStart().plusDays(-7 * (weeks - 1))
    return (0 until weeks * 7).map { offset ->
        val date = start.plusDays(offset)
        HeatCell(date, doneByDate[date] ?: 0, isFuture = date > today, isToday = date == today)
    }
}

fun daysUntil(today: LocalDate, target: LocalDate): Int = today.daysUntil(target)
