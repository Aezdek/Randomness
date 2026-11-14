package xyz.aezdek.hush.server

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.crypto.AccountKeys
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.crypto.PasswordKeys
import xyz.aezdek.hush.protocol.AddPasskeyRequest
import xyz.aezdek.hush.protocol.AdminAlertDto
import xyz.aezdek.hush.protocol.AuthResponse
import xyz.aezdek.hush.protocol.CreateThreadRequest
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.PasskeyDto
import xyz.aezdek.hush.protocol.PasskeyLoginRequest
import xyz.aezdek.hush.protocol.PreloginRequest
import xyz.aezdek.hush.protocol.PreloginResponse
import xyz.aezdek.hush.protocol.PushPrefs
import xyz.aezdek.hush.protocol.PushType
import xyz.aezdek.hush.protocol.ResetKeysRequest
import xyz.aezdek.hush.protocol.Wire
import xyz.aezdek.hush.protocol.WsFrame
import xyz.aezdek.hush.protocol.WsType
import java.security.SecureRandom

/** Attacks on what was added with passkeys, sign-in alerts, private pushes, notes to self and admin alerts. */
class NewFeaturesSecurityTest {
    private val random = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun credential() = B64.encode(bytes(32))

    private suspend fun Harness.authKeyOf(member: Member): ByteArray {
        val pre = http.post("/api/v1/auth/prelogin") {
            contentType(ContentType.Application.Json)
            setBody(PreloginRequest(member.username))
        }.body<PreloginResponse>()
        return PasswordKeys.derive(member.password, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit)).authKey
    }

    private suspend fun Harness.passkeyLogin(credentialId: String, authKey: ByteArray) = http.post("/api/v1/auth/passkey") {
        contentType(ContentType.Application.Json)
        setBody(PasskeyLoginRequest(credentialId, B64.encode(authKey), DeviceInfo("Laptop", "web")))
    }

    @Test
    fun aPasskeySignsInWithItsOwnSealedKeysAndNothingElseDoes() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val credentialId = credential()
        val passkeyAuth = bytes(32)
        val vault = bytes(200)
        val added = alice.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(authKeyOf(alice)), credentialId, "MacBook", B64.encode(passkeyAuth), B64.encode(vault)))
        assertEquals(HttpStatusCode.OK, added.status)

        val ok = passkeyLogin(credentialId, passkeyAuth)
        assertEquals(HttpStatusCode.OK, ok.status)
        val auth = ok.body<AuthResponse>()
        assertEquals(alice.id, auth.me.id)
        assertTrue("the passkey's own copy of the keys", B64.decode(auth.vault).contentEquals(vault))

        // A wrong secret, someone else's id, or junk: refused the same way, so ids can't be probed.
        assertEquals(HttpStatusCode.Unauthorized, passkeyLogin(credentialId, bytes(32)).status)
        assertEquals(HttpStatusCode.Unauthorized, passkeyLogin(credential(), passkeyAuth).status)
        assertEquals(HttpStatusCode.Unauthorized, passkeyLogin("../../etc", passkeyAuth).status)
        // Bob can't list, delete or reuse Alice's passkeys.
        assertEquals(emptyList<PasskeyDto>(), bob.get("/api/v1/auth/passkeys").body<List<PasskeyDto>>())
        val id = alice.get("/api/v1/auth/passkeys").body<List<PasskeyDto>>().single().id
        assertEquals(HttpStatusCode.NotFound, bob.delete("/api/v1/auth/passkeys/$id").status)
        val again = bob.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(authKeyOf(bob)), credentialId, "x", B64.encode(bytes(32)), B64.encode(bytes(50))))
        assertEquals(HttpStatusCode.Conflict, again.status)
    }

    @Test
    fun aStolenSessionCannotAddAPasskey() = serverTest {
        val (alice) = members("alice")
        val stolen = alice.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(bytes(32)), credential(), "attacker", B64.encode(bytes(32)), B64.encode(bytes(64))))
        assertEquals(HttpStatusCode.Forbidden, stolen.status)
        // Guessing the password through this door runs into the password's own limit.
        repeat(9) { alice.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(bytes(32)), credential(), "x", B64.encode(bytes(32)), B64.encode(bytes(64)))) }
        assertEquals(HttpStatusCode.TooManyRequests, alice.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(bytes(32)), credential(), "x", B64.encode(bytes(32)), B64.encode(bytes(64)))).status)
        assertEquals(emptyList<PasskeyDto>(), alice.get("/api/v1/auth/passkeys").body<List<PasskeyDto>>())
    }

    @Test
    fun passkeyGuessingIsLimited() = serverTest {
        val (alice) = members("alice")
        val credentialId = credential()
        alice.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(authKeyOf(alice)), credentialId, "Phone", B64.encode(bytes(32)), B64.encode(bytes(64))))
        repeat(20) { assertEquals(HttpStatusCode.Unauthorized, passkeyLogin(credentialId, bytes(32)).status) }
        assertEquals(HttpStatusCode.TooManyRequests, passkeyLogin(credentialId, bytes(32)).status)
    }

    @Test
    fun newKeysRetireEveryPasskey() = serverTest {
        val (alice) = members("alice")
        val credentialId = credential()
        val passkeyAuth = bytes(32)
        alice.post("/api/v1/auth/passkeys", AddPasskeyRequest(B64.encode(authKeyOf(alice)), credentialId, "Phone", B64.encode(passkeyAuth), B64.encode(bytes(64))))
        val fresh = AccountKeys.generate()
        val pub = fresh.publicKeys()
        val salt = PasswordKeys.newSalt()
        val derived = PasswordKeys.derive(alice.password, salt, KdfParams.FAST_FOR_TESTS)
        val reset = alice.post(
            "/api/v1/auth/reset-keys",
            ResetKeysRequest(B64.encode(authKeyOf(alice)), B64.encode(salt), KdfParams.FAST_FOR_TESTS.opsLimit, KdfParams.FAST_FOR_TESTS.memLimitBytes,
                B64.encode(derived.authKey), B64.encode(fresh.sealVault(derived.vaultKey)), B64.encode(pub.signPk), B64.encode(pub.boxPk), B64.encode(pub.boxPkSig), emptyList()),
        )
        assertEquals(HttpStatusCode.OK, reset.status)
        // Its sealed copy held the old keys: it is gone, and can't sign in.
        assertEquals(HttpStatusCode.Unauthorized, passkeyLogin(credentialId, passkeyAuth).status)
        assertEquals(emptyList<PasskeyDto>(), alice.get("/api/v1/auth/passkeys").body<List<PasskeyDto>>())
    }

    @Test
    fun aNewSignInIsToldToTheOtherDevicesAndTheyCanThrowItOut() = serverTest {
        val (alice) = members("alice")
        val first = alice.token
        http.webSocket("/api/v1/ws", request = { header(HttpHeaders.SecWebSocketProtocol, "hush.v1, auth.$first") }) {
            suspend fun next(): WsFrame = withTimeout(5_000) {
                Wire.json.decodeFromString(WsFrame.serializer(), (incoming.receive() as Frame.Text).readText())
            }
            assertEquals(WsType.SYNC, next().t)
            assertEquals(HttpStatusCode.OK, alice.loginFresh().status)
            var frame = next()
            while (frame.t == WsType.PRESENCE || frame.t == WsType.SYNC) frame = next()
            assertEquals(WsType.SESSION, frame.t)
        }
        // "Not me": the first device signs the newcomer out.
        val intruder = alice.token
        val list = http.get("/api/v1/auth/sessions") { header(HttpHeaders.Authorization, "Bearer $first") }
        val newcomer = list.body<List<xyz.aezdek.hush.protocol.SessionDto>>().single { !it.current && it.deviceName == "second" }
        val revoked = http.delete("/api/v1/auth/sessions/${newcomer.id}") { header(HttpHeaders.Authorization, "Bearer $first") }
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $intruder") }.status)
    }

    @Test
    fun securityAlertsCantBeMutedAndPrivatePushesNameNobody() = serverTest {
        val (alice) = members("alice")
        // Even muting them by name doesn't stop sign-in or server alerts.
        assertEquals(HttpStatusCode.OK, alice.put("/api/v1/push/prefs", PushPrefs(mutedTypes = listOf(PushType.SIGN_IN, PushType.ADMIN_ALERT, "message"), hideNames = true)).status)
        assertTrue(services.push.wanted(alice.id, PushMessage(PushType.SIGN_IN, ref = "Laptop")))
        assertTrue(services.push.wanted(alice.id, PushMessage(PushType.ADMIN_ALERT, ref = "disk")))
        assertFalse(services.push.wanted(alice.id, PushMessage("message", from = "bob", fromId = "x", ref = "t")))
        assertTrue(services.push.prefs(alice.id).hideNames)
        // Entries that could break the stored list are refused.
        assertEquals(HttpStatusCode.BadRequest, alice.put("/api/v1/push/prefs", PushPrefs(mutedTypes = listOf("a,b"))).status)
        assertEquals(HttpStatusCode.BadRequest, alice.put("/api/v1/push/prefs", PushPrefs(mutedThreads = listOf("x".repeat(65)))).status)
    }

    @Test
    fun notesToSelfIsOneChatThatNobodyElseCanEnter() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val threadId = newId()
        val (thread, _) = alice.seal("thread", "thread", "{}", listOf(alice), objectId = threadId, threadId = threadId)
        val (epoch, _) = alice.seal("epoch", "thread", "{}", listOf(alice), objectId = newId(), threadId = threadId)
        assertEquals(HttpStatusCode.Created, alice.post("/api/v1/threads", CreateThreadRequest(false, emptyList(), thread, epoch)).status)
        // A second one comes back as the same chat.
        val otherId = newId()
        val (thread2, _) = alice.seal("thread", "thread", "{}", listOf(alice), objectId = otherId, threadId = otherId)
        val (epoch2, _) = alice.seal("epoch", "thread", "{}", listOf(alice), objectId = newId(), threadId = otherId)
        assertEquals(HttpStatusCode.Conflict, alice.post("/api/v1/threads", CreateThreadRequest(false, emptyList(), thread2, epoch2)).status)
        // Bob can neither see it nor be added to it, and a group of one isn't a thing.
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/threads/$threadId").status)
        val groupId = newId()
        val (g, _) = alice.seal("thread", "thread", "{}", listOf(alice), objectId = groupId, threadId = groupId)
        val (ge, _) = alice.seal("epoch", "thread", "{}", listOf(alice), objectId = newId(), threadId = groupId)
        assertEquals(HttpStatusCode.BadRequest, alice.post("/api/v1/threads", CreateThreadRequest(true, emptyList(), g, ge)).status)
    }

    @Test
    fun adminAlertsAreForAdminsOnly() = serverTest {
        val (admin, bob) = members("admin", "bob")
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/admin/alerts").status)
        assertEquals(HttpStatusCode.Forbidden, bob.get("/api/v1/admin/alerts").status)
        // A full disk (the floor above what's free) is one alert, announced once.
        services.settings.update(services.settings.dto().copy(minFreeGb = 500), 2)
        val alerts = admin.get("/api/v1/admin/alerts").body<List<AdminAlertDto>>()
        assertTrue(alerts.any { it.kind == "disk" })
    }
}
