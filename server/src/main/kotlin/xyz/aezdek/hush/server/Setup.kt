package xyz.aezdek.hush.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import xyz.aezdek.hush.crypto.Sodium
import xyz.aezdek.hush.protocol.ApiError
import xyz.aezdek.hush.protocol.Health
import xyz.aezdek.hush.protocol.ServerState
import xyz.aezdek.hush.protocol.SetupRequest
import xyz.aezdek.hush.protocol.SetupStatus
import xyz.aezdek.hush.protocol.UnlockRequest

/**
 * What runs until the database can be opened: a fresh server waiting for its server password, or one that this machine
 * can't unlock by itself (a restore, new hardware). It stores nothing. It serves the web app (which says Hush is
 * being set up), the Android download and `/healthz`; the rest of the API waits.
 *
 * Setting the password and unlocking are only accepted from the server itself (update.sh calls them on 127.0.0.1), never
 * through Cloudflare, so the password never crosses the internet: requests the tunnel forwarded carry its headers.
 */
fun Application.setupServer(config: ServerConfig, vault: Vault, onReady: (ByteArray) -> Unit) {
    val log = LoggerFactory.getLogger("hush.setup")
    basePlugins()
    if (vault.state() == ServerState.SETUP) {
        vault.setupCode()
        log.info("Hush is waiting for its server password, set on the server itself: sudo /opt/hl/hush/update.sh (or re-run install.sh)")
    } else {
        log.info("Hush is locked: this machine can't unseal the database key. Unlock with: sudo /opt/hl/hush/update.sh")
    }
    var lastAttempt = 0L

    routing {
        get("/healthz") { call.respond(Health(ok = true, version = config.version, state = vault.state())) }
        route("/api/v1") {
            get("/setup") { call.respond(SetupStatus(vault.state())) }
            post("/setup") {
                call.requireLocal()
                val request = call.receive<SetupRequest>()
                if (vault.state() != ServerState.SETUP) conflict("This server is already set up", "exists")
                val expected = vault.setupCode().encodeToByteArray()
                if (!Sodium.constantTimeEquals(request.code.trim().uppercase().encodeToByteArray(), expected)) forbidden("Wrong setup code")
                val key = withContext(Dispatchers.IO) { vault.setup(request.password) }
                call.respond(SetupStatus(ServerState.READY))
                onReady(key)
            }
            post("/setup/unlock") {
                call.requireLocal()
                val request = call.receive<UnlockRequest>()
                // Argon2id already makes each guess take a second; one at a time on top of that.
                synchronized(vault) {
                    val now = System.currentTimeMillis()
                    if (now - lastAttempt < 2000) throw ApiException(HttpStatusCode.TooManyRequests, "slow_down", "Wait a moment and try again")
                    lastAttempt = now
                }
                val key = withContext(Dispatchers.IO) { vault.unlock(request.password) } ?: forbidden("That isn't the server password")
                call.respond(SetupStatus(ServerState.READY))
                onReady(key)
            }
            route("{...}") {
                handle {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError(vault.state(), "Hush is starting up. Try again in a minute."))
                }
            }
        }
        publicRoutes(config)
    }
}

/** Cloudflare adds these to everything it forwards; update.sh on the server itself sends none of them. */
private val FORWARDED = listOf("CF-Connecting-IP", "CF-Ray", "CF-IPCountry", "X-Forwarded-For", "X-Forwarded-Proto", "Forwarded", "CDN-Loop")

internal fun ApplicationCall.requireLocal() {
    if (FORWARDED.any { request.headers[it] != null }) forbidden("Set the server password on the server itself: sudo /opt/hl/hush/update.sh")
}
