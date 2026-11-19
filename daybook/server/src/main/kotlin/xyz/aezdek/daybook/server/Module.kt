package xyz.aezdek.daybook.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
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

/** Largest request body accepted, in bytes. A full push of 500 tasks is far smaller. */
private const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024

fun Application.daybookModule(
    token: String,
    store: ServerStore,
    clock: () -> Long = System::currentTimeMillis,
    waitMillis: Long = WAIT_MILLIS,
) {
    val expected = token.encodeToByteArray()

    install(ContentNegotiation) { json(DaybookJson) }
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
