package xyz.aezdek.salah.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupTest {
    private val data = SalahData()
        .withStatus(LocalDate.of(2026, 10, 21), Prayer.FAJR, PrayerStatus.MISSED)
        .withRestPrayed(LocalDate.of(2026, 10, 21))
        .withOwedAdded(mapOf(Prayer.ISHA to 40))
        .withMadeUp(LocalDate.of(2026, 10, 22), Prayer.ISHA, 3)
        .withSettings { it.copy(reminderEnabled = true, hijriOffset = -1, theme = ThemeMode.DARK) }

    @Test
    fun `round trips through a backup file`() {
        val text = Backup.encode(data, "2026-10-22T20:00")
        assertTrue(text.contains("\"2026-10-21\""))
        assertEquals(data, Backup.decode(text))
    }

    @Test
    fun `round trips through the stored form`() {
        val text = SalahJson.encodeToString(SalahData.serializer(), data)
        assertEquals(data, SalahJson.decodeFromString(SalahData.serializer(), text))
    }

    @Test
    fun `rejects files that are not backups`() {
        val error = assertThrows(IllegalArgumentException::class.java) { Backup.decode("""{"hello": 1}""") }
        assertEquals("This isn't a Salah backup file.", error.message)
        assertThrows(IllegalArgumentException::class.java) {
            Backup.decode("""{"format":"something.else","version":1,"exportedAt":"","data":{}}""")
        }
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("not json") }
    }

    @Test
    fun `rejects newer backups and drops negative counts`() {
        assertThrows(IllegalArgumentException::class.java) {
            Backup.decode("""{"format":"${Backup.FORMAT}","version":99,"exportedAt":"","data":{}}""")
        }
        val read = Backup.decode(
            """{"format":"${Backup.FORMAT}","version":1,"exportedAt":"","data":{"owed":{"FAJR":-4,"ASR":2},"madeUp":{"2026-10-01":{"ASR":-1}}}}""",
        )
        assertEquals(mapOf(Prayer.ASR to 2), read.owed)
        assertTrue(read.madeUp.isEmpty())
    }
}
