package xyz.aezdek.salah.core

import java.time.LocalTime
import java.time.ZonedDateTime

/** When a daily reminder set for [minuteOfDay] (minutes after midnight) next goes off after [now]. */
fun nextReminder(minuteOfDay: Int, now: ZonedDateTime): ZonedDateTime {
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    val today = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
    return if (today.isAfter(now)) today else ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.zone)
}
