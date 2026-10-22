package xyz.aezdek.salah.core

import java.time.LocalDate

fun SalahData.statusOf(date: LocalDate, prayer: Prayer): PrayerStatus? = log[date]?.get(prayer)

/** Sets (or with null, clears) what was logged for [prayer] on [date]. */
fun SalahData.withStatus(date: LocalDate, prayer: Prayer, status: PrayerStatus?): SalahData {
    val day = log[date].orEmpty().toMutableMap()
    if (status == null) day.remove(prayer) else day[prayer] = status
    return copy(log = log.withEntry(date, day))
}

/** Marks every prayer of [date] that has nothing logged as prayed. Prayers marked missed stay missed. */
fun SalahData.withRestPrayed(date: LocalDate): SalahData {
    val day = log[date].orEmpty().toMutableMap()
    Prayer.entries.forEach { day.putIfAbsent(it, PrayerStatus.PRAYED) }
    return copy(log = log.withEntry(date, day))
}

/** Adds old missed prayers (negative counts take them away, down to zero). */
fun SalahData.withOwedAdded(counts: Map<Prayer, Int>): SalahData =
    withOwed(Prayer.entries.associateWith { (owed[it] ?: 0) + (counts[it] ?: 0) })

/** Replaces the old missed prayer counts. */
fun SalahData.withOwed(counts: Map<Prayer, Int>): SalahData =
    copy(owed = counts.mapValues { it.value.coerceAtLeast(0) }.filterValues { it > 0 })

/** Records [delta] qada prayers of [prayer] made up on [date] (negative to undo; never below zero for that day). */
fun SalahData.withMadeUp(date: LocalDate, prayer: Prayer, delta: Int): SalahData {
    val day = madeUp[date].orEmpty().toMutableMap()
    val count = ((day[prayer] ?: 0) + delta).coerceAtLeast(0)
    if (count == 0) day.remove(prayer) else day[prayer] = count
    return copy(madeUp = madeUp.withEntry(date, day))
}

fun SalahData.withSettings(transform: (Settings) -> Settings): SalahData = copy(settings = transform(settings))

private fun <V> Map<LocalDate, Map<Prayer, V>>.withEntry(date: LocalDate, day: Map<Prayer, V>): Map<LocalDate, Map<Prayer, V>> =
    toMutableMap().apply { if (day.isEmpty()) remove(date) else put(date, day) }
