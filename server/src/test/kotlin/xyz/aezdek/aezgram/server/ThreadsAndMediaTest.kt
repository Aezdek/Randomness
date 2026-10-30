package xyz.aezdek.aezgram.server

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.aezgram.crypto.AccountKeys
import xyz.aezdek.aezgram.crypto.B64
import xyz.aezdek.aezgram.crypto.MediaCipher
import xyz.aezdek.aezgram.crypto.ObjectCrypto
import xyz.aezdek.aezgram.crypto.SignalCrypto
import xyz.aezdek.aezgram.protocol.AddMembersRequest
import xyz.aezdek.aezgram.protocol.BlobDto
import xyz.aezdek.aezgram.protocol.CreateThreadRequest
import xyz.aezdek.aezgram.protocol.ReadRequest
import xyz.aezdek.aezgram.protocol.SignalDto
import xyz.aezdek.aezgram.protocol.StartUploadRequest
import xyz.aezdek.aezgram.protocol.ThreadDto
import xyz.aezdek.aezgram.protocol.UploadState
import xyz.aezdek.aezgram.protocol.Wire
import xyz.aezdek.aezgram.protocol.WrapDto
import xyz.aezdek.aezgram.protocol.WsFrame
import xyz.aezdek.aezgram.protocol.WsType
import java.io.File

class ThreadsAndMediaTest {

    private class Chat(val threadId: String, val threadKey: ByteArray, var epochId: String, var epochKey: ByteArray)

    private suspend fun Harness.startChat(creator: Member, others: List<Member>, group: Boolean): Chat {
        val threadId = newId()
        val epochId = newId()
        val members = others + creator
        val (thread, threadKey) = creator.seal("thread", "thread", """{"name":"chat"}""", members, objectId = threadId, threadId = threadId)
        val (epoch, epochKey) = creator.seal("epoch", "thread", "{}", members, objectId = epochId, threadId = threadId)
        val response = creator.post("/api/v1/threads", CreateThreadRequest(group, others.map { it.id }, thread, epoch))
        assertEquals(HttpStatusCode.Created, response.status)
        return Chat(threadId, threadKey, epochId, epochKey)
    }

    private fun Member.message(chat: Chat, text: String, audience: String = "thread") = newId().let { id ->
        seal("message", audience, text, objectId = id, parentId = chat.epochId, threadId = chat.threadId, key = ObjectCrypto.childKey(chat.epochKey, id), wrap = false)
    }

    @Test
    fun oneToOneChatsAreUniqueAndMessagesUseEpochKeys() = serverTest {
        val (alice, bob, carol) = members("alice", "bob", "carol")
        val chat = startChat(alice, listOf(bob), group = false)
        val duplicate = runCatching { startChat(bob, listOf(alice), group = false) }
        assertTrue(duplicate.isFailure)

        val (message) = alice.message(chat, "hi bob")
        assertEquals(HttpStatusCode.Created, alice.create(message).status)
        bob.sync()
        val got = bob.received.getValue(message.id)
        assertEquals("hi bob", bob.open(got, alice, ObjectCrypto.childKey(chat.epochKey, message.id)))
        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/objects/${message.id}").status)
        // Carol cannot post into the chat (and is not even told it exists).
        val (intrusion) = carol.message(chat, "hey")
        assertEquals(HttpStatusCode.NotFound, carol.create(intrusion).status)
    }

