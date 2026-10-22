@file:UseSerializers(LocalDateSerializer::class)

package xyz.aezdek.salah.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/**
 * Everything the app stores. It is small (five entries a day), so it is kept and saved as one value.
 *
 * Qada owed for a prayer = [owed] (old misses the user added) + days it is marked missed in [log]
 * − times it was made up ([madeUp]).
 */
@Serializable
data class SalahData(
    /** What was logged each day. Days with nothing logged are left out. */
    val log: Map<LocalDate, Map<Prayer, PrayerStatus>> = emptyMap(),
    /** Missed prayers from before this log, added by the user. */
    val owed: Map<Prayer, Int> = emptyMap(),
    /** Qada prayers made up, by the day they were made up. */
    val madeUp: Map<LocalDate, Map<Prayer, Int>> = emptyMap(),
    val settings: Settings = Settings(),
)

@Serializable
data class Settings(
    val reminderEnabled: Boolean = false,
    /** Minutes after midnight. */
    val reminderMinute: Int = 21 * 60 + 30,
    /** Days added to the calculated Hijri date, to match the local moon sighting. */
    val hijriOffset: Int = 0,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val wallpaperColors: Boolean = false,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** ISO dates ("2026-10-22"), so logged days are readable map keys in the JSON. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("xyz.aezdek.salah.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
