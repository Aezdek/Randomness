package xyz.aezdek.hush.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aezdek.hush.client.LocalDb
import xyz.aezdek.hush.client.Pin

/** The SQLite store behind the local cache (no crypto involved, so it runs under Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SqliteTablesTest {
    private val tables = SqliteTables(ApplicationProvider.getApplicationContext(), "test-${System.nanoTime()}.db")

    @Test
    fun putGetReplaceDelete() {
        tables.put(LocalDb.OBJECTS, "a", "1")
        tables.put(LocalDb.OBJECTS, "b", "2")
        tables.put(LocalDb.OBJECTS, "a", "3")
        assertEquals("3", tables.get(LocalDb.OBJECTS, "a"))
        assertEquals(setOf("2", "3"), tables.all(LocalDb.OBJECTS).toSet())
        tables.delete(LocalDb.OBJECTS, "a")
        assertNull(tables.get(LocalDb.OBJECTS, "a"))
    }

    @Test
    fun transactionsAndClear() {
        tables.transaction {
            for (i in 0 until 100) tables.put(LocalDb.USERS, "u$i", "v$i")
        }
        assertEquals(100, tables.all(LocalDb.USERS).size)
        tables.clear()
        assertTrue(LocalDb.TABLES.all { tables.all(it).isEmpty() })
    }

    @Test
    fun localDbRoundTripsCursorAndPins() {
        val db = LocalDb(tables)
        db.setCursor(42)
        db.setPins(mapOf("u1" to Pin("pk", 1, verified = true)))
        assertEquals(42L, LocalDb(tables).cursor())
        assertEquals(true, LocalDb(tables).pins()["u1"]?.verified)
    }
}
