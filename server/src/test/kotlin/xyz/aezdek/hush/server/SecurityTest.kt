package xyz.aezdek.hush.server

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.crypto.AccountKeys
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.crypto.ObjectCrypto
import xyz.aezdek.hush.crypto.PasswordKeys
import xyz.aezdek.hush.protocol.AddMembersRequest
import xyz.aezdek.hush.protocol.AdminServerDto
import xyz.aezdek.hush.protocol.ApiError
import xyz.aezdek.hush.protocol.CreateThreadRequest
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.LoginRequest
import xyz.aezdek.hush.protocol.PreloginRequest
import xyz.aezdek.hush.protocol.PreloginResponse
import xyz.aezdek.hush.protocol.PushSubscriptionRequest
import xyz.aezdek.hush.protocol.ResetKeysRequest
import xyz.aezdek.hush.protocol.StartUploadRequest
import xyz.aezdek.hush.protocol.UnlockRequest
import xyz.aezdek.hush.protocol.UpdateMeRequest
import xyz.aezdek.hush.protocol.WrapDto
import java.io.File

/** One test per hole the security review closed, so none of them comes back unnoticed. */
class SecurityTest {

    private suspend fun Harness.login(username: String, authKey: ByteArray = ByteArray(32)) = http.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(username, B64.encode(authKey), DeviceInfo("probe", "web")))
    }

    private suspend fun Harness.authKeyOf(member: Member): ByteArray {
        val pre = http.post("/api/v1/auth/prelogin") {
            contentType(ContentType.Application.Json)
            setBody(PreloginRequest(member.username))
        }.body<PreloginResponse>()
        return PasswordKeys.derive(member.password, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit)).authKey
    }

    @Test
    fun spacesAndCapitalsDoNotBuyMorePasswordGuesses() = serverTest {
        members("alice")
        val spellings = listOf("alice", " alice", "alice ", "ALICE", "Alice\t", "  aLiCe  ", "\nalice", "alice\n", "aLICE", " ALICE ")
        spellings.forEach { assertEquals(it, HttpStatusCode.Unauthorized, login(it).status) }
        // The eleventh guess is refused however the name is written.
        assertEquals(HttpStatusCode.TooManyRequests, login("   Alice").status)
        assertEquals(HttpStatusCode.TooManyRequests, login("alice").status)
    }

    @Test
    fun aNameThatCannotBeAnAccountNeverBecomesALimiterKey() = serverTest {
        members("alice")
        val before = services.limiter.size
        repeat(20) { assertEquals(HttpStatusCode.Unauthorized, login("x".repeat(5000) + it).status) }
        // Only the per-address counter grew: one key, not twenty huge ones.
        assertTrue(services.limiter.size <= before + 1)
    }

    @Test
    fun wrongPasswordsFromEverywhereTogetherAreCapped() = serverTest {
        val (alice) = members("alice")
        repeat(200) { services.limiter.record("login-failed:alice", 60 * 60_000L) }
        assertEquals(HttpStatusCode.TooManyRequests, alice.loginFresh().status)
        clock.advance(61 * 60_000L)
        assertEquals(HttpStatusCode.OK, alice.loginFresh().status)
    }

    @Test
    fun theLimiterForgetsQuietKeys() {
        val clock = MutableClock()
        val limiter = RateLimiter(clock)
        repeat(500) { limiter.check("k$it", 5, 60_000) }
        assertEquals(500, limiter.size)
        assertFalse(limiter.exhausted("k1", 5, 60_000))
        assertEquals("asking doesn't make a key", 500, limiter.size)
        clock.advance(120_000)
        limiter.check("fresh", 5, 60_000)
        assertEquals(1, limiter.size)
        repeat(4) { limiter.check("fresh", 5, 60_000) }
        assertFalse(limiter.allow("fresh", 5, 60_000))
    }

    @Test
    fun aBodySentInChunksIsCutOffLikeAnyOther() = serverTest {
        // No Content-Length to check up front: the limit has to hold while the body is read.
        val response = http.post("/api/v1/auth/prelogin") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeFully("{\"username\":\"".encodeToByteArray())
                    val filler = ByteArray(64 * 1024) { 'a'.code.toByte() }
                    repeat(96) { channel.writeFully(filler) }
                    channel.writeFully("\"}".encodeToByteArray())
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun resettingKeysSignsOutEveryOtherDevice() = serverTest {
        val (alice) = members("alice")
        val first = alice.token
        assertEquals(HttpStatusCode.OK, alice.loginFresh().status)
        val second = alice.token

        val fresh = AccountKeys.generate()
        val salt = PasswordKeys.newSalt()
        val derived = PasswordKeys.derive("a new password, long", salt, KdfParams.FAST_FOR_TESTS)
        val pre = authKeyOf(alice)
        val pub = fresh.publicKeys()
        val reset = alice.post(
            "/api/v1/auth/reset-keys",
            ResetKeysRequest(
                currentAuthKey = B64.encode(pre), salt = B64.encode(salt), opsLimit = KdfParams.FAST_FOR_TESTS.opsLimit,
                memLimit = KdfParams.FAST_FOR_TESTS.memLimitBytes, authKey = B64.encode(derived.authKey), signPk = B64.encode(pub.signPk),
                boxPk = B64.encode(pub.boxPk), boxPkSig = B64.encode(pub.boxPkSig), vault = B64.encode(fresh.sealVault(derived.vaultKey)),
                rewraps = emptyList(),
            ),
        )
        assertEquals(HttpStatusCode.OK, reset.status)
        assertEquals(second, alice.token)
        assertEquals(HttpStatusCode.OK, alice.get("/api/v1/me").status)
        alice.token = first
        assertEquals("the other device is signed out", HttpStatusCode.Unauthorized, alice.get("/api/v1/me").status)
    }

    @Test
    fun aStolenSessionCannotGuessThePasswordWithoutLimit() = serverTest {
        val (alice) = members("alice")
        val wrong = xyz.aezdek.hush.protocol.DeleteAccountRequest(B64.encode(ByteArray(32)))
        repeat(10) { assertEquals(HttpStatusCode.Forbidden, alice.post("/api/v1/me/delete", wrong).status) }
        assertEquals(HttpStatusCode.TooManyRequests, alice.post("/api/v1/me/delete", wrong).status)
    }

    @Test
    fun aRemovedFollowerCannotKeepAPostByPointingAtIt() = serverTest {
        val (alice, bob) = members("alice", "bob")
        alice.patch("/api/v1/me", UpdateMeRequest(isPrivate = true))
        bob.post("/api/v1/follows/${alice.id}", null)
        alice.post("/api/v1/follows/requests/${bob.id}/accept", null)
        val (post) = alice.seal("post", "followers", "for followers", listOf(bob))
        assertEquals(HttpStatusCode.Created, alice.create(post).status)
        assertEquals(HttpStatusCode.OK, bob.get("/api/v1/objects/${post.id}").status)

        // Bob saves it in a collection of his own, which refers to the post.
        val (saved) = bob.seal("collection", "self", "{}", refIds = listOf(post.id))
        assertEquals(HttpStatusCode.Created, bob.create(saved).status)

        alice.delete("/api/v1/followers/${bob.id}")
        assertEquals("his own reference opens nothing", HttpStatusCode.NotFound, bob.get("/api/v1/objects/${post.id}").status)
        bob.sync()
        assertTrue(post.id in bob.deleted)
    }

    private class Chat(val threadId: String, val threadKey: ByteArray, val epochId: String, val epochKey: ByteArray)

    private suspend fun Harness.startChat(creator: Member, others: List<Member>, group: Boolean): Chat {
        val threadId = newId()
        val epochId = newId()
        val members = others + creator
        val (thread, threadKey) = creator.seal("thread", "thread", "{}", members, objectId = threadId, threadId = threadId)
        val (epoch, epochKey) = creator.seal("epoch", "thread", "{}", members, objectId = epochId, threadId = threadId)
        assertEquals(HttpStatusCode.Created, creator.post("/api/v1/threads", CreateThreadRequest(group, others.map { it.id }, thread, epoch)).status)
        return Chat(threadId, threadKey, epochId, epochKey)
    }

    private fun Member.message(chat: Chat, text: String) = newId().let { id ->
        seal("message", "thread", text, objectId = id, parentId = chat.epochId, threadId = chat.threadId, key = ObjectCrypto.childKey(chat.epochKey, id), wrap = false).first
    }

    @Test
    fun blockingSomeoneStopsTheirMessagesInTheChatYouHad() = serverTest {
        val (alice, bob, carol) = members("alice", "bob", "carol")
        val direct = startChat(alice, listOf(bob), group = false)
        val group = startChat(alice, listOf(bob, carol), group = true)
        assertEquals(HttpStatusCode.Created, bob.create(bob.message(direct, "hello")).status)

        alice.post("/api/v1/blocks/${bob.id}", null)
        assertEquals(HttpStatusCode.Forbidden, bob.create(bob.message(direct, "still there?")).status)
        assertEquals(HttpStatusCode.Forbidden, alice.create(alice.message(direct, "it cuts both ways")).status)
        // A group they share carries on.
        assertEquals(HttpStatusCode.Created, bob.create(bob.message(group, "hi all")).status)

        alice.delete("/api/v1/blocks/${bob.id}")
        assertEquals(HttpStatusCode.Created, bob.create(bob.message(direct, "back")).status)
    }

    @Test
    fun nobodyIsAddedToAGroupAcrossABlock() = serverTest {
        val (alice, bob, carol) = members("alice", "bob", "carol")
        val group = startChat(alice, listOf(bob), group = true)
        carol.post("/api/v1/blocks/${alice.id}", null)
        val (epoch) = alice.seal("epoch", "thread", "{}", listOf(bob, carol), threadId = group.threadId)
        val wrap = WrapDto(carol.id, B64.encode(ObjectCrypto.wrap(group.threadKey, carol.keys.boxPk)))
        val added = alice.post("/api/v1/threads/${group.threadId}/members", AddMembersRequest(listOf(carol.id), epoch, listOf(wrap)))
        assertEquals(HttpStatusCode.Forbidden, added.status)
    }

    @Test
    fun pushGoesOnlyToTheBrowsersOwnPushServices() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val key = B64.encode(WebPush.uncompressed(WebPush.newP256().public as java.security.interfaces.ECPublicKey))
        val auth = B64.encode(ByteArray(16))
        suspend fun subscribe(who: Member, endpoint: String) = who.post("/api/v1/push/subscriptions", PushSubscriptionRequest(endpoint, key, auth)).status

        for (bad in listOf(
            "https://127.0.0.1/hook", "https://localhost:8096/", "http://fcm.googleapis.com/fcm/send/x", "https://immich.local/api",
            "https://fcm.googleapis.com.evil.example/x", "https://evil.example/fcm.googleapis.com", "https://user@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x", "https://169.254.169.254/latest/meta-data", "not a url",
        )) {
            assertEquals(bad, HttpStatusCode.BadRequest, subscribe(alice, bad))
        }
        for (good in listOf(
            "https://fcm.googleapis.com/fcm/send/abc", "https://updates.push.services.mozilla.com/wpush/v2/abc",
            "https://web.push.apple.com/abc", "https://wns2-par02p.notify.windows.com/w/?token=abc",
        )) {
            assertEquals(good, HttpStatusCode.OK, subscribe(alice, good))
        }
        // Someone else's endpoint can't be taken over by naming it.
        assertEquals(HttpStatusCode.BadRequest, subscribe(bob, "https://fcm.googleapis.com/fcm/send/abc"))
    }

    @Test
    fun unusedDevicesAreSignedOutAfterNinetyDays() = serverTest {
        val (alice) = members("alice")
        val old = alice.token
        clock.advance(60L * 24 * 3_600_000)
        assertEquals(HttpStatusCode.OK, alice.loginFresh().status)
        val recent = alice.token
        clock.advance(45L * 24 * 3_600_000)
        assertEquals(1, services.accounts.expireIdleSessions())
        assertEquals(HttpStatusCode.OK, alice.get("/api/v1/me").status)
        alice.token = old
        assertEquals(HttpStatusCode.Unauthorized, alice.get("/api/v1/me").status)
        alice.token = recent
    }

    @Test
    fun uploadsInProgressAreCapped() = serverTest {
        val (alice) = members("alice")
        repeat(64) { assertEquals(HttpStatusCode.OK, alice.post("/api/v1/uploads", StartUploadRequest(1024)).status) }
        val refused = alice.post("/api/v1/uploads", StartUploadRequest(1024))
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals("too_many_uploads", refused.body<ApiError>().error)
    }

    @Test
    fun aPostCanBeSealedForEveryMemberOfTheLargestServer() = serverTest {
        val (alice) = members("alice")
        val (post) = alice.seal("post", "members", "x")
        // 50 members is the most the settings allow; the server must take a key for each.
        val many = post.copy(wraps = post.wraps + List(49) { WrapDto("nobody$it", post.wraps[0].sealed) })
        val response = alice.create(many)
        // Refused for the unknown recipients, not for there being too many.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Unknown recipient", response.body<ApiError>().message)
    }

    @Test
    fun theJobLogIsOnlyShownWhileTheControlsAreUnlocked() = serverTest {
        val password = "the server password, long"
        Vault(services.config).setup(password)
        val (admin) = members("admin")
        File(dataDir, "control").mkdirs()
        File(dataDir, "control/last.log").writeText("==> Hush 1.0.1 is up\n")
        assertNull(admin.get("/api/v1/admin/server").body<AdminServerDto>().log)
        assertEquals(HttpStatusCode.OK, admin.post("/api/v1/admin/server/unlock", UnlockRequest(password)).status)
        assertNotNull(admin.get("/api/v1/admin/server").body<AdminServerDto>().log)
    }

    @Test
    fun lookingTwiceIsOneView() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val (story) = alice.seal("story", "followers", "x", listOf(bob), expiresAt = clock.now() + 3_600_000L)
        alice.create(story)
        alice.sync()
        repeat(5) { assertEquals(HttpStatusCode.OK, bob.post("/api/v1/objects/${story.id}/views", null).status) }
        val changes = services.db.tx { long("SELECT COUNT(*) FROM changes WHERE user_id = ? AND seq > ?", alice.id, alice.cursor) }
        assertEquals(1L, changes)
    }
}