    @Test
    fun newGroupMembersOnlySeeMessagesFromTheirEpochOn() = serverTest {
        val (alice, bob, carol) = members("alice", "bob", "carol")
        val chat = startChat(alice, listOf(bob), group = true)
        val (before) = alice.message(chat, "before carol")
        alice.create(before)

        val newEpochId = newId()
        val (epoch, epochKey) = alice.seal("epoch", "thread", "{}", listOf(bob, carol), objectId = newEpochId, threadId = chat.threadId)
        val threadWrap = WrapDto(carol.id, B64.encode(ObjectCrypto.wrap(chat.threadKey, carol.keys.boxPk)))
        val added = alice.post("/api/v1/threads/${chat.threadId}/members", AddMembersRequest(listOf(carol.id), epoch, listOf(threadWrap)))
        assertEquals(3, added.body<ThreadDto>().members.size)

        // Sending against the old epoch is refused: everyone moves to the new key.
        val (stale) = bob.message(chat, "stale")
        assertEquals(HttpStatusCode.Conflict, bob.create(stale).status)
        chat.epochId = newEpochId
        chat.epochKey = epochKey
        val (after) = bob.message(chat, "welcome carol")
        assertEquals(HttpStatusCode.Created, bob.create(after).status)

        carol.sync()
        assertFalse(before.id in carol.received)
        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/objects/${before.id}").status)
        assertTrue(after.id in carol.received)
        assertTrue(chat.threadId in carol.received)

        // Carol leaves: she loses the chat entirely.
        carol.delete("/api/v1/threads/${chat.threadId}/members/${carol.id}")
        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/objects/${after.id}").status)
    }

    @Test
    fun vanishModeMessagesAreDeletedOnceEveryoneHasSeenThem() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val chat = startChat(alice, listOf(bob), group = false)
        val (vanish) = alice.message(chat, "poof", audience = "vanish")
        alice.create(vanish)
        services.jobs.expireNow()
        assertEquals(HttpStatusCode.OK, bob.get("/api/v1/objects/${vanish.id}").status)

