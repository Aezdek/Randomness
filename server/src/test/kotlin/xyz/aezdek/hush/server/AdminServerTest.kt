package xyz.aezdek.hush.server

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.protocol.AdminServerDto
import xyz.aezdek.hush.protocol.AdminStatus
import xyz.aezdek.hush.protocol.ApiError
import xyz.aezdek.hush.protocol.ControlStatusDto
import xyz.aezdek.hush.protocol.InviteDto
import xyz.aezdek.hush.protocol.ServerActionRequest
import xyz.aezdek.hush.protocol.ServerSettingsDto
import xyz.aezdek.hush.protocol.ServerUnlockResponse
import xyz.aezdek.hush.protocol.UnlockRequest
import xyz.aezdek.hush.protocol.WebAdminRequest
import java.io.File

class AdminServerTest {
    private val serverPassword = "the server password, long"

    /** The keyring update.sh would have made, so the server password can be checked. */
    private fun Harness.setServerPassword() = Vault(services.config).setup(serverPassword)

    @Test
    fun serverControlsNeedTheServerPasswordAndChangeTheSettings() = serverTest {
        setServerPassword()
        val (admin, bob, cara) = members("admin", "bob", "cara")

        val server = admin.get("/api/v1/admin/server").body<AdminServerDto>()
        assertEquals(10, server.settings.maxMembers)
        assertEquals(7, server.settings.inviteDays)
        assertTrue(server.settings.webAdmin)
        assertNull(server.unlockedUntil)
        assertFalse(server.agent)
        assertEquals(HttpStatusCode.Forbidden, bob.get("/api/v1/admin/server").status)

        // Nothing changes until the server password unlocks the controls.
        val settings = ServerSettingsDto(maxMembers = 3, inviteDays = 2, minFreeGb = 5)
        val locked = admin.put("/api/v1/admin/server/settings", settings)
        assertEquals(HttpStatusCode.Forbidden, locked.status)
        assertEquals("locked", locked.body<ApiError>().error)
        assertEquals(HttpStatusCode.Forbidden, admin.post("/api/v1/admin/server/unlock", UnlockRequest("not it at all")).status)
        assertEquals(HttpStatusCode.Forbidden, bob.post("/api/v1/admin/server/unlock", UnlockRequest(serverPassword)).status)
        val unlocked = admin.post("/api/v1/admin/server/unlock", UnlockRequest(serverPassword)).body<ServerUnlockResponse>()
        assertEquals(clock.now() + Elevation.UNLOCKED_MS, unlocked.unlockedUntil)

        // The limit can't go below the members there are, and values stay in range.
        assertEquals(HttpStatusCode.BadRequest, admin.put("/api/v1/admin/server/settings", settings.copy(maxMembers = 2)).status)
        assertEquals(HttpStatusCode.BadRequest, admin.put("/api/v1/admin/server/settings", settings.copy(inviteDays = 90)).status)
        val saved = admin.put("/api/v1/admin/server/settings", settings).body<ServerSettingsDto>()
        assertEquals(settings, saved)
        assertTrue(File(dataDir, "config.json").readText().contains("\"maxMembers\": 3") || File(dataDir, "config.json").readText().contains("\"maxMembers\":3"))

        // The new settings apply: Hush is full at three, and invites last two days.
        val status = admin.get("/api/v1/admin/status").body<AdminStatus>()
        assertEquals(3, status.maxUsers)
        assertEquals(2, status.inviteDays)
        val invite = admin.post("/api/v1/admin/invites", null).body<InviteDto>()
        assertEquals(2 * 24 * 60 * 60 * 1000L, invite.expiresAt - invite.createdAt)
        val dan = Member(this, "dan")
        assertEquals(HttpStatusCode.Forbidden, runCatching { dan.signup(invite.code) }.fold({ HttpStatusCode.OK }, { HttpStatusCode.Forbidden }))

        // The unlock lasts ten minutes, for that session only.
        clock.advance(Elevation.UNLOCKED_MS + 1)
        assertEquals(HttpStatusCode.Forbidden, admin.put("/api/v1/admin/server/settings", settings).status)
        assertNull(admin.get("/api/v1/admin/server").body<AdminServerDto>().unlockedUntil)
    }

