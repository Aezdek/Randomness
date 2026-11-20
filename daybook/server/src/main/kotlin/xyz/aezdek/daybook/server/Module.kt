package xyz.aezdek.daybook.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.response.respondBytes
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import xyz.aezdek.daybook.core.DaybookJson
import xyz.aezdek.daybook.core.PingResponse
import xyz.aezdek.daybook.core.SyncRequest
import xyz.aezdek.daybook.core.WaitResponse
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("xyz.aezdek.daybook.server")

/** How long `/api/wait` holds a request open. Well under Cloudflare's 100 s proxy timeout. */
const val WAIT_MILLIS: Long = 25_000

/**
 * The web app loads only its own files and talks only to this server. WebAssembly needs
 * 'wasm-unsafe-eval'; the page's one inline module script and import map need 'unsafe-inline'.
 */
private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; " +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

/** Largest request body accepted, in bytes. A full push of 500 tasks is far smaller. */
private const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024

fun Application.daybookModule(
    token: String,
    store: ServerStore,
    clock: () -> Long = System::currentTimeMillis,
    waitMillis: Long = WAIT_MILLIS,
    web: WebAssets = WebAssets("web"),
) {
    val expected = token.encodeToByteArray()

    install(ContentNegotiation) { json(DaybookJson) }
    // API answers are gzipped on the fly; the website's files arrive already compressed (see WebAssets).
    install(Compression) {
        gzip { minimumSize(1024) }
    }
    install(createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        }
    })
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondText("Bad request: ${cause.message}", status = HttpStatusCode.BadRequest)
        }
        exception<SerializationException> { call, _ ->
            call.respondText("Bad request: the body is not valid sync JSON", status = HttpStatusCode.BadRequest)
        }
        exception<Throwable> { call, cause ->
            log.error("Request failed", cause)
            call.respondText("Server error", status = HttpStatusCode.InternalServerError)
        }
    }

    routing {
        // Unauthenticated liveness check for Docker and uptime monitors. Reveals nothing.
        get("/health") { call.respondText("ok") }

        // The web app. It holds no data: everything it shows comes from /api with the token.
        get("/{path...}") {
            if (!web.available) {
                return@get call.respondText(
                    "Daybook sync server. This build doesn't include the web app.",
                    status = if (call.parameters.getAll("path").isNullOrEmpty()) HttpStatusCode.OK else HttpStatusCode.NotFound,
                )
            }
            val asset = web.get(call.parameters.getAll("path")?.joinToString("/").orEmpty())
                ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.ETag, asset.etag)
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
            if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
                return@get call.respond(HttpStatusCode.NotModified)
            }
            val acceptsGzip = call.request.headers[HttpHeaders.AcceptEncoding]?.contains("gzip") == true
            if (asset.gzipped != null && acceptsGzip) {
                call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
                call.respondBytes(asset.gzipped, asset.contentType)
            } else {
                call.respondBytes(asset.bytes, asset.contentType)
            }
        }

        route("/api") {
            get("/ping") {
                if (!call.authorized(expected)) return@get call.unauthorized()
                call.respond(PingResponse(rev = store.rev.value, tasks = store.taskCount()))
            }

            post("/sync") {
                if (!call.authorized(expected)) return@post call.unauthorized()
                val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > MAX_BODY_BYTES) {
                    return@post call.respondText("Request too large", status = HttpStatusCode.PayloadTooLarge)
                }
                val request = call.receive<SyncRequest>()
                call.respond(store.sync(request, clock()))
            }

            // Long poll: answers as soon as the revision passes `since`, or after [waitMillis] regardless.
            get("/wait") {
                if (!call.authorized(expected)) return@get call.unauthorized()
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                val rev = withTimeoutOrNull(waitMillis) { store.rev.first { it > since } } ?: store.rev.value
                call.respond(WaitResponse(rev))
            }
        }
    }
}

private fun ApplicationCall.authorized(expected: ByteArray): Boolean {
    val header = request.headers[HttpHeaders.Authorization] ?: return false
    if (!header.startsWith("Bearer ", ignoreCase = true)) return false
    val given = header.substring(7).trim().encodeToByteArray()
    return MessageDigest.isEqual(given, expected)
}

private suspend fun ApplicationCall.unauthorized() {
    response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
    respondText("Missing or wrong access token", status = HttpStatusCode.Unauthorized)
}
