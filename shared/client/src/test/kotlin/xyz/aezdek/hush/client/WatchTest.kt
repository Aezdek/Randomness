package xyz.aezdek.hush.client

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.protocol.WatchParty
import xyz.aezdek.hush.protocol.WatchState
import java.net.ServerSocket

class WatchTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun `where the film is follows the newest play, pause or jump`() {
        val hour = 3_600_000L
        val t = 1_800_000_000_000
        assertEquals(Watching.Now(false, 0), Watching.now(emptyList(), 2 * hour, t))
        // Played from the start a minute ago.
        assertEquals(Watching.Now(true, 60_000), Watching.now(listOf(WatchState(true, 0, t - 60_000)), 2 * hour, t))
        // Then paused at ten minutes: it stays there however long ago that was.
        val paused = listOf(WatchState(true, 0, t - 900_000), WatchState(false, 600_000, t - 300_000))
        assertEquals(Watching.Now(false, 600_000), Watching.now(paused, 2 * hour, t))
        // Left playing past the end: stopped at the end.
        assertEquals(Watching.Now(false, 2 * hour), Watching.now(listOf(WatchState(true, 0, t - 5 * hour)), 2 * hour, t))
    }

    @Test
    fun `states no honest app sends are ignored or read sensibly`() {
        val t = 1_800_000_000_000
        val good = WatchState(false, 120_000, t - 1_000)
        // A negative position, one a year long, and a time no clock shows: the last sensible state still counts.
        val hostile = listOf(good, WatchState(true, -5, t), WatchState(true, Long.MAX_VALUE, t), WatchState(true, 0, Long.MAX_VALUE), WatchState(true, 0, Long.MIN_VALUE))
        assertEquals(Watching.Now(false, 120_000), Watching.now(hostile, 0, t))
        // Dated in the future: read as now, not as a position before the start.
        assertEquals(Watching.Now(true, 5_000), Watching.now(listOf(WatchState(true, 5_000, t + 999_999)), 0, t))
        assertTrue(Watching.valid(WatchParty(id, "Film")))
        for (bad in listOf("../../System/Logs", "a b", "", "x".repeat(200), "$id/../../Users", "$id?api_key=x")) {
            assertFalse(bad, Watching.valid(WatchParty(bad, "Film")))
            assertNull(Jellyfin.streamUrl(JellyfinSession("https://jf.example", "tok", "u"), bad))
        }
        assertFalse(Watching.valid(WatchParty(id, "Film", runtimeMs = -1)))
        assertEquals(Watching.MAX_TITLE, Watching.title("T".repeat(5_000)).length)
    }

    @Test
    fun `only web addresses are taken for a Jellyfin server`() {
        assertEquals("https://jellyfin.example.com/", Jellyfin.baseOf("jellyfin.example.com").toString())
        assertEquals("http://10.0.0.5:8096/", Jellyfin.baseOf("http://10.0.0.5:8096/").toString())
        assertNull(Jellyfin.baseOf("javascript:alert(1)"))
        assertNull(Jellyfin.baseOf("file:///etc/passwd"))
        assertNull(Jellyfin.baseOf("https://user:pass@jellyfin.example.com"))
        assertNull(Jellyfin.baseOf("  "))
    }

    @Test
    fun `signing in, listing and playing against a Jellyfin server`() = runBlocking {
        // A stand-in that answers as Jellyfin does for the three things Hush asks of it.
        val port = ServerSocket(0).use { it.localPort }
        var listedWith: String? = null
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                post("/Users/AuthenticateByName") {
                    val body = call.receiveText()
                    if (call.request.header("Authorization")?.contains("Client=\"Hush\"") != true) return@post call.respond(HttpStatusCode.BadRequest)
                    if (!body.contains("\"Pw\":\"right\"")) return@post call.respond(HttpStatusCode.Unauthorized)
                    call.respondText("""{"User":{"Id":"user-1","Name":"ana"},"AccessToken":"tok-123","ServerId":"s"}""", ContentType.Application.Json)
                }
                get("/Items") {
                    listedWith = call.request.header("Authorization")
                    if (listedWith?.contains("Token=\"tok-123\"") != true) return@get call.respond(HttpStatusCode.Unauthorized)
                    call.respondText(
                        """{"Items":[{"Id":"$id","Name":"Paddington","Type":"Movie","ProductionYear":2014,"RunTimeTicks":57000000000},
                           {"Id":"${id.reversed()}","Name":"Pilot","Type":"Episode","SeriesName":"Some Show","RunTimeTicks":-5},
                           {"Name":"no id"}],"TotalRecordCount":3}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        try {
            val jellyfin = Jellyfin(OkHttpClient())
            val base = "http://127.0.0.1:$port"
            assertTrue(runCatching { jellyfin.signIn(base, "ana", "wrong") }.exceptionOrNull() is UserFacingException)
            val session = jellyfin.signIn("$base/", " ana ", "right")
            assertEquals(JellyfinSession(base, "tok-123", "user-1"), session)
            val items = jellyfin.items(session, "pad")
            assertEquals(listOf(JellyfinItem(id, "Paddington (2014)", 5_700_000), JellyfinItem(id.reversed(), "Some Show: Pilot", 0)), items)
            assertEquals("$base/Videos/$id/stream?static=true&api_key=tok-123", Jellyfin.streamUrl(session, id))
            assertTrue(runCatching { jellyfin.items(session.copy(token = "stale")) }.exceptionOrNull() is UserFacingException)
            assertTrue(runCatching { jellyfin.signIn("http://127.0.0.1:1", "a", "b") }.exceptionOrNull() is UserFacingException)
        } finally {
            server.stop(100, 500)
        }
    }
}
