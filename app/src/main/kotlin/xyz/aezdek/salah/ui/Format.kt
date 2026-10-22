package xyz.aezdek.salah.ui

import xyz.aezdek.salah.core.ThemeMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

val LongDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
val MonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
val MonthName: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM")
private val Weekday = DateTimeFormatter.ofPattern("EEEE")
private val DayMonth = DateTimeFormatter.ofPattern("d MMMM")
private val DayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy")

/** "Today", "Yesterday", the weekday for the last week, then the date. */
fun dayTitle(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date > today.minusDays(7) && date < today -> date.format(Weekday)
    date.year == today.year -> date.format(DayMonth)
    else -> date.format(DayMonthYear)
}

fun Int.grouped(): String = NumberFormat.getIntegerInstance().format(this)

fun plural(count: Int, one: String, many: String = one + "s"): String = "${count.grouped()} ${if (count == 1) one else many}"

/** A rough length of time for a number of days, like "3 weeks" or "1.5 years". */
fun approxDuration(days: Int): String = when {
    days < 14 -> plural(days, "day")
    days < 60 -> plural((days / 7.0).roundToInt(), "week")
    days < 730 -> plural((days / 30.44).roundToInt(), "month")
    else -> "${String.format(Locale.getDefault(), "%.1f", days / 365.25)} years"
}

/** "4", or "2.5" below ten a day. */
fun formatPace(perDay: Double): String =
    if (perDay >= 9.95) perDay.roundToInt().toString()
    else String.format(Locale.getDefault(), "%.1f", perDay).removeSuffix(".0").removeSuffix(",0")

fun formatTime(minuteOfDay: Int, is24Hour: Boolean): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a"))

val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }
