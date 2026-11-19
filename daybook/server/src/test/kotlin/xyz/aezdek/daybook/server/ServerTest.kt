package xyz.aezdek.daybook.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import xyz.aezdek.daybook.core.DaybookJson
import xyz.aezdek.daybook.core.Limits
import xyz.aezdek.daybook.core.SettingKeys
import xyz.aezdek.daybook.core.SettingRecord
import xyz.aezdek.daybook.core.SyncRequest
import xyz.aezdek.daybook.core.SyncResponse
import xyz.aezdek.daybook.core.TaskRecord
import xyz.aezdek.daybook.core.WaitResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {

    private val token = "test-token-0123456789abcdef"
    private val now = 1_800_000_000_000

    private fun task(id: String, updatedAt: Long, by: String = "phone", title: String = "Task $id", done: Boolean = false) =
        TaskRecord(
            id = id, title = title, subject = "physics", paper = "P4", minutes = 60, date = "2026-11-19",
            done = done, createdAt = 1, updatedAt = updatedAt, updatedBy = by,
        )

    /** The server's wall clock. Tests move it forward to model time passing. */
    private var clockNow = now

    private fun serverTest(waitMillis: Long = 2_000, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val store = ServerStore.open(":memory:")
        application { daybookModule(token, store, clock = { clockNow }, waitMillis = waitMillis) }
        val http = createClient { install(ContentNegotiation) { json(DaybookJson) } }
        block(http)
    }

    private suspend fun HttpClient.sync(request: SyncRequest): SyncResponse =
        post("/api/sync") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    @Test
    fun healthNeedsNoToken() = serverTest { http ->
        assertEquals(HttpStatusCode.OK, http.get("/health").status)
    }

    @Test
    fun apiRefusesMissingOrWrongToken() = serverTest { http ->
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/ping").status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/ping") { bearerAuth("wrong-token-0123456789abcdef") }.status)
        assertEquals(HttpStatusCode.OK, http.get("/api/ping") { bearerAuth(token) }.status)
    }

    @Test
    fun aTaskPushedByOneDeviceReachesTheOther() = serverTest { http ->
        val pushed = http.sync(SyncRequest(since = 0, deviceId = "phone", tasks = listOf(task("a", 100))))
        assertEquals(1, pushed.rev)

        val laptop = http.sync(SyncRequest(since = 0, deviceId = "laptop"))
        assertEquals(listOf("a"), laptop.tasks.map { it.id })
        assertEquals(1, laptop.rev)

        val nothingNew = http.sync(SyncRequest(since = laptop.rev, deviceId = "laptop"))
        assertTrue(nothingNew.tasks.isEmpty())
    }

    @Test
    fun anOlderEditLosesAndTheWinnerIsSentBack() = serverTest { http ->
        http.sync(SyncRequest(since = 0, deviceId = "laptop", tasks = listOf(task("a", 200, by = "laptop", title = "Newer"))))

        // The phone edited the same task offline, earlier in time, and only syncs now.
        val response = http.sync(SyncRequest(since = 1, deviceId = "phone", tasks = listOf(task("a", 150, title = "Older"))))

        assertEquals(1, response.rev, "nothing was written")
        assertEquals("Newer", response.tasks.single().title, "the phone is told which version won")
    }

    @Test
    fun aNewerEditReplacesTheStoredOne() = serverTest { http ->
        http.sync(SyncRequest(since = 0, deviceId = "phone", tasks = listOf(task("a", 100))))
        http.sync(SyncRequest(since = 1, deviceId = "laptop", tasks = listOf(task("a", 300, by = "laptop", done = true))))

        val phone = http.sync(SyncRequest(since = 1, deviceId = "phone"))
        assertTrue(phone.tasks.single().done)
        assertEquals(2, phone.rev)
    }

    @Test
    fun aClockFarInTheFutureCannotWinForever() = serverTest { http ->
        val farFuture = now + 365L * 24 * 60 * 60 * 1000
        http.sync(SyncRequest(since = 0, deviceId = "phone", tasks = listOf(task("a", farFuture))))
        val stored = http.sync(SyncRequest(since = 0, deviceId = "laptop")).tasks.single()
        assertEquals(now + ServerStore.CLOCK_SKEW_ALLOWANCE_MS, stored.updatedAt)

        // Ten minutes later a correctly-clocked device edits the task, and its edit wins.
        clockNow = now + 10 * 60 * 1000
        val laterRealEdit = http.sync(
            SyncRequest(since = 1, deviceId = "laptop", tasks = listOf(task("a", clockNow, by = "laptop", title = "Real"))),
        )
        assertEquals("Real", laterRealEdit.tasks.single().title)
    }

    @Test
    fun malformedRecordsAreRejectedWithoutBlockingTheRest() = serverTest { http ->
        val response = http.sync(
            SyncRequest(since = 0, deviceId = "phone", tasks = listOf(task("good", 1), task("bad", 1).copy(date = "someday"))),
        )
        assertEquals(listOf("bad"), response.rejected)
        assertEquals(listOf("good"), response.tasks.map { it.id })
    }

    @Test
    fun settingsSyncLikeTasks() = serverTest { http ->
        http.sync(
            SyncRequest(
                since = 0, deviceId = "phone",
                settings = listOf(SettingRecord(SettingKeys.EXAM_DATE, "2027-05-04", updatedAt = 10, updatedBy = "phone")),
            ),
        )
        val laptop = http.sync(SyncRequest(since = 0, deviceId = "laptop"))
        assertEquals("2027-05-04", laptop.settings.single().value)
    }

    @Test
    fun largePullsArePaged() = serverTest { http ->
        val all = (1..(Limits.PAGE + 20)).map { task("t$it", 10) }
        all.chunked(Limits.MAX_BATCH).forEach { batch -> http.sync(SyncRequest(since = 0, deviceId = "phone", tasks = batch)) }

        val first = http.sync(SyncRequest(since = 0, deviceId = "laptop"))
        assertTrue(first.hasMore)
        assertEquals(Limits.PAGE, first.tasks.size)
        val second = http.sync(SyncRequest(since = first.rev, deviceId = "laptop"))
        assertFalse(second.hasMore)
        assertEquals(20, second.tasks.size)
        assertEquals(all.map { it.id }.toSet(), (first.tasks + second.tasks).map { it.id }.toSet())
    }

    @Test
    fun waitReturnsAsSoonAsAnotherDeviceWrites() = serverTest(waitMillis = 10_000) { http ->
        coroutineScope {
            val waiting = async { http.get("/api/wait?since=0") { bearerAuth(token) }.body<WaitResponse>() }
            delay(200)
            http.sync(SyncRequest(since = 0, deviceId = "phone", tasks = listOf(task("a", 1))))
            assertEquals(1, waiting.await().rev)
        }
    }

    @Test
    fun waitTimesOutWithTheCurrentRevision() = serverTest(waitMillis = 300) { http ->
        val response = http.get("/api/wait?since=0") { bearerAuth(token) }.body<WaitResponse>()
        assertEquals(0, response.rev)
    }
}
