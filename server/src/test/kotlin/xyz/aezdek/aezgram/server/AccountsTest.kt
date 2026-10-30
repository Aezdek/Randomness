package xyz.aezdek.aezgram.server

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.aezgram.crypto.AccountKeys
import xyz.aezdek.aezgram.crypto.B64
import xyz.aezdek.aezgram.crypto.KdfParams
import xyz.aezdek.aezgram.crypto.PasswordKeys
import xyz.aezdek.aezgram.protocol.AdminStatus
import xyz.aezdek.aezgram.protocol.ChangePasswordRequest
import xyz.aezdek.aezgram.protocol.Me
import xyz.aezdek.aezgram.protocol.PreloginRequest
import xyz.aezdek.aezgram.protocol.PreloginResponse
import xyz.aezdek.aezgram.protocol.SessionDto
import xyz.aezdek.aezgram.protocol.UserDto

class AccountsTest {

    @Test
    fun firstMemberIsAdminAndNewDevicesRestoreKeysFromTheVault() = serverTest {
        val (alice, bob) = members("alice", "bob")
        assertTrue(alice.get("/api/v1/me").body<Me>().isAdmin)
        assertFalse(bob.get("/api/v1/me").body<Me>().isAdmin)

        val original = alice.keys
        val response = alice.loginFresh()
        assertEquals(HttpStatusCode.OK, response.status)
        assertArrayEquals(original.signSk, alice.keys.signSk)
        assertArrayEquals(original.boxSk, alice.keys.boxSk)
        assertEquals(2, alice.get("/api/v1/auth/sessions").body<List<SessionDto>>().size)

        assertEquals(HttpStatusCode.Unauthorized, alice.loginFresh("wrong password").status)
    }

    @Test
    fun preloginDoesNotRevealWhetherAUsernameExists() = serverTest {
        members("alice")
        suspend fun prelogin(name: String) = http.post("/api/v1/auth/prelogin") {
            contentType(ContentType.Application.Json)
            setBody(PreloginRequest(name))
        }.body<PreloginResponse>()
        val ghost1 = prelogin("ghost")
        val ghost2 = prelogin("GHOST")
        assertEquals(ghost1, ghost2)
        assertEquals(16, B64.decode(ghost1.salt).size)
        assertEquals(DEFAULT_OPS_LIMIT, ghost1.opsLimit)
        assertFalse(ghost1.salt == prelogin("alice").salt)
    }

    @Test
    fun invitesAreSingleUseAndTheInstanceHoldsTenPeople() = serverTest {
        val people = members(*Array(10) { "user$it" })
        val first = firstInvite()
        val reuse = Member(this, "late")
        val reused = runCatching { reuse.signup(first) }
        assertTrue(reused.isFailure)

        val invite = newInvite(people[0])
        val eleventh = runCatching { Member(this, "eleven").signup(invite) }
        assertTrue(eleventh.isFailure)
        assertEquals(10, people[0].get("/api/v1/users").body<List<UserDto>>().size)
    }

    @Test
    fun usernamesAreValidatedAndUnique() = serverTest {
        val (alice) = members("alice")
        assertTrue(runCatching { Member(this, "Bad Name!").signup(newInvite(alice)) }.isFailure)
        assertTrue(runCatching { Member(this, "ALICE").signup(newInvite(alice)) }.isFailure)
    }

    @Test
    fun passwordChangeResealsTheSameKeysAndSignsOutOtherDevices() = serverTest {
        val (alice) = members("alice")
        val firstToken = alice.token
        alice.loginFresh()
        val secondToken = alice.token
        val oldKeys = alice.keys

        val oldDerived = run {
            val pre = alice.post("/api/v1/auth/prelogin", PreloginRequest("alice")).body<PreloginResponse>()
            PasswordKeys.derive(alice.password, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit))
        }
        val salt = PasswordKeys.newSalt()
        val derived = PasswordKeys.derive("a brand new password", salt, KdfParams.FAST_FOR_TESTS)
        val response = alice.post(
            "/api/v1/auth/password",
            ChangePasswordRequest(
                B64.encode(oldDerived.authKey), B64.encode(salt), KdfParams.FAST_FOR_TESTS.opsLimit, KdfParams.FAST_FOR_TESTS.memLimitBytes,
                B64.encode(derived.authKey), B64.encode(oldKeys.sealVault(derived.vaultKey)),
            ),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        // The other device is signed out; this one still works.
        alice.token = firstToken
        assertEquals(HttpStatusCode.Unauthorized, alice.get("/api/v1/me").status)
        alice.token = secondToken
        assertEquals(HttpStatusCode.OK, alice.get("/api/v1/me").status)
        // The new password opens the same keys.
        assertEquals(HttpStatusCode.Unauthorized, alice.loginFresh().status)
        assertEquals(HttpStatusCode.OK, alice.loginFresh("a brand new password").status)
        assertArrayEquals(oldKeys.signSk, alice.keys.signSk)
    }

    @Test
    fun revokedSessionsStopWorking() = serverTest {
        val (alice) = members("alice")
        val first = alice.token
        alice.loginFresh()
        val sessions = alice.get("/api/v1/auth/sessions").body<List<SessionDto>>()
        val other = sessions.first { !it.current }
        assertEquals(HttpStatusCode.OK, alice.delete("/api/v1/auth/sessions/${other.id}").status)
        alice.token = first
        assertEquals(HttpStatusCode.Unauthorized, alice.get("/api/v1/me").status)
    }

    @Test
    fun adminStatusReportsBackupState() = serverTest {
        val (alice, bob) = members("alice", "bob")
        assertEquals(HttpStatusCode.Forbidden, bob.get("/api/v1/admin/status").status)
        var status = alice.get("/api/v1/admin/status").body<AdminStatus>()
        assertNull(status.lastBackupAt)
        assertEquals(2, status.users)
        services.config.backupStatusFile.writeText("""{"lastSuccessAt": 1700000000, "ok": true, "message": "12 files"}""")
        status = alice.get("/api/v1/admin/status").body()
        assertEquals(1_700_000_000_000, status.lastBackupAt)
        assertEquals(true, status.lastBackupOk)
    }

    @Test
    fun deletingAnAccountRemovesItsContentAndFreesTheName() = serverTest {
        val (alice, bob) = members("alice", "bob")
        alice.sync()
        val pre = bob.post("/api/v1/auth/prelogin", PreloginRequest("bob")).body<PreloginResponse>()
        val derived = PasswordKeys.derive(bob.password, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit))
        val (post) = bob.seal("post", "members", "bye", listOf(alice))
        bob.create(post)
        alice.sync()
        assertTrue(post.id in alice.received)
        bob.post("/api/v1/me/delete", xyz.aezdek.aezgram.protocol.DeleteAccountRequest(B64.encode(derived.authKey)))
        alice.sync()
        assertTrue(post.id in alice.deleted)
        assertTrue(alice.users.getValue(bob.id).deleted)
        Member(this, "bob").signup(newInvite(alice))
        // A leftover key does not break anything.
        AccountKeys.generate()
    }
}