        clock.advance(1000)
        bob.post("/api/v1/threads/${chat.threadId}/read", ReadRequest(clock.now()))
        services.jobs.expireNow()
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/${vanish.id}").status)
        assertEquals(HttpStatusCode.NotFound, alice.get("/api/v1/objects/${vanish.id}").status)
    }

    @Test
    fun viewOnceMediaIsDeletedAfterItIsOpened() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val chat = startChat(alice, listOf(bob), group = false)
        val (once) = alice.message(chat, "look", audience = "once")
        alice.create(once)
        bob.post("/api/v1/objects/${once.id}/views", null)
        assertEquals(HttpStatusCode.NotFound, alice.get("/api/v1/objects/${once.id}").status)
    }

    @Test
    fun mediaUploadsResumeServeRangesAndRespectAccess() = serverTest {
        val (alice, bob, carol) = members("alice", "bob", "carol")
        val fileKey = MediaCipher.newFileKey()
        val plain = ByteArray(300_000) { (it % 251).toByte() }
        val cipher = MediaCipher.encrypt(plain, fileKey)

        val start = alice.post("/api/v1/uploads", StartUploadRequest(cipher.size.toLong())).body<UploadState>()
        suspend fun put(offset: Int, bytes: ByteArray) = http.put("/api/v1/uploads/${start.uploadId}?offset=$offset") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.OK, put(0, cipher.copyOfRange(0, 100_000)).status)
        // A retry of an old piece is refused; the client asks where to resume.
        assertEquals(HttpStatusCode.Conflict, put(0, cipher.copyOfRange(0, 100_000)).status)
        val resumeAt = alice.get("/api/v1/uploads/${start.uploadId}").body<UploadState>().received.toInt()
        assertEquals(100_000, resumeAt)
        put(resumeAt, cipher.copyOfRange(resumeAt, cipher.size))
        val blob = alice.post("/api/v1/uploads/${start.uploadId}/complete", null).body<BlobDto>()
        assertEquals(64, blob.blobId.length)

        val (post) = alice.seal("post", "followers", "pic", listOf(bob), blobIds = listOf(blob.blobId))
        assertEquals(HttpStatusCode.Created, alice.create(post).status)

        val whole = http.get("/api/v1/blobs/${blob.blobId}") { header(HttpHeaders.Authorization, "Bearer ${bob.token}") }.bodyAsBytes()
        assertArrayEquals(plain, MediaCipher.decrypt(whole, fileKey))
        // Seek: the server serves the second chunk alone, and it opens on its own.
        val header = MediaCipher.parseHeader(whole)
        val range = header.cipherRange(1)
        val partial = http.get("/api/v1/blobs/${blob.blobId}") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
        }
        assertEquals(HttpStatusCode.PartialContent, partial.status)
        assertArrayEquals(plain.copyOfRange(65536, 131072), MediaCipher.openChunk(header, 1, partial.bodyAsBytes(), fileKey))

        val denied = http.get("/api/v1/blobs/${blob.blobId}") { header(HttpHeaders.Authorization, "Bearer ${carol.token}") }
        assertEquals(HttpStatusCode.NotFound, denied.status)

        // Orphaned media is collected after a day.
        alice.delete("/api/v1/objects/${post.id}")
        clock.advance(25 * 3_600_000L)
        assertEquals(1, services.blobs.gc())
    }

    @Test
    fun theServerHoldsNoPlaintext() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val marker = "PLAINTEXT-MARKER-7f3a"
        val (post) = alice.seal("post", "followers", "caption $marker", listOf(bob))
        alice.create(post)
        val chat = startChat(alice, listOf(bob), group = false)
        alice.create(alice.message(chat, "dm $marker").first)
        val cipher = MediaCipher.encrypt("image $marker".encodeToByteArray(), MediaCipher.newFileKey())
        val upload = alice.post("/api/v1/uploads", StartUploadRequest(cipher.size.toLong())).body<UploadState>()
        http.put("/api/v1/uploads/${upload.uploadId}?offset=0") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            setBody(cipher)
        }
        alice.post("/api/v1/uploads/${upload.uploadId}/complete", null)
        services.db.raw("PRAGMA wal_checkpoint(TRUNCATE)")
        val files = dataDir.walkTopDown().filter(File::isFile).toList()
        assertTrue(files.any { it.name == "aezgram.db" })
        for (file in files) assertFalse("${file.name} contains plaintext", String(file.readBytes(), Charsets.ISO_8859_1).contains(marker))
    }

    @Test
    fun webSocketsNudgeSyncAndRelaySealedCallSignals() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val signal = SignalCrypto.seal("""{"type":"offer"}""".encodeToByteArray(), alice.id, bob.id, clock.now(), alice.keys, bob.keys.boxPk)
        http.webSocket("/api/v1/ws", request = { header(HttpHeaders.SecWebSocketProtocol, "aezgram.v1, auth.${bob.token}") }) {
            suspend fun next(): WsFrame = withTimeout(5_000) {
                val frame = incoming.receive() as Frame.Text
                Wire.json.decodeFromString(WsFrame.serializer(), frame.readText())
            }
            assertEquals(WsType.SYNC, next().t)
            // Alice shares a post with bob: bob is nudged.
            alice.create(alice.seal("post", "followers", "x", listOf(bob)).first)
            var frame = next()
            while (frame.t == WsType.PRESENCE) frame = next()
            assertEquals(WsType.SYNC, frame.t)

            http.webSocket("/api/v1/ws", request = { header(HttpHeaders.SecWebSocketProtocol, "aezgram.v1, auth.${alice.token}") }) {
                val sent = WsFrame(
                    WsType.SIGNAL, to = bob.id, callId = "call1", ring = true,
                    signal = SignalDto(B64.encode(signal.sealed), B64.encode(signal.signature), signal.sentAt),
                )
                send(Wire.json.encodeToString(WsFrame.serializer(), sent))
                var relayed = next()
                while (relayed.t != WsType.SIGNAL) relayed = next()
                assertEquals(alice.id, relayed.from)
                val dto = relayed.signal!!
                val opened = SignalCrypto.open(
                    SignalCrypto.Sealed(B64.decode(dto.sealed), B64.decode(dto.signature), dto.sentAt),
                    relayed.from!!, bob.id, alice.keys.signPk, bob.keys, clock.now(),
                )
                assertEquals("""{"type":"offer"}""", opened.decodeToString())
            }
        }
        // A socket without a valid token is closed.
        AccountKeys.generate()
    }

    @Test
    fun snapshotsAreConsistentCopies() = serverTest {
        members("alice")
        val target = File(services.config.snapshotsDir, "aezgram-test.db")
        services.jobs.snapshot(target)
        assertTrue(target.length() > 0)
        val copy = Db(target)
        assertEquals(1L, copy.tx { long("SELECT COUNT(*) FROM users") })
        copy.close()
    }
}
