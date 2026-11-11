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
import xyz.aezdek.hush.crypto.toHex
import xyz.aezdek.hush.protocol.ApiError
import xyz.aezdek.hush.protocol.Health
import xyz.aezdek.hush.protocol.ServerState
import xyz.aezdek.hush.protocol.SetupRequest
import xyz.aezdek.hush.protocol.SetupStatus
import xyz.aezdek.hush.protocol.UnlockRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * What runs until the database can be opened: a fresh server waiting for its server password, or one that this machine
 * can't unlock by itself (a restore, new hardware). It stores nothing. It serves the web app (which says Hush is
 * being set up), the Android download and `/healthz`; the rest of the API waits.
 *
 * Setting the password and unlocking are only accepted from the server itself (update.sh calls them on 127.0.0.1), never
 * through a tunnel or proxy, so the password never crosses the internet (see [LocalAccess]).
 */
fun Application.setupServer(config: ServerConfig, vault: Vault, onReady: (ByteArray) -> Unit) {
    val log = LoggerFactory.getLogger("hush.setup")
    val local = LocalAccess(config)
    local.key()
    basePlugins()
    if (vault.state() == ServerState.SETUP) {
        vault.setupCode()
        log.info("Hush is waiting for its server password, set on the server itself: sudo ${config.hostRoot}/update.sh (or run install.sh again)")
    } else {
        log.info("Hush is locked: this machine can't unseal the database key. Unlock with: sudo ${config.hostRoot}/update.sh")
    }
    var lastAttempt = 0L

    routing {
        get("/healthz") { call.respond(Health(ok = true, version = config.version, state = vault.state())) }
        route("/api/v1") {
            get("/setup") { call.respond(SetupStatus(vault.state())) }
            post("/setup") {
                local.requireDirect(call)
                val request = call.receive<SetupRequest>()
                if (vault.state() != ServerState.SETUP) conflict("This server is already set up", "exists")
                val expected = vault.setupCode().encodeToByteArray()
                if (!Sodium.constantTimeEquals(request.code.trim().uppercase().encodeToByteArray(), expected)) forbidden("Wrong setup code")
                val key = withContext(Dispatchers.IO) { vault.setup(request.password) }
                call.respond(SetupStatus(ServerState.READY))
                onReady(key)
            }
            post("/setup/unlock") {
                local.require(call)
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

/**
 * What makes a request "from the server itself" (update.sh and control.sh, over 127.0.0.1). Two things, both needed:
 *
 * - it carries the key in data/local.key, which only someone who can read the server's files has. The server writes it
 *   on first start; the scripts read it and send it as `X-Hush-Local`;
 * - it did not arrive through a proxy: Cloudflare and ordinary reverse proxies add the headers below to what they forward.
 *
 * The headers alone are not enough: a reverse proxy set up without them would make every visitor look local.
 */
class LocalAccess(config: ServerConfig) {
    private val file = File(config.dataDir, "local.key")

    @Volatile
    private var cached: String? = null

    fun key(): String = cached ?: synchronized(this) {
        cached ?: run {
            val existing = file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            val key = existing ?: Sodium.random(32).toHex().also { fresh ->
                file.parentFile.mkdirs()
                val temp = File(file.parentFile, ".local.key.tmp")
                temp.writeText("$fresh\n")
                runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            cached = key
            key
        }
    }

    fun require(call: ApplicationCall) {
        val refusal = "Do this on the server itself: sudo update.sh (or control.sh) in Hush's folder"
        if (FORWARDED.any { call.request.headers[it] != null }) forbidden(refusal)
        val sent = call.request.headers[HEADER]?.trim().orEmpty()
        if (!Sodium.constantTimeEquals(sent.encodeToByteArray(), key().encodeToByteArray())) forbidden(refusal)
    }

    /** For setting the first password, where the setup code already proves who is asking: only the proxy check. */
    fun requireDirect(call: ApplicationCall) {
        if (FORWARDED.any { call.request.headers[it] != null }) forbidden("Set the server password on the server itself: sudo update.sh in Hush's folder")
    }

    companion object {
        const val HEADER = "X-Hush-Local"

        /** What Cloudflare and reverse proxies add to everything they forward; the scripts on the server send none of them. */
        private val FORWARDED = listOf(
            "CF-Connecting-IP", "CF-Ray", "CF-IPCountry", "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Real-IP", "Forwarded", "Via", "CDN-Loop",
        )
    }
}
