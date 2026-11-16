package xyz.aezdek.hush.client

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.InviteDto
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.ScheduleRequest
import xyz.aezdek.hush.protocol.ScheduledDto
import xyz.aezdek.hush.server.Clock
import xyz.aezdek.hush.server.ServerConfig
import xyz.aezdek.hush.server.Services
import xyz.aezdek.hush.server.hush
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Messages sent later, against the real server with a clock the test moves. */
class SendLaterTest {
    private lateinit var dir: File
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var services: Services
    private var port = 0
    private var ahead = 0L
    private val scopes = ArrayList<CoroutineScope>()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        dir = Files.createTempDirectory("hush-later").toFile()
        port = ServerSocket(0).use { it.localPort }
        val config = ServerConfig(dataDir = dir, host = "127.0.0.1", port = port, runJobs = false, minFreeBytes = 0, version = "test",
            dbKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        val clock = object : Clock {
            override fun now() = System.currentTimeMillis() + ahead
        }
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") { services = hush(config, clock) }.start(wait = false)
    }

    @After
    fun stop() {
        scopes.forEach { it.cancel() }
        server.stop(100, 1000)
        dir.deleteRecursively()
    }

    private fun client(): AezClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return AezClient("http://127.0.0.1:$port", http, MemoryTables(), MemorySecretStore(), DeviceInfo("Pixel 9", "android"), scope, KdfParams.FAST_FOR_TESTS)
    }

    private val AezClient.current: Index get() = Index.build(state.value)
    private fun AezClient.texts(chat: String) = current.messages[chat].orEmpty().mapNotNull { it.payloadAs<MessagePayload>()?.text }

    private suspend fun two(): Pair<AezClient, AezClient> {
        val ana = client().apply { signUp(File(dir, "first-invite.txt").readText().trim(), "ana", "tangerine kettle orbit") }
        val invite: InviteDto = ana.api.post("/admin/invites")
        val bo = client().apply { signUp(invite.code, "bo", "quiet harbour lantern") }
        ana.engine.sync()
        bo.engine.sync()
        return ana to bo
    }

    private val hour = 3_600_000L

    @Test
    fun aMessageArrivesAtItsTimeAndNotBefore() = runBlocking {
        val (ana, bo) = two()
        val chat = ana.composer.openDirect(bo.state.value.me!!.id)
        val at = System.currentTimeMillis() + 3 * 24 * hour
        val waiting = ana.composer.sendLater(chat, "happy birthday", at)
        assertEquals(listOf(waiting.id), ana.composer.scheduled().map { it.id })

        // Before its time: the job posts nothing, and Bo's app has nothing to read, however it asks.
        services.jobs.releaseNow()
        bo.engine.sync()
        assertTrue(bo.texts(chat).isEmpty())
        assertTrue(runCatching { bo.api.get<xyz.aezdek.hush.protocol.ObjectDto>("/objects/${waiting.id}") }.isFailure)
        assertTrue("only its author sees it waiting", bo.composer.scheduled().isEmpty())
        assertTrue("and only its author can cancel it", runCatching { bo.composer.cancelScheduled(waiting.id) }.isFailure)

        // Its time comes.
        ahead = 3 * 24 * hour + 1_000
        services.jobs.releaseNow()
        bo.engine.sync()
        assertEquals(listOf("happy birthday"), bo.texts(chat))
        assertTrue(ana.composer.scheduled().isEmpty())
        val delivered = bo.current.messages[chat]!!.single()
        assertEquals("dated for when it arrived", at, delivered.dto.createdAt)
    }

    @Test
    fun cancelledOrOutOfTheChatMeansNotDelivered() = runBlocking {
        val (ana, bo) = two()
        val boId = bo.state.value.me!!.id
        val chat = ana.composer.openDirect(boId)
        val cancelled = ana.composer.sendLater(chat, "never mind", System.currentTimeMillis() + 2 * hour)
        ana.composer.cancelScheduled(cancelled.id)

        // Bo is paused by the admin before his message's time: it isn't delivered, and his list says why.
        val fromBo = bo.composer.sendLater(chat, "from someone paused since", System.currentTimeMillis() + 2 * hour)
        ana.api.post<xyz.aezdek.hush.protocol.OkResponse, xyz.aezdek.hush.protocol.SetStatusRequest>("/admin/users/$boId/status", xyz.aezdek.hush.protocol.SetStatusRequest("restricted"))
        ahead = 2 * hour + 1_000
        services.jobs.releaseNow()
        ana.engine.sync()
        assertTrue(ana.texts(chat).isEmpty())
        val left = bo.composer.scheduled().single()
        assertEquals(fromBo.id, left.id)
        assertNotNull(left.failed)
    }

    @Test
    fun whatTheServerRefusesToHold() = runBlocking {
        val (ana, bo) = two()
        val chat = ana.composer.openDirect(bo.state.value.me!!.id)
        val now = System.currentTimeMillis()
        suspend fun refused(block: suspend () -> Unit) = assertTrue(runCatching { block() }.isFailure)
        // Too soon, too far, and dated for some other time than its delivery (to jump the queue of a feed, say).
        refused { ana.composer.sendLater(chat, "x", now + 1_000) }
        refused { ana.composer.sendLater(chat, "x", now + 90L * 24 * hour) }
        val epoch = ana.composer.sendMessage(chat, MessagePayload("text", text = "hi")).dto.parentId
        val draft = ana.composer.seal(
            Composer.Draft(xyz.aezdek.hush.protocol.Kind.MESSAGE, xyz.aezdek.hush.protocol.Audience.THREAD, MessagePayload("text", text = "x"), parentId = epoch, threadId = chat),
            createdAt = now,
        )
        refused { ana.api.post<ScheduledDto, ScheduleRequest>("/scheduled", ScheduleRequest(now + 2 * hour, draft.upload)) }
        // Not a message, or carrying files, or for a chat the sender isn't in.
        refused { ana.api.post<ScheduledDto, ScheduleRequest>("/scheduled", ScheduleRequest(now + 2 * hour, draft.upload.copy(kind = xyz.aezdek.hush.protocol.Kind.POST, createdAt = now + 2 * hour))) }
        refused { ana.api.post<ScheduledDto, ScheduleRequest>("/scheduled", ScheduleRequest(now + 2 * hour, draft.upload.copy(blobIds = listOf("ab".repeat(32)), createdAt = now + 2 * hour))) }
        val invite: InviteDto = ana.api.post("/admin/invites")
        val cy = client().apply { signUp(invite.code, "cy", "velvet anchor pocket") }
        refused { cy.api.post<ScheduledDto, ScheduleRequest>("/scheduled", ScheduleRequest(now + 2 * hour, draft.upload.copy(createdAt = now + 2 * hour))) }
        // No more than fifty waiting at once.
        repeat(50) { ana.composer.sendLater(chat, "m$it", now + 2 * hour + it) }
        refused { ana.composer.sendLater(chat, "one too many", now + 3 * hour) }
        assertNull(ana.composer.scheduled().firstOrNull { it.failed != null })
    }
}
