package xyz.aezdek.daybook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    private fun task(updatedAt: Long, by: String = "phone", title: String = "Vectors") = TaskRecord(
        id = "t1", title = title, subject = "maths", paper = "P3", minutes = 45, date = "2026-11-19",
        createdAt = 1, updatedAt = updatedAt, updatedBy = by,
    )

    @Test
    fun laterStampWins() {
        assertTrue(task(200).supersedes(task(100)))
        assertFalse(task(100).supersedes(task(200)))
    }

    @Test
    fun equalStampsBreakTiesByDeviceTheSameWayEverywhere() {
        val a = task(100, by = "aaa")
        val b = task(100, by = "bbb")
        assertTrue(b.supersedes(a))
        assertFalse(a.supersedes(b))
        assertFalse(a.supersedes(a))
    }

    @Test
    fun editStampNeverFallsBehindTheVersionItReplaces() {
        assertEquals(1_000, nextStamp(now = 1_000, previous = null))
        assertEquals(5_001, nextStamp(now = 1_000, previous = 5_000), "slow clock still moves forward")
        assertEquals(9_000, nextStamp(now = 9_000, previous = 5_000))
    }

    @Test
    fun validRecordHasNoProblem() {
        assertNull(task(1).problem())
    }

    @Test
    fun malformedRecordsAreCaught() {
        assertNotNull(task(1).copy(id = "has space").problem())
        assertNotNull(task(1).copy(title = "  ").problem())
        assertNotNull(task(1).copy(date = "2026-02-30").problem())
        assertNotNull(task(1).copy(minutes = -5).problem())
        assertNotNull(task(1).copy(subject = "Maths!").problem())
        assertNull(task(1).copy(title = "", deleted = true).problem(), "a tombstone may lose its title")
    }

    @Test
    fun isoDates() {
        assertTrue(isIsoDate("2028-02-29"))
        assertFalse(isIsoDate("2027-02-29"))
        assertFalse(isIsoDate("2026-13-01"))
        assertFalse(isIsoDate("19-11-2026"))
    }

    @Test
    fun unknownFieldsFromNewerPeersAreIgnored() {
        val json = """{"id":"t1","title":"x","subject":"cs","date":"2026-11-19","createdAt":1,"updatedAt":2,"updatedBy":"d","colour":"red"}"""
        val record = DaybookJson.decodeFromString(TaskRecord.serializer(), json)
        assertEquals("x", record.title)
    }
}
