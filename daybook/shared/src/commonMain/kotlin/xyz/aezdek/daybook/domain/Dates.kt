package xyz.aezdek.daybook.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

val DayOfWeek.longName: String get() = WEEKDAYS[ordinal]
val DayOfWeek.shortName: String get() = WEEKDAYS[ordinal].take(3)

/** One letter, as in a calendar header. */
val DayOfWeek.initial: String get() = WEEKDAYS[ordinal].take(1)

val LocalDate.monthName: String get() = MONTHS[month.number - 1]

/** "Thu 19 Nov" */
fun LocalDate.short(): String = "${dayOfWeek.shortName} $day ${monthName.take(3)}"

/** "Thursday 19 November" */
fun LocalDate.long(): String = "${dayOfWeek.longName} $day $monthName"

/** "19 November 2026" */
fun LocalDate.withYear(): String = "$day $monthName $year"

/** "November 2026" */
fun LocalDate.monthAndYear(): String = "$monthName $year"

/** "Today", "Tomorrow", "Yesterday", or the weekday name. */
fun LocalDate.relativeName(today: LocalDate): String = when (this) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.plusDays(-1) -> "Yesterday"
    else -> dayOfWeek.longName
}

/** "45 min", "1 hr", "1 hr 30 min" */
fun formatMinutes(minutes: Int): String {
    if (minutes <= 0) return ""
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h hr"
        else -> "$h hr $m min"
    }
}

/** "2h 15m", for tight spaces. */
fun formatMinutesShort(minutes: Int): String {
    if (minutes <= 0) return "0m"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** "just now", "4 min ago", "at 14:05", "on 17 Nov" */
fun formatLastSync(epochMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - epochMillis).coerceAtLeast(0)
    val zone = TimeZone.currentSystemDefault()
    val then = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
    return when {
        elapsed < 60_000 -> "just now"
        elapsed < 60 * 60_000 -> "${elapsed / 60_000} min ago"
        then.date == now.date -> "at ${then.hour.toString().padStart(2, '0')}:${then.minute.toString().padStart(2, '0')}"
        else -> "on ${then.date.day} ${then.date.monthName.take(3)}"
    }
}
