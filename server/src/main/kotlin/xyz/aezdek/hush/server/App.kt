package xyz.aezdek.hush.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import xyz.aezdek.hush.protocol.ApiError
import xyz.aezdek.hush.protocol.Health
import xyz.aezdek.hush.protocol.Wire
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Every service, wired once. Tests build one per test with a temp data dir and a fake clock. */
class Services(val config: ServerConfig, val clock: Clock, app: Application) {
    init {
        config.dataDir.mkdirs()
    }

    val db = Db(config.dbFile)
    val presence = Presence()
    val accounts = Accounts(db, clock, presence)
    val objects = Objects(db, clock)
    val social = Social(db, clock, objects)
    val threads = Threads(db, clock, objects)
    val blobs = Blobs(db, clock, config, objects)
    val sync = Sync(db, objects, accounts, threads)
    val push = WebPush(db, clock, config.vapidSubject)
    val turn = Turn(config, clock)
    val releases = Releases(config.releaseUrl, clock)
    val startedAt = clock.now()
    val realtime = Realtime(db, presence, push, app, clock)
    val jobs = Jobs(config, db, objects, blobs, realtime)
    val limiter = RateLimiter(clock)
}

/**
 * Security headers for everything served, the web app included. The CSP keeps the client to its own origin: no
 * third-party scripts, styles, fonts or connections. `wasm-unsafe-eval` is for libsodium's WebAssembly.
 */
private const val CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' blob: data:; media-src 'self' blob:; font-src 'self'; connect-src 'self'; worker-src 'self' blob:; " +
    "manifest-src 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'; form-action 'self'"

fun Application.hush(config: ServerConfig, clock: Clock = Clock.SYSTEM): Services {
    val log = LoggerFactory.getLogger("hush")
    val services = Services(config, clock, this)

    install(ContentNegotiation) { json(Wire.json) }
    install(WebSockets) {
        pingPeriod = 25.seconds
        timeout = 60.seconds
        maxFrameSize = 256 * 1024
    }
    install(PartialContent)
    install(AutoHeadResponse)
    install(Compression) {
        gzip {
            excludeContentType(ContentType.Application.OctetStream)
            minimumSize(1024)
        }
    }
    install(CallLogging) {
        level = Level.INFO
        // Never log query strings or headers: tokens and ids stay out of the logs.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
        filter { call -> call.request.path().startsWith("/api/") }
    }
    install(DefaultHeaders) {
        header("Content-Security-Policy", CSP)
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
        header("X-Frame-Options", "DENY")
        header("Cross-Origin-Opener-Policy", "same-origin")
        header("Permissions-Policy", "camera=(self), microphone=(self), display-capture=(self), geolocation=(), payment=()")
        header("Strict-Transport-Security", "max-age=31536000")
    }
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respond(cause.status, ApiError(cause.code, cause.message)) }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", cause.cause?.message ?: cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Something went wrong on the server"))
        }
    }
    install(BodyLimit)

    services.accounts.bootstrap(config)
    if (config.runJobs) services.jobs.start(this)

    routing {
        get("/healthz") { call.respond(Health(ok = true, version = config.version)) }
        api(services)
        realtimeRoutes(services)
        get("/download/hush.apk") {
            val apk = config.apkDir?.let { File(it, "hush.apk") }?.takeIf { it.isFile } ?: notFound("No Android build in this image")
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter("filename", "Hush.apk").toString())
            call.respondFile(apk)
        }
        get("/{path...}") { serveWeb(call, config) }
    }
    return services
}

/** The single-page web app: real files when they exist, index.html otherwise (client-side routes). */
private suspend fun serveWeb(call: ApplicationCall, config: ServerConfig) {
    val path = call.request.path().trimStart('/')
    if (path.startsWith("api/")) notFound()
    val root = config.webDir?.canonicalFile ?: notFound("The web app is not part of this build")
    val requested = File(root, path).canonicalFile
    val file = if (requested.isFile && requested.path.startsWith(root.path + File.separator)) requested else File(root, "index.html")
    if (!file.isFile) notFound()
    val immutable = file.parentFile.name == "assets"
    call.response.header(
        HttpHeaders.CacheControl,
        if (immutable) "public, max-age=31536000, immutable" else "no-cache",
    )
    call.respondFile(file)
}

suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** JSON bodies are small; only media uploads stream large bodies, in pieces. */
private val BodyLimit = createApplicationPlugin("BodyLimit") {
    onCall { call ->
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0
        val isUpload = call.request.path().startsWith("/api/v1/uploads/")
        if (length > (if (isUpload) Blobs.CHUNK + 1024L else 4L * 1024 * 1024)) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "too_large", "Request too large")
        }
    }
}
