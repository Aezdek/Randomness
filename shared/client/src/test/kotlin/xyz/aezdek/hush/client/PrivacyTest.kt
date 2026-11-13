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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.Disappearing
import xyz.aezdek.hush.protocol.InviteDto
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ThreadPayload
import xyz.aezdek.hush.server.ServerConfig
import xyz.aezdek.hush.server.hush
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Disappearing messages, and what a removed person's app keeps (nothing), against the real server. */
class PrivacyTest {
    private lateinit var dir: File
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0
    private val scopes = ArrayList<CoroutineScope>()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        dir = Files.createTempDirectory("hush-privacy").toFile()
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

    private fun client(tables: Tables = MemoryTables()): AezClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return AezClient("http://127.0.0.1:$port", http, tables, MemorySecretStore(), DeviceInfo("test", "android"), scope, KdfParams.FAST_FOR_TESTS)
    }

    private val AezClient.current: Index get() = Index.build(state.value)

    /** Three people: [0] is the admin. */
    private suspend fun people(vararg names: String, tables: List<Tables> = names.map { MemoryTables() }): List<AezClient> {
        val first = client(tables[0]).apply { signUp(File(dir, "first-invite.txt").readText().trim(), names[0], "${names[0]} has a long password") }
        val rest = names.drop(1).mapIndexed { i, name ->
            val invite: InviteDto = first.api.post("/admin/invites")
            client(tables[i + 1]).apply { signUp(invite.code, name, "$name has a long password") }
        }
        val all = listOf(first) + rest
        all.forEach { it.engine.sync() }
        return all
    }

    @Test
    fun disappearingMessagesExpireButTheSettingStays() = runBlocking {
        val (ana, bo) = people("ana", "bo")
        val boId = bo.state.value.me!!.id
        val thread = ana.composer.openDirect(boId)
        val setting = ana.composer.sendChatEvent(thread, ChatEvent(disappearAfter = Disappearing.DAY))
        assertNull("the setting itself never expires", setting.dto.expiresAt)

        bo.engine.sync()
        assertEquals("both sides see the timer", Disappearing.DAY, bo.current.chatCustom(thread).disappearAfter)
        val sent = bo.composer.sendMessage(thread, MessagePayload(MessageType.TEXT, text = "gone tomorrow"))
        val expiresAt = sent.dto.expiresAt ?: error("a message in a disappearing chat has an expiry")
        assertTrue("about a day from now", expiresAt - System.currentTimeMillis() in (Disappearing.DAY - 60_000)..Disappearing.DAY)

        // Once its time is up the message is gone from the chat, even before the server's hourly sweep deletes it.
        ana.engine.sync()
        val messages = { now: Long -> Index.build(ana.state.value, now).messages[thread].orEmpty().map { it.payloadAs<MessagePayload>()?.text } }
        assertTrue("gone tomorrow" in messages(System.currentTimeMillis()))
        assertFalse("gone tomorrow" in messages(expiresAt + 1))

        // Turning it off: new messages stay.
        ana.composer.sendChatEvent(thread, ChatEvent(disappearAfter = 0))
        assertNull(ana.composer.sendMessage(thread, MessagePayload(MessageType.TEXT, text = "here to stay")).dto.expiresAt)
    }

    @Test
    fun removedPeopleKeepNothing() = runBlocking {
        val coTables = MemoryTables()
        val (ana, bo, cy) = people("ana", "bo", "cy", tables = listOf(MemoryTables(), MemoryTables(), coTables))
        val anaId = ana.state.value.me!!.id
        val boId = bo.state.value.me!!.id
        val cyId = cy.state.value.me!!.id

        // Bo follows Ana and reads her post and its comments.
        bo.composer.follow(anaId)
        ana.engine.sync()
        ana.composer.acceptFollow(boId)
        ana.engine.sync()
        val photo = File.createTempFile("photo", ".bin", dir).apply { writeBytes(kotlin.random.Random.nextBytes(20_000)) }
        val ref = ana.media.upload(PreparedMedia(photo, "image/jpeg", 1080, 1350, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
        val post = ana.composer.createPost(PostPayload(caption = "only for followers", media = listOf(ref)))
        ana.composer.comment(post.id, "first!")
        ana.engine.processWrapTasks()
        bo.engine.sync()
        assertNotNull(bo.state.value.entities[post.id])
        assertEquals(1, bo.current.commentsOf(post.id).size)

        // Ana removes Bo as a follower: his app drops the post and its comments, cached copies included.
        ana.composer.removeFollower(boId)
        bo.engine.sync()
        assertNull(bo.state.value.entities[post.id])
        assertTrue(bo.state.value.entities.values.none { it.dto.parentId == post.id })

        // Taken out of a group: the chat and its messages leave Cy's app.
        val group = ana.composer.createThread(listOf(boId, cyId), isGroup = true, meta = ThreadPayload(name = "Trip"))
        ana.composer.sendMessage(group, MessagePayload(MessageType.TEXT, text = "plans"))
        cy.engine.sync()
        assertTrue(cy.current.messages[group].orEmpty().isNotEmpty())
        ana.composer.leaveThread(group, cyId)
        cy.engine.sync()
        assertNull(cy.state.value.threads[group])
        assertTrue(cy.state.value.entities.values.none { it.dto.threadId == group })

        // Removed from the server: signed out, and everything stored on the device is wiped.
        ana.api.delete<xyz.aezdek.hush.protocol.OkResponse>("/admin/users/$cyId")
        runCatching { cy.engine.sync() }
        kotlinx.coroutines.withTimeout(10_000) { while (cy.signedIn) kotlinx.coroutines.delay(50) }
        assertTrue("nothing left on the device", coTables.all(LocalDb.OBJECTS).isEmpty())
    }
}
