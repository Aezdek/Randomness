package xyz.aezdek.hush.client

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.protocol.CallSignal
import xyz.aezdek.hush.protocol.CommentPayload
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.FollowState
import xyz.aezdek.hush.protocol.InviteDto
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.LikePayload
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ProfilePayload
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.StoryText
import xyz.aezdek.hush.protocol.WsFrame
import xyz.aezdek.hush.server.ServerConfig
import xyz.aezdek.hush.server.hush
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The Android client's shared logic against the real server, over real HTTP and WebSockets. */
class ClientTest {
    private lateinit var dir: File
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0
    private val scopes = ArrayList<CoroutineScope>()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        dir = Files.createTempDirectory("hush-client").toFile()
        port = ServerSocket(0).use { it.localPort }
        val config = ServerConfig(dataDir = dir, host = "127.0.0.1", port = port, runJobs = false, minFreeBytes = 0, version = "test")
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") { hush(config) }.start(wait = false)
    }

    @After
    fun stop() {
        scopes.forEach { it.cancel() }
        server.stop(100, 1000)
        dir.deleteRecursively()
    }

    private fun client(tables: Tables = MemoryTables(), secrets: SecretStore = MemorySecretStore()): AezClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return AezClient("http://127.0.0.1:$port", http, tables, secrets, DeviceInfo("test", "android"), scope, KdfParams.FAST_FOR_TESTS)
    }

    private suspend fun until(what: String, check: () -> Boolean) {
        withTimeout(15_000) {
            while (!check()) delay(50)
        }
        assertTrue(what, check())
    }

    /** The index for the state as it is now. [AezClient.index] is derived on another thread, so it can lag a moment. */
    private val AezClient.current: Index get() = Index.build(state.value)

    private fun tempFile(bytes: ByteArray): File = File.createTempFile("media", ".bin", dir).apply { writeBytes(bytes) }

    @Test
    fun twoPeopleUseTheAppEndToEnd() = runBlocking {
        val firstInvite = File(dir, "first-invite.txt").readText().trim()
        val anaSecrets = MemorySecretStore()
        val anaTables = MemoryTables()
        val ana = client(anaTables, anaSecrets)
        ana.signUp(firstInvite, "ana", "ana has a long password")
        val invite: InviteDto = ana.api.post("/admin/invites")
        val bo = client()
        bo.signUp(invite.code, "Bo", "bo has a long password")
        assertEquals("bo", bo.state.value.me!!.username)

        // Profiles are sealed to every member.
        ana.composer.saveProfile(ProfilePayload(displayName = "Ana Lima", bio = "hi"))
        // If Ana's client hadn't heard of Bo yet, the server has it seal the profile to Bo on its next sync.
        ana.engine.sync()
        bo.engine.sync()
        val anaId = ana.state.value.me!!.id
        val boId = bo.state.value.me!!.id
        assertEquals("Ana Lima", bo.current.profile(anaId)?.displayName)
        until("the UI's index catches up") { bo.index.value.profile(anaId)?.displayName == "Ana Lima" }

        // Bo asks to follow (accounts start private); Ana confirms.
        bo.engine.sync()
        bo.composer.follow(anaId)
        assertEquals(FollowState.REQUESTED, bo.state.value.users[anaId]!!.followState)
        ana.engine.sync()
        assertEquals(listOf(boId), ana.state.value.followRequests)
        ana.composer.acceptFollow(boId)

        // A post with encrypted media; Bo decrypts both.
        val photo = Random.nextBytes(150_000)
        val ref = ana.media.upload(PreparedMedia(tempFile(photo), "image/jpeg", 1080, 1350, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
        val post = ana.composer.createPost(PostPayload(caption = "Golden hour @bo #golden", media = listOf(ref)))
        bo.engine.sync()
        val seen = bo.state.value.entities[post.id]
        assertNotNull("Bo can read Ana's post", seen)
        assertEquals("Golden hour @bo #golden", seen!!.payloadAs<PostPayload>()!!.caption)
        assertArrayEquals(photo, bo.media.download(seen.payloadAs<PostPayload>()!!.media.single()))
        // The @mention reached Bo as an activity item only he can read.
        assertTrue(bo.current.activity.any { it.dto.authorId == anaId })

        // Ranged reads decrypt only the chunks they need, for video streaming.
        val ranged = bo.media.openRanged(ref)
        assertEquals(photo.size.toLong(), ranged.length)
        assertArrayEquals(photo.copyOfRange(70_000, 140_000), ranged.read(70_000, 70_000))
        assertArrayEquals(photo.copyOfRange(149_990, 150_000), ranged.read(149_990, 1000))

        // Likes and comments use child keys; Ana reads them.
        bo.composer.toggleLike(post.id)
        bo.composer.comment(post.id, "This light is unreal")
        ana.engine.sync()
        assertTrue(ana.current.isLiked(post.id, boId))
        assertEquals("This light is unreal", ana.current.commentsOf(post.id).single().payloadAs<CommentPayload>()!!.text)
        bo.composer.toggleLike(post.id)
        ana.engine.sync()
        assertTrue(ana.current.likesOf(post.id).isEmpty())

        // A story, then a direct chat that arrives over the WebSocket without polling.
        ana.composer.createStory(StoryPayload(text = StoryText("Sunrise tomorrow?")), closeFriends = false)
        bo.engine.sync()
        assertEquals(1, bo.current.stories[anaId]?.size)

        until("Bo's socket is open") { bo.realtime.isOpen }
        val threadId = ana.composer.openDirect(boId)
        ana.composer.sendMessage(threadId, MessagePayload(MessageType.TEXT, text = "Coffee later?"))
        until("Bo receives the message by itself") {
            bo.current.messages[threadId].orEmpty().any { it.payloadAs<MessagePayload>()?.text == "Coffee later?" }
        }
        val message = bo.current.messages[threadId]!!.single()
        bo.composer.react(message, "❤️")
        bo.composer.sendMessage(threadId, MessagePayload(MessageType.TEXT, text = "Always", replyTo = message.id))
        ana.engine.sync()
        assertEquals(2, ana.current.messages[threadId]!!.size)
        assertEquals(1, ana.current.reactionsOf(message.id).size)
        assertEquals(1, ana.current.unreadCount(ana.state.value.threads[threadId]!!, anaId))

        // Call signalling is sealed to Bo and signed by Ana; anything else is rejected.
        val frame = ana.sealSignal(boId, CallSignal("offer", "call-1", sdp = "v=0"))!!
        val relayed = frame.copy(from = anaId, to = null)
        val (from, signal) = bo.openSignal(relayed)!!
        assertEquals(anaId, from)
        assertEquals("v=0", signal.sdp)
        assertEquals(null, ana.openSignal(relayed.copy(from = boId)))

        // Ana's app restarts offline: everything is still there from the local cache.
        val again = client(anaTables, anaSecrets)
        assertTrue(again.restore())
        until("restored entities") { again.state.value.entities.containsKey(post.id) }
        assertEquals("Golden hour @bo #golden", again.state.value.entities[post.id]!!.payloadAs<PostPayload>()!!.caption)
    }

    @Test
    fun webShapedPayloadsDecode() {
        // The web client omits defaults and sends nulls; Kotlin must accept both.
        val post = Payloads.decode(Kind.POST, """{"caption":"hi","media":[{"blobId":"b","key":"k","mime":"image/jpeg","size":3,"durationMs":null,"poster":null}],"location":null,"hideLikes":null}""")
        assertEquals("hi", (post as PostPayload).caption)
        assertEquals(false, post.hideLikes)
        assertEquals("❤️", (Payloads.decode(Kind.LIKE, "{}") as LikePayload).emoji)
        val unknown = Payloads.decode("future-kind", """{"x":1}""")
        assertTrue(unknown is kotlinx.serialization.json.JsonElement)
        assertEquals(WsFrame("sync"), WsFrame("sync"))
    }
}
