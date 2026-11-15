package xyz.aezdek.hush.client

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.InviteDto
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.Plan
import xyz.aezdek.hush.protocol.PlanAnswer
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.WsType
import xyz.aezdek.hush.server.ServerConfig
import xyz.aezdek.hush.server.hush
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Notes to self, albums, plans, live location, memories, search and sign-in alerts, against the real server. */
class CircleTest {
    private lateinit var dir: File
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0
    private val scopes = ArrayList<CoroutineScope>()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        dir = Files.createTempDirectory("hush-circle").toFile()
        port = ServerSocket(0).use { it.localPort }
        val config = ServerConfig(dataDir = dir, host = "127.0.0.1", port = port, runJobs = false, minFreeBytes = 0, version = "test",
            dbKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") { hush(config) }.start(wait = false)
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

    private suspend fun two(): Pair<AezClient, AezClient> {
        val ana = client().apply { signUp(File(dir, "first-invite.txt").readText().trim(), "ana", "tangerine kettle orbit") }
        val invite: InviteDto = ana.api.post("/admin/invites")
        val bo = client().apply { signUp(invite.code, "bo", "quiet harbour lantern") }
        ana.engine.sync()
        bo.engine.sync()
        return ana to bo
    }

    @Test
    fun notesToSelfAlbumsPlansAndSearch() = runBlocking {
        val (ana, bo) = two()
        val boId = bo.state.value.me!!.id

        // Notes to self: one chat, only Ana in it, found again rather than made twice.
        val notes = ana.composer.openNotesToSelf()
        assertEquals(notes, ana.composer.openNotesToSelf())
        ana.composer.sendMessage(notes, MessagePayload(MessageType.TEXT, text = "buy film for the trip"))
        assertEquals(notes, ana.current.notesToSelf(ana.state.value))
        bo.engine.sync()
        assertNull("Bo never sees it", bo.state.value.threads[notes])

        // An album both of them add photos to.
        val album = ana.composer.createAlbum("Lisbon", listOf(boId))
        bo.engine.sync()
        assertTrue(bo.current.isAlbum(album))
        val photo = File.createTempFile("photo", ".jpg", dir).apply { writeBytes(kotlin.random.Random.nextBytes(4000)) }
        val ref = bo.media.upload(PreparedMedia(photo, "image/jpeg", 800, 600, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
        bo.composer.sendMessage(album, MessagePayload(MessageType.MEDIA, media = listOf(ref)))
        ana.engine.sync()
        assertEquals(1, ana.current.albumMedia(album).size)

        // A plan, with answers that can change.
        val chat = ana.composer.openDirect(boId)
        val plan = ana.composer.sendPlan(chat, Plan("Dinner at Leo's", System.currentTimeMillis() + 86_400_000, "Rua das Flores 12"))
        bo.engine.sync()
        val seen = bo.current.messages[chat]!!.single { it.id == plan.id }
        bo.composer.answerPlan(seen, PlanAnswer.MAYBE)
        bo.composer.answerPlan(seen, PlanAnswer.GOING)
        ana.engine.sync()
        assertEquals(mapOf(boId to PlanAnswer.GOING), ana.current.planAnswers(plan.id))
        bo.composer.answerPlan(bo.current.messages[chat]!!.single { it.id == plan.id }, PlanAnswer.GOING)
        ana.engine.sync()
        assertTrue("choosing it again takes it back", ana.current.planAnswers(plan.id).isEmpty())

        // Search across every chat, on the device.
        assertEquals(listOf(plan.id), ana.current.searchMessages("rua das").map { it.id })
        assertEquals(1, ana.current.searchMessages("FILM").size)
        assertTrue(ana.current.searchMessages("x").isEmpty())
    }

    @Test
    fun liveLocationIsReplacedNotTrailedAndEnds() = runBlocking {
        val (ana, bo) = two()
        val chat = ana.composer.openDirect(bo.state.value.me!!.id)
        val shared = ana.composer.shareLocation(chat, 38.71, -9.14, 12f, 60 * 60_000L)
        assertNotNull(shared.dto.expiresAt)
        val moved = ana.composer.moveLocation(shared, 38.72, -9.15, 8f)!!
        assertEquals("the same message, a new version", shared.id, moved.id)
        bo.engine.sync()
        val there = bo.current.liveLocations(chat).single().payloadAs<MessagePayload>()!!.location!!
        assertEquals(38.72, there.lat, 0.0)
        // Nobody can push the end out past what was chosen.
        assertTrue(bo.current.liveLocations(chat, now = shared.dto.expiresAt!! + 1).isEmpty())
        ana.composer.stopLocation(moved)
        bo.engine.sync()
        assertTrue(bo.current.liveLocations(chat).isEmpty())
    }

    @Test
    fun memoriesComeFromThisDateInEarlierYears() = runBlocking {
        val (ana, _) = two()
        val photo = File.createTempFile("photo", ".jpg", dir).apply { writeBytes(kotlin.random.Random.nextBytes(4000)) }
        val ref = ana.media.upload(PreparedMedia(photo, "image/jpeg", 800, 600, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
        val post = ana.composer.createPost(PostPayload(caption = "first day", media = listOf(ref)))
        val me = ana.state.value.me!!.id
        val nextYear = java.time.Instant.ofEpochMilli(post.dto.createdAt).atZone(ZoneOffset.UTC).plusYears(1).toInstant().toEpochMilli()
        assertEquals(listOf(post.id), ana.current.onThisDay(me, nextYear, ZoneOffset.UTC).map { it.id })
        assertTrue("not on the same day it was posted", ana.current.onThisDay(me, post.dto.createdAt, ZoneOffset.UTC).isEmpty())
        assertTrue(ana.current.onThisDay(me, nextYear + 86_400_000, ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun aNewSignInIsAnnouncedAndCanBeThrownOut() = runBlocking {
        val (ana, _) = two()
        withTimeout(10_000) { while (!ana.realtime.isOpen) delay(50) }
        val before = System.currentTimeMillis() - 1000
        val heard = async { withTimeout(10_000) { ana.security.first { it == WsType.SESSION } } }
        val intruder = client()
        intruder.signIn("ana", "tangerine kettle orbit")
        assertEquals(WsType.SESSION, heard.await())
        val fresh = ana.newSignIns(before)
        assertEquals(1, fresh.size)
        assertEquals("Pixel 9", fresh.single().deviceName)
        assertTrue(fresh.single().active)
        ana.signOutDevice(fresh.single().sessionId)
        withTimeout(10_000) {
            runCatching { intruder.engine.sync() }
            while (intruder.signedIn) delay(50)
        }
        // It stays on record, marked as gone: signing out doesn't erase that it happened.
        assertFalse(ana.newSignIns(before).single().active)
        // Someone who signs in and straight out again is on record too.
        val quick = client()
        quick.signIn("ana", "tangerine kettle orbit")
        quick.signOut()
        assertEquals(2, ana.newSignIns(before).size)
        assertTrue(ana.newSignIns(before).none { it.active })
    }
}