    @Test
    fun actionsGoToTheControlHelperOneAtATime() = serverTest {
        setServerPassword()
        val (admin) = members("admin")
        admin.post("/api/v1/admin/server/unlock", UnlockRequest(serverPassword))

        // Without control.sh installed there is nobody to run them.
        val none = admin.post("/api/v1/admin/server/actions", ServerActionRequest("backup"))
        assertEquals(HttpStatusCode.Conflict, none.status)
        assertEquals("no_agent", none.body<ApiError>().error)

        val control = File(dataDir, "control").apply { mkdirs() }
        File(control, "agent").writeText("installed\n")
        assertEquals(HttpStatusCode.BadRequest, admin.post("/api/v1/admin/server/actions", ServerActionRequest("rm -rf /")).status)
        val queued = admin.post("/api/v1/admin/server/actions", ServerActionRequest("update")).body<ControlStatusDto>()
        assertEquals("queued", queued.state)
        val request = File(control, "request.env").readLines()
        assertTrue("action=update" in request)
        assertTrue(request.all { it.matches(Regex("[a-zA-Z]+=[A-Za-z0-9_-]*")) })
        assertEquals(HttpStatusCode.Conflict, admin.post("/api/v1/admin/server/actions", ServerActionRequest("restart")).status)

        // control.sh takes the request and reports back; the panel shows it.
        File(control, "request.env").delete()
        File(control, "status.env").writeText("id=${queued.id}\naction=update\nstate=done\nstartedAt=1700000000\nfinishedAt=1700000060\nmessage=Updated to 1.0.40\n")
        File(control, "last.log").writeText("Health check passed\n")
        val server = admin.get("/api/v1/admin/server").body<AdminServerDto>()
        assertTrue(server.agent)
        assertEquals("done", server.control?.state)
        assertEquals("Updated to 1.0.40", server.control?.message)
        assertEquals(1_700_000_060_000, server.control?.finishedAt)
        assertEquals("Health check passed\n", server.log)
        assertEquals(HttpStatusCode.OK, admin.post("/api/v1/admin/server/actions", ServerActionRequest("backup")).status)
    }

    @Test
    fun theWebAdminCanBeTurnedOffAndOnlyTheServerTurnsItBackOn() = serverTest {
        setServerPassword()
        val (admin) = members("admin")
        assertEquals(HttpStatusCode.Forbidden, admin.post("/api/v1/admin/server/web-admin", WebAdminRequest(false)).status)
        admin.post("/api/v1/admin/server/unlock", UnlockRequest(serverPassword))
        assertEquals(HttpStatusCode.OK, admin.post("/api/v1/admin/server/web-admin", WebAdminRequest(false)).status)

        // Every admin endpoint is closed now, the server's included.
        for (path in listOf("/api/v1/admin/status", "/api/v1/admin/users", "/api/v1/admin/invites", "/api/v1/admin/server")) {
            val response = admin.get(path)
            assertEquals(path, HttpStatusCode.Forbidden, response.status)
            assertEquals(path, "web_admin_off", response.body<ApiError>().error)
        }

        // Through the tunnel the server-only endpoints refuse; on the server itself they work.
        val viaTunnel = http.post("/api/v1/local/web-admin") {
            contentType(ContentType.Application.Json)
            header("CF-Connecting-IP", "203.0.113.7")
            setBody(WebAdminRequest(true))
        }
        assertEquals(HttpStatusCode.Forbidden, viaTunnel.status)
        val invite = http.post("/api/v1/local/invites").body<InviteDto>()
        assertNotNull(invite.code)
        val onServer = http.post("/api/v1/local/web-admin") {
            contentType(ContentType.Application.Json)
            setBody(WebAdminRequest(true))
        }
        assertEquals(HttpStatusCode.OK, onServer.status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/admin/status").status)
    }
}
