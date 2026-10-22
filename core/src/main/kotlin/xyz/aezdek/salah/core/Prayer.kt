package xyz.aezdek.salah.core

import kotlinx.serialization.Serializable

/** The five daily prayers, in the order they are prayed. */
@Serializable
enum class Prayer(val title: String, val arabic: String) {
    FAJR("Fajr", "الفجر"),
    DHUHR("Dhuhr", "الظهر"),
    ASR("Asr", "العصر"),
    MAGHRIB("Maghrib", "المغرب"),
    ISHA("Isha", "العشاء"),
}

/** What was logged for one prayer. A prayer with nothing logged has no status. */
@Serializable
enum class PrayerStatus { PRAYED, MISSED }
