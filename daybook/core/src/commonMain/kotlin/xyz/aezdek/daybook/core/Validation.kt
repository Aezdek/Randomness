package xyz.aezdek.daybook.core

private val ID = Regex("^[A-Za-z0-9_-]{1,${Limits.MAX_ID}}$")
private val SUBJECT = Regex("^[a-z0-9_]{1,${Limits.MAX_SUBJECT}}$")
private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
private val SETTING_KEY = Regex("^[A-Za-z0-9_.-]{1,${Limits.MAX_SETTING_KEY}}$")

fun isValidId(id: String): Boolean = ID.matches(id)

/** A calendar date in yyyy-MM-dd form. Rejects impossible days such as 2026-02-30. */
fun isIsoDate(value: String): Boolean {
    val match = ISO_DATE.matchEntire(value) ?: return false
    val (y, m, d) = match.destructured.toList().map { it.toInt() }
    if (y < 1970 || y > 9999 || m !in 1..12 || d < 1) return false
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val days = when (m) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return d <= days
}

/** Why [this] can't be stored, or null when it is fine. */
fun TaskRecord.problem(): String? = when {
    !isValidId(id) -> "bad id"
    !isValidId(updatedBy) -> "bad updatedBy"
    !SUBJECT.matches(subject) -> "bad subject"
    !deleted && title.isBlank() -> "empty title"
    title.length > Limits.MAX_TITLE -> "title too long"
    paper.length > Limits.MAX_PAPER -> "paper too long"
    minutes !in 0..Limits.MAX_MINUTES -> "minutes out of range"
    !isIsoDate(date) -> "bad date"
    createdAt < 0 || updatedAt < 0 -> "bad timestamp"
    else -> null
}

fun SettingRecord.problem(): String? = when {
    !SETTING_KEY.matches(key) -> "bad key"
    !isValidId(updatedBy) -> "bad updatedBy"
    value.length > Limits.MAX_SETTING_VALUE -> "value too long"
    updatedAt < 0 -> "bad timestamp"
    else -> null
}
