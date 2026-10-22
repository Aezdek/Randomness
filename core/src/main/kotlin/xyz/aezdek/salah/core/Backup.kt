package xyz.aezdek.salah.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How [SalahData] is stored on the device. Unknown keys are skipped so older apps can read newer files. */
val SalahJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The export file: the stored data with a marker, so a random JSON file isn't mistaken for a backup. */
object Backup {
    const val FORMAT = "xyz.aezdek.salah.backup"
    const val VERSION = 1

    @Serializable
    private data class BackupFile(val format: String, val version: Int, val exportedAt: String, val data: SalahData)

    private val json = Json(SalahJson) { prettyPrint = true }

    fun encode(data: SalahData, exportedAt: String): String =
        json.encodeToString(BackupFile.serializer(), BackupFile(FORMAT, VERSION, exportedAt, data))

    /** Reads a backup, or throws [IllegalArgumentException] with a message to show. */
    fun decode(text: String): SalahData {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("This isn't a Salah backup file.", e)
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("This backup has a date that can't be read.", e)
        }
        require(file.format == FORMAT) { "This isn't a Salah backup file." }
        require(file.version <= VERSION) { "This backup is from a newer version of Salah. Update the app first." }
        // Counts can't be negative; drop anything a hand-edited file got wrong.
        return file.data.copy(
            owed = file.data.owed.filterValues { it > 0 },
            madeUp = file.data.madeUp.mapValues { day -> day.value.filterValues { it > 0 } }.filterValues { it.isNotEmpty() },
        )
    }
}
