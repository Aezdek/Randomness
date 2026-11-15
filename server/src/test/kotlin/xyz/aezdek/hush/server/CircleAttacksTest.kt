package xyz.aezdek.hush.server

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.crypto.ObjectCrypto
import xyz.aezdek.hush.crypto.PasswordKeys
import xyz.aezdek.hush.protocol.AccountStatus
import xyz.aezdek.hush.protocol.AddPasskeyRequest
import xyz.aezdek.hush.protocol.AuthResponse
import xyz.aezdek.hush.protocol.CreateThreadRequest
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.LoginRequest
import xyz.aezdek.hush.protocol.PasskeyLoginRequest
import xyz.aezdek.hush.protocol.PreloginRequest
import xyz.aezdek.hush.protocol.PreloginResponse
import xyz.aezdek.hush.protocol.SessionDto
import xyz.aezdek.hush.protocol.SetStatusRequest
import xyz.aezdek.hush.protocol.SignInDto
import java.security.SecureRandom

/**
 * What someone with a stolen password, or a member with a modified app, could try against sign-in alerts, passkeys,
 * plans, live location and the times things claim to have been made.
 */
class CircleAttacksTest {
    private val random = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private suspend fun Harness.authKeyOf(member: Member): ByteArray {
        val pre = http.post("/api/v1/auth/prelogin") {
            contentType(ContentType.Application.Json)
            setBody(PreloginRequest(member.username))
        }.body<PreloginResponse>()
        return PasswordKeys.derive(member.password, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit)).authKey
    }

    /** A sign-in from another device with the right password, under a name of the attacker's choosing. Returns its token. */
    private suspend fun Harness.signInAs(member: Member, device: String, platform: String = "web"): String {
        val response = http.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(member.username, B64.encode(authKeyOf(member)), DeviceInfo(device, platform)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body<AuthResponse>().token
    }

    private suspend fun Harness.signIns(token: String) =
        http.get("/api/v1/auth/sign-ins") { header(HttpHeaders.Authorization, "Bearer $token") }.body<List<SignInDto>>()

    // ------------------------------------------------------------------------------------------------ sign-in alerts

    @Test
    fun aQuickSignInAndOutStillLeavesATrace() = serverTest {
        val (alice) = members("alice")
        // Someone with her password signs in, copies what they want, and signs out again within the second.
        val intruder = signInAs(alice, "Chrome on Windows")
        assertEquals(HttpStatusCode.OK, http.post("/api/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer $intruder") }.status)
        // Her own device looks afterwards: the session list shows nothing, but the sign-in is on record.
        assertEquals(1, alice.get("/api/v1/auth/sessions").body<List<SessionDto>>().size)
        val seen = signIns(alice.token).filter { !it.current }
        assertEquals(listOf("Chrome on Windows"), seen.map { it.deviceName })
        assertFalse("it has already gone", seen.single().active)
    }

    @Test
    fun throwingTheOwnerOutDoesNotHideTheSignIn() = serverTest {
        val (alice) = members("alice")
        val owner = alice.token
        // The intruder signs in and at once signs the owner's device out, before it can ask "Was this you?".
        val intruder = signInAs(alice, "Firefox on Linux")
        val mine = http.get("/api/v1/auth/sessions") { header(HttpHeaders.Authorization, "Bearer $intruder") }.body<List<SessionDto>>()
        val owners = mine.single { !it.current }
        assertEquals(HttpStatusCode.OK, http.delete("/api/v1/auth/sessions/${owners.id}") { header(HttpHeaders.Authorization, "Bearer $intruder") }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $owner") }.status)
        // The owner signs in again and is shown the sign-in they never made, still active, and can end it.
        val back = signInAs(alice, "Hush on Pixel", "android")
        val others = signIns(back).filter { !it.current }
        val theirs = others.single { it.deviceName == "Firefox on Linux" }
        assertTrue(theirs.active)
        assertEquals(HttpStatusCode.OK, http.delete("/api/v1/auth/sessions/${theirs.sessionId}") { header(HttpHeaders.Authorization, "Bearer $back") }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $intruder") }.status)
        assertFalse(signIns(back).single { it.deviceName == "Firefox on Linux" }.active)
    }

    @Test
    fun theSignInRecordIsOnesOwnAndForgetsAfterAMonth() = serverTest {
        val (alice, bob) = members("alice", "bob")
        signInAs(alice, "Laptop")
        assertTrue(signIns(bob.token).none { it.deviceName == "Laptop" })
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/v1/auth/sign-ins").status)
        clock.advance(31L * 24 * 60 * 60 * 1000)
        services.accounts.expireIdleSessions()
        // The old sessions have aged out by now as well; a fresh one sees only its own sign-in.
        val fresh = signInAs(alice, "Later")
        assertEquals(listOf("Later"), signIns(fresh).map { it.deviceName })
    }

    @Test
    fun aDeviceNameCannotDressItselfUp() = serverTest {
        val (alice) = members("alice")
        // Control characters, new lines and right-to-left overrides could make an alert read as something else.
        val token = signInAs(alice, "Pixel‮gnp.exe\n\nThat was you\u0007 ⁦ok⁩‏")
        val name = http.get("/api/v1/auth/sessions") { header(HttpHeaders.Authorization, "Bearer $token") }
            .body<List<SessionDto>>().single { it.current }.deviceName
        assertTrue("got \"$name\"", name.none { it.isISOControl() || it in "‪‫‬‭‮⁦⁧⁨⁩‎‏" })
        assertTrue(name.startsWith("Pixel"))
        // A name of nothing but those is still a name.
        val blank = signInAs(alice, "‮\n\t")
        assertEquals("Unknown device", http.get("/api/v1/auth/sessions") { header(HttpHeaders.Authorization, "Bearer $blank") }
            .body<List<SessionDto>>().single { it.current }.deviceName)
    }

    // ------------------------------------------------------------------------------------------------------ passkeys

    @Test
    fun aPasskeyDiesWithItsAccountAndTellsNothingAboutIt() = serverTest {
        val (admin, bob, cara) = members("alice", "bob", "cara")
        suspend fun passkeyFor(member: Member): Pair<String, ByteArray> {
            val id = B64.encode(bytes(32))
            val key = bytes(32)
            assertEquals(HttpStatusCode.OK, member.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(authKeyOf(member)), id, "Phone", B64.encode(key), B64.encode(bytes(64)))).status)
            return id to key
        }
        suspend fun login(id: String, key: ByteArray) = http.post("/api/v1/auth/passkey") {
            contentType(ContentType.Application.Json)
            setBody(PasskeyLoginRequest(id, B64.encode(key), DeviceInfo("Laptop", "web")))
        }
        val (bobId, bobKey) = passkeyFor(bob)
        val (caraId, caraKey) = passkeyFor(cara)
        // A paused account: the right passkey is told so, a wrong one learns nothing (not even that the account is paused).
        assertEquals(HttpStatusCode.OK, admin.post("/api/v1/admin/users/${bob.id}/status", SetStatusRequest(AccountStatus.DISABLED)).status)
        assertEquals(HttpStatusCode.Forbidden, login(bobId, bobKey).status)
        assertEquals(HttpStatusCode.Unauthorized, login(bobId, bytes(32)).status)
        // A removed member's passkey is gone with them.
        assertEquals(HttpStatusCode.OK, admin.delete("/api/v1/admin/users/${cara.id}").status)
        assertEquals(HttpStatusCode.Unauthorized, login(caraId, caraKey).status)
        // One member's passkey secret never opens another's account.
        assertEquals(HttpStatusCode.Unauthorized, login(bobId, caraKey).status)
    }

    // ----------------------------------------------------------------------------------------- plans and live location

    private class Chat(val threadId: String, val epochId: String, val epochKey: ByteArray)

    private suspend fun Harness.startChat(creator: Member, others: List<Member>): Chat {
        val threadId = newId()
        val epochId = newId()
        val everyone = others + creator
        val (thread, _) = creator.seal("thread", "thread", "{}", everyone, objectId = threadId, threadId = threadId)
        val (epoch, epochKey) = creator.seal("epoch", "thread", "{}", everyone, objectId = epochId, threadId = threadId)
        assertEquals(HttpStatusCode.Created, creator.post("/api/v1/threads", CreateThreadRequest(others.size > 1, others.map { it.id }, thread, epoch)).status)
        return Chat(threadId, epochId, epochKey)
    }

    @Test
    fun aPlanIsAnsweredOnlyFromInsideTheChat() = serverTest {
        val (alice, bob, carol) = members("alice", "bob", "carol")
        val chat = startChat(alice, listOf(bob))
        val planId = newId()
        val planKey = ObjectCrypto.childKey(chat.epochKey, planId)
        val plan = alice.seal("message", "thread", """{"type":"plan","plan":{"title":"Dinner","at":1}}""", objectId = planId, parentId = chat.epochId,
            threadId = chat.threadId, key = planKey, wrap = false).first
        assertEquals(HttpStatusCode.Created, alice.create(plan).status)
        fun Member.answer(option: Int) = newId().let { id ->
            seal("vote", "parent", """{"stickerId":"plan","option":$option}""", objectId = id, parentId = planId, threadId = chat.threadId,
                key = ObjectCrypto.childKey(planKey, id), wrap = false).first
        }
        assertEquals(HttpStatusCode.Created, bob.create(bob.answer(0)).status)
        // Carol isn't in the chat: she can't answer, see the plan, or see who answered.
        assertTrue(carol.create(carol.answer(0)).status in setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound))
        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/objects/$planId").status)
        carol.sync()
        assertTrue(carol.received.values.none { it.threadId == chat.threadId })
        // Bob can't take Alice's plan down or rewrite it.
        assertTrue(bob.delete("/api/v1/objects/$planId").status in setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound))
        val forged = bob.seal("message", "thread", """{"type":"plan","plan":{"title":"Cancelled","at":1}}""", objectId = planId, parentId = chat.epochId,
            threadId = chat.threadId, key = planKey, wrap = false, version = 2, createdAt = plan.createdAt).first
        assertTrue(bob.put("/api/v1/objects/$planId", forged).status in setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound))
    }

    @Test
    fun liveLocationEndsWhenItSaidItWouldAndOnlyItsOwnerMovesIt() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val chat = startChat(alice, listOf(bob))
        val id = newId()
        val key = ObjectCrypto.childKey(chat.epochKey, id)
        val until = clock.now() + 15 * 60_000
        fun Member.location(version: Int, expiresAt: Long?, createdAt: Long = clock.now()) =
            seal("message", "thread", """{"type":"location","location":{"lat":1.0,"lng":2.0,"until":$until,"updatedAt":1}}""", objectId = id,
                parentId = chat.epochId, threadId = chat.threadId, key = key, wrap = false, version = version, expiresAt = expiresAt, createdAt = createdAt).first
        val first = alice.location(1, until)
        assertEquals(HttpStatusCode.Created, alice.create(first).status)
        // Moving keeps the end; it can't be pushed later or made permanent.
        assertEquals(HttpStatusCode.OK, alice.put("/api/v1/objects/$id", alice.location(2, until, first.createdAt)).status)
        assertEquals(HttpStatusCode.BadRequest, alice.put("/api/v1/objects/$id", alice.location(3, until + 60 * 60_000, first.createdAt)).status)
        assertEquals(HttpStatusCode.BadRequest, alice.put("/api/v1/objects/$id", alice.location(3, null, first.createdAt)).status)
        // Bob can read it but not move or end it.
        assertTrue(bob.put("/api/v1/objects/$id", bob.location(3, until, first.createdAt)).status in setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound))
        assertTrue(bob.delete("/api/v1/objects/$id").status in setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound))
        // When the time is up it is gone from the server, whatever the clients do.
        clock.advance(16 * 60_000)
        services.jobs.expireNow()
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/$id").status)
    }

    // ---------------------------------------------------------------------------------------------------------- time

    @Test
    fun aPostCannotClaimToBeFromTheFutureOrTheDistantPast() = serverTest {
        val (alice) = members("alice")
        val now = clock.now()
        // Dated far ahead it would sit on top of every follower's feed for good; out-of-range dates break date handling.
        for (bad in listOf(now + 2 * 24 * 60 * 60_000L, Long.MAX_VALUE, -1L, 0L, now - 3 * 24 * 60 * 60_000L)) {
            val status = alice.create(alice.seal("post", "followers", """{"media":[]}""", createdAt = bad).first).status
            assertEquals("createdAt=$bad", HttpStatusCode.BadRequest, status)
        }
        // A clock that is a little off is fine.
        assertEquals(HttpStatusCode.Created, alice.create(alice.seal("post", "followers", """{"media":[]}""", createdAt = now + 5 * 60_000).first).status)
        assertEquals(HttpStatusCode.Created, alice.create(alice.seal("post", "followers", """{"media":[]}""", createdAt = now - 60 * 60_000).first).status)
    }
}
