package xyz.aezdek.daybook.sync

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.TaskDraft
import xyz.aezdek.daybook.server.ServerStore
import xyz.aezdek.daybook.server.daybookModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwoDeviceSyncTest {

    private val token = "test-token-0123456789abcdef"
    private val config = SyncConfig(serverUrl = "http://localhost", token = token)
    private val day = LocalDate(2026, 11, 19)

    private class MemoryConfigStore(private var sync: SyncConfig) : ConfigStore {
        private var appearance = Appearance.System
        override fun loadSync() = sync
        override fun saveSync(config: SyncConfig) { sync = config }
        override fun loadAppearance() = appearance
        override fun saveAppearance(appearance: Appearance) { this.appearance = appearance }
    }

    /** A device with its own database and its own clock, which tests move by hand. */
    private class Device(val app: Daybook, val clock: Clock) {
        val tasks get() = app.tasks
        suspend fun sync() = assertTrue(app.sync.syncNow(), "sync failed: ${app.sync.status.value}")
        suspend fun all() = app.tasks.tasks.first()
    }

    private class Clock(var now: Long)

    private fun twoDevices(block: suspend (phone: Device, laptop: Device) -> Unit) = testApplication {
        val server = ServerStore.open(":memory:")
        application { daybookModule(token, server, waitMillis = 1_000) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val phone = device(client, scope, start = 1_000_000)
            val laptop = device(client, scope, start = 1_000_000)
            block(phone, laptop)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun device(http: HttpClient, scope: CoroutineScope, start: Long): Device {
        val clock = Clock(start)
        val app = Daybook.open(
            driver = BundledSQLiteDriver(),
            databasePath = ":memory:",
            dbDispatcher = Dispatchers.IO.limitedParallelism(1),
            http = http,
            configStore = MemoryConfigStore(config),
            scope = scope,
            clock = { clock.now },
        )
        return Device(app, clock)
    }

    private fun draft(title: String, subject: Subject = Subject.MATHS) =
        TaskDraft(title = title, subject = subject, paper = "P3", minutes = 45, date = day)

    @Test
    fun aTaskAddedOnThePhoneAppearsOnTheLaptop() = twoDevices { phone, laptop ->
        phone.tasks.add(draft("Integration by parts, 10 questions"))
        phone.sync()
        laptop.sync()

        val onLaptop = laptop.all().single()
        assertEquals("Integration by parts, 10 questions", onLaptop.title)
        assertEquals(Subject.MATHS, onLaptop.subject)
        assertEquals(0, phone.app.store.pendingCount(), "the phone's change was confirmed")
    }

    @Test
    fun tickingOnOneDeviceTicksOnTheOther() = twoDevices { phone, laptop ->
        val id = phone.tasks.add(draft("Past paper 42"))
        phone.sync()
        laptop.sync()

        laptop.clock.now += 60_000
        laptop.tasks.setDone(id, true)
        laptop.sync()
        phone.sync()

        assertTrue(phone.all().single().done)
    }

    @Test
    fun conflictingOfflineEditsSettleOnTheLaterOneEverywhere() = twoDevices { phone, laptop ->
        val id = phone.tasks.add(draft("Vectors"))
        phone.sync()
        laptop.sync()

        // Both edit while offline. The laptop's edit is the later one.
        phone.clock.now += 10_000
        phone.tasks.edit(id, draft("Vectors, lines and planes"))
        laptop.clock.now += 20_000
        laptop.tasks.edit(id, draft("Vectors, planes only"))

        phone.sync()
        laptop.sync()
        phone.sync()

        assertEquals("Vectors, planes only", phone.all().single().title)
        assertEquals("Vectors, planes only", laptop.all().single().title)
        assertEquals(0, phone.app.store.pendingCount())
        assertEquals(0, laptop.app.store.pendingCount())
    }

    @Test
    fun anEditBasedOnTheLatestVersionWinsEvenWithASlowClock() = twoDevices { phone, laptop ->
        val id = phone.tasks.add(draft("Moments"))
        phone.clock.now += 50_000
        phone.tasks.setDone(id, true)
        phone.sync()
        laptop.sync()

        // The laptop's clock is behind the phone's, but it has seen the phone's edit, so its edit still wins.
        laptop.clock.now -= 30_000
        laptop.tasks.setDone(id, false)
        laptop.sync()
        phone.sync()

        assertFalse(phone.all().single().done)
    }

    @Test
    fun deletesAndUndoReachTheOtherDevice() = twoDevices { phone, laptop ->
        val id = phone.tasks.add(draft("Binomial distribution"))
        phone.sync()
        laptop.sync()

        phone.clock.now += 1_000
        phone.tasks.delete(id)
        phone.sync()
        laptop.sync()
        assertTrue(laptop.all().isEmpty())

        laptop.clock.now += 5_000
        laptop.tasks.restore(id)
        laptop.sync()
        phone.sync()
        assertEquals(listOf(id), phone.all().map { it.id })
    }

    @Test
    fun theExamDateSyncs() = twoDevices { phone, laptop ->
        phone.tasks.setExamDate(LocalDate(2027, 5, 4))
        phone.sync()
        laptop.sync()
        assertEquals(LocalDate(2027, 5, 4), laptop.tasks.examDate.first())

        laptop.clock.now += 1_000
        laptop.tasks.setExamDate(null)
        laptop.sync()
        phone.sync()
        assertEquals(null, phone.tasks.examDate.first())
    }

    @Test
    fun aRunningLaptopPicksUpThePhonesChangeWithoutBeingAsked() = twoDevices { phone, laptop ->
        laptop.app.start()
        phone.app.start()

        phone.tasks.add(draft("Kinematics recap", Subject.PHYSICS))

        val arrived = withTimeout(10_000) { laptop.tasks.tasks.first { it.isNotEmpty() } }
        assertEquals("Kinematics recap", arrived.single().title)
    }
}
