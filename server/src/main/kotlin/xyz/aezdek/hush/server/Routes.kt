package xyz.aezdek.hush.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.hush.protocol.AddMembersRequest
import xyz.aezdek.hush.protocol.AdminStatus
import xyz.aezdek.hush.protocol.AppVersion
import xyz.aezdek.hush.protocol.ArchiveRequest
import xyz.aezdek.hush.protocol.ChangePasswordRequest
import xyz.aezdek.hush.protocol.CreateThreadRequest
import xyz.aezdek.hush.protocol.DeleteAccountRequest
import xyz.aezdek.hush.protocol.FollowResult
import xyz.aezdek.hush.protocol.IdList
import xyz.aezdek.hush.protocol.LoginRequest
import xyz.aezdek.hush.protocol.MAX_MEMBERS
import xyz.aezdek.hush.protocol.ObjectUpload
import xyz.aezdek.hush.protocol.OkResponse
import xyz.aezdek.hush.protocol.PreloginRequest
import xyz.aezdek.hush.protocol.PushPrefs
import xyz.aezdek.hush.protocol.PushSubscriptionRequest
import xyz.aezdek.hush.protocol.ReadRequest
import xyz.aezdek.hush.protocol.ResetKeysRequest
import xyz.aezdek.hush.protocol.SignupRequest
import xyz.aezdek.hush.protocol.StartUploadRequest
import xyz.aezdek.hush.protocol.TestRingRequest
import xyz.aezdek.hush.protocol.UpdateMeRequest
import xyz.aezdek.hush.protocol.VapidKey
import xyz.aezdek.hush.protocol.Wire
import xyz.aezdek.hush.protocol.WrapsRequest
import xyz.aezdek.hush.protocol.WsFrame
import xyz.aezdek.hush.protocol.WsType
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Login and signup throttling, per client IP (Cloudflare's CF-Connecting-IP when behind the tunnel). */
class RateLimiter(private val clock: Clock) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun check(key: String, max: Int, windowMs: Long) {
        val now = clock.now()
        val queue = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.first() > windowMs) queue.removeFirst()
            if (queue.size >= max) {
                throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many attempts. Try again in a few minutes.")
            }
            queue.addLast(now)
        }
    }
}

private fun ApplicationCall.clientIp() = request.headers["CF-Connecting-IP"] ?: request.origin.remoteAddress

private fun ApplicationCall.bearer(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

private suspend fun ApplicationCall.session(s: Services): Session = blocking { s.accounts.authenticate(bearer()) } ?: unauthorized()

private fun ApplicationCall.param(name: String): String = parameters[name] ?: badRequest("Missing $name")

private const val MINUTE = 60_000L

fun Route.api(s: Services) = route("/api/v1") {

    // ---------------------------------------------------------------------------------------------------- auth
    post("/auth/prelogin") {
        val req = call.receive<PreloginRequest>()
        s.limiter.check("prelogin:${call.clientIp()}", 60, 10 * MINUTE)
        call.respond(blocking { s.accounts.prelogin(req.username) })
    }
    post("/auth/signup") {
        val req = call.receive<SignupRequest>()
        s.limiter.check("signup:${call.clientIp()}", 10, 60 * MINUTE)
        val fx = Effects()
        val response = blocking { s.accounts.signup(req, fx) }
        s.realtime.apply(fx)
        call.respond(response)
    }
    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        s.limiter.check("login:${call.clientIp()}:${req.username.lowercase()}", 10, 10 * MINUTE)
        call.respond(blocking { s.accounts.login(req) })
    }
    post("/auth/logout") {
        val session = call.session(s)
        blocking { s.accounts.logout(session) }
        call.respond(OkResponse())
    }
    get("/auth/sessions") {
        val session = call.session(s)
        call.respond(blocking { s.accounts.sessions(session) })
    }
    delete("/auth/sessions/{id}") {
        val session = call.session(s)
        blocking { s.accounts.revoke(session.userId, call.param("id")) }
        call.respond(OkResponse())
    }
    post("/auth/password") {
        val session = call.session(s)
        val req = call.receive<ChangePasswordRequest>()
        s.limiter.check("password:${session.userId}", 10, 60 * MINUTE)
        blocking { s.accounts.changePassword(session, req) }
        call.respond(OkResponse())
    }
    post("/auth/reset-keys") {
        val session = call.session(s)
        val req = call.receive<ResetKeysRequest>()
        val fx = Effects()
        blocking { s.accounts.resetKeys(session, req, fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }

    // ------------------------------------------------------------------------------------------------------ me
    get("/me") {
        val session = call.session(s)
        call.respond(blocking { s.accounts.me(session) })
    }
    patch("/me") {
        val session = call.session(s)
        val req = call.receive<UpdateMeRequest>()
        val fx = Effects()
        val me = blocking { s.accounts.updateMe(session, req, s.social, fx) }
        s.realtime.apply(fx)
        call.respond(me)
    }
    post("/me/delete") {
        val session = call.session(s)
        val req = call.receive<DeleteAccountRequest>()
        val fx = Effects()
        blocking { s.accounts.deleteAccount(session, req.authKey, s.objects, fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }

    // --------------------------------------------------------------------------------------------------- users
    get("/users") {
        val session = call.session(s)
        call.respond(blocking { s.accounts.allUsers(session.userId) })
    }
    get("/users/{id}") {
        val session = call.session(s)
        call.respond(blocking { s.accounts.userDto(session.userId, call.param("id")) })
    }
    get("/users/{id}/followers") {
        val session = call.session(s)
        call.respond(IdList(blocking { s.social.followers(session.userId, call.param("id")) }))
    }
    get("/users/{id}/following") {
        val session = call.session(s)
        call.respond(IdList(blocking { s.social.following(session.userId, call.param("id")) }))
    }

    // ------------------------------------------------------------------------------------------------- follows
    post("/follows/{id}") {
        val session = call.session(s)
        val fx = Effects()
        val state = blocking { s.social.follow(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(FollowResult(state))
    }
    delete("/follows/{id}") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.social.unfollow(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    get("/follows/requests") {
        val session = call.session(s)
        call.respond(IdList(blocking { s.social.incomingRequests(session.userId) }))
    }
    post("/follows/requests/{id}/accept") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.social.accept(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    post("/follows/requests/{id}/decline") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.social.decline(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    delete("/followers/{id}") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.social.removeFollower(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    get("/blocks") {
        val session = call.session(s)
        call.respond(IdList(blocking { s.social.blocked(session.userId) }))
    }
    post("/blocks/{id}") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.social.block(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    delete("/blocks/{id}") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.social.unblock(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }

    // ------------------------------------------------------------------------------------------------- objects
    post("/objects") {
        val session = call.session(s)
        val req = call.receive<ObjectUpload>()
        val fx = Effects()
        val dto = blocking { s.objects.create(session.userId, req, fx) }
        s.realtime.apply(fx)
        call.respond(HttpStatusCode.Created, dto)
    }
    get("/objects/{id}") {
        val session = call.session(s)
        call.respond(blocking { s.objects.get(session.userId, call.param("id")) })
    }
    put("/objects/{id}") {
        val session = call.session(s)
        val req = call.receive<ObjectUpload>()
        val fx = Effects()
        val dto = blocking { s.objects.update(session.userId, call.param("id"), req, fx) }
        s.realtime.apply(fx)
        call.respond(dto)
    }
    delete("/objects/{id}") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.objects.delete(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    post("/objects/{id}/archive") {
        val session = call.session(s)
        val req = call.receive<ArchiveRequest>()
        val fx = Effects()
        blocking { s.objects.archive(session.userId, call.param("id"), req.archived, fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    post("/objects/{id}/views") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.objects.recordView(session.userId, call.param("id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    get("/objects/{id}/views") {
        val session = call.session(s)
        call.respond(blocking { s.objects.views(session.userId, call.param("id")) })
    }
    post("/wraps") {
        val session = call.session(s)
        val req = call.receive<WrapsRequest>()
        val fx = Effects()
        blocking { s.objects.addWraps(session.userId, req.wraps, fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    get("/wrap-tasks") {
        val session = call.session(s)
        call.respond(blocking { s.objects.wrapTasks(session.userId) })
    }
    post("/wrap-tasks/{id}/done") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.objects.completeTask(session.userId, call.param("id").toLongOrNull() ?: badRequest("bad id"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }

    // ---------------------------------------------------------------------------------------------------- sync
    get("/sync") {
        val session = call.session(s)
        val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 300
        call.respond(blocking { s.sync.sync(session.userId, cursor, limit) })
    }

    // ------------------------------------------------------------------------------------------------- threads
    get("/threads") {
        val session = call.session(s)
        call.respond(blocking { s.threads.list(session.userId) })
    }
    post("/threads") {
        val session = call.session(s)
        val req = call.receive<CreateThreadRequest>()
        val fx = Effects()
        val thread = blocking { s.threads.create(session.userId, req, fx) }
        s.realtime.apply(fx)
        call.respond(HttpStatusCode.Created, thread)
    }
    get("/threads/{id}") {
        val session = call.session(s)
        call.respond(blocking { s.threads.get(session.userId, call.param("id")) })
    }
    post("/threads/{id}/members") {
        val session = call.session(s)
        val req = call.receive<AddMembersRequest>()
        val fx = Effects()
        val thread = blocking { s.threads.addMembers(session.userId, call.param("id"), req, fx) }
        s.realtime.apply(fx)
        call.respond(thread)
    }
    post("/threads/{id}/epochs") {
        val session = call.session(s)
        val req = call.receive<ObjectUpload>()
        val fx = Effects()
        val dto = blocking { s.threads.rotate(session.userId, call.param("id"), req, fx) }
        s.realtime.apply(fx)
        call.respond(HttpStatusCode.Created, dto)
    }
    delete("/threads/{id}/members/{userId}") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.threads.removeMember(session.userId, call.param("id"), call.param("userId"), fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    post("/threads/{id}/read") {
        val session = call.session(s)
        val req = call.receive<ReadRequest>()
        val fx = Effects()
        blocking { s.threads.markRead(session.userId, call.param("id"), req.at, fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }

    // --------------------------------------------------------------------------------------------------- media
    post("/uploads") {
        val session = call.session(s)
        val req = call.receive<StartUploadRequest>()
        call.respond(blocking { s.blobs.start(session.userId, req.size) })
    }
    get("/uploads/{id}") {
        val session = call.session(s)
        call.respond(blocking { s.blobs.state(session.userId, call.param("id")) })
    }
    put("/uploads/{id}") {
        val session = call.session(s)
        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: badRequest("Missing offset")
        val body = call.receiveStream()
        call.respond(blocking { s.blobs.append(session.userId, call.param("id"), offset, body) })
    }
    post("/uploads/{id}/complete") {
        val session = call.session(s)
        call.respond(blocking { s.blobs.complete(session.userId, call.param("id")) })
    }
    get("/blobs/{id}") {
        val session = call.session(s)
        val file = blocking { s.blobs.file(session.userId, call.param("id")) }
        call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        call.response.header(HttpHeaders.ETag, "\"${call.param("id")}\"")
        call.respondFile(file)
    }

    // -------------------------------------------------------------------------------------------- push & calls
    get("/push/vapid") {
        call.session(s)
        call.respond(VapidKey(blocking { s.push.publicKey }))
    }
    post("/push/subscriptions") {
        val session = call.session(s)
        val req = call.receive<PushSubscriptionRequest>()
        blocking { s.push.subscribe(session, req) }
        call.respond(OkResponse())
    }
    get("/push/prefs") {
        val session = call.session(s)
        call.respond(blocking { s.push.prefs(session.userId) })
    }
    put("/push/prefs") {
        val session = call.session(s)
        val req = call.receive<PushPrefs>()
        blocking { s.push.setPrefs(session, req) }
        call.respond(OkResponse())
    }
    post("/push/unsubscribe") {
        val session = call.session(s)
        val req = call.receive<PushSubscriptionRequest>()
        blocking { s.push.unsubscribe(session, req.endpoint) }
        call.respond(OkResponse())
    }
    post("/push/test-ring") {
        val session = call.session(s)
        val req = call.receive<TestRingRequest>()
        s.limiter.check("ring:${session.userId}", 10, 10 * MINUTE)
        val delaySeconds = req.delaySeconds.coerceIn(0, 120)
        s.realtime.let { realtime ->
            call.application.launch {
                delay(delaySeconds * 1000L)
                if (!s.presence.send(session.userId, WsFrame(WsType.RING_TEST))) {
                    realtime.pushTo(session.userId, WsType.RING_TEST, null, null, urgent = true)
                }
            }
        }
        call.respond(OkResponse())
    }
    get("/turn") {
        call.session(s)
        call.respond(blocking { s.turn.iceServers() })
    }

    // --------------------------------------------------------------------------------------------------- admin
    get("/admin/status") {
        val session = call.session(s)
        if (!blocking { s.accounts.isAdmin(session.userId) }) forbidden("Admins only")
        call.respond(blocking { adminStatus(s) })
    }
    get("/admin/invites") {
        val session = call.session(s)
        call.respond(blocking { s.accounts.invites(session) })
    }
    post("/admin/invites") {
        val session = call.session(s)
        call.respond(blocking { s.accounts.createInvite(session) })
    }
    delete("/admin/invites/{code}") {
        val session = call.session(s)
        blocking { s.accounts.revokeInvite(session, call.param("code")) }
        call.respond(OkResponse())
    }
    get("/admin/users") {
        val session = call.session(s)
        call.respond(blocking { s.accounts.adminUsers(session) })
    }
    post("/admin/users/{id}/sign-out") {
        val session = call.session(s)
        blocking { s.accounts.signOutEverywhere(session, call.param("id")) }
        call.respond(OkResponse())
    }
    delete("/admin/users/{id}") {
        val session = call.session(s)
        val fx = Effects()
        blocking { s.accounts.removeMember(session, call.param("id"), s.objects, fx) }
        s.realtime.apply(fx)
        call.respond(OkResponse())
    }
    get("/app/version") {
        call.session(s)
        val file = s.config.apkDir?.let { File(it, "version.json") }?.takeIf { it.isFile } ?: notFound("No Android build in this image")
        call.respond(Wire.json.decodeFromString(AppVersion.serializer(), file.readText()))
    }
}

/** Reads the status file backup.sh writes after every run. */
fun adminStatus(s: Services): AdminStatus {
    val backup = s.config.backupStatusFile.takeIf { it.isFile }?.let { file ->
        runCatching { Wire.json.parseToJsonElement(file.readText()) }.getOrNull()
    }
    fun field(name: String) = (backup as? kotlinx.serialization.json.JsonObject)?.get(name)
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    val latest = s.releases.latest()
    return AdminStatus(
        users = s.db.tx { long("SELECT COUNT(*) FROM users WHERE deleted = 0") ?: 0 }.toInt(),
        maxUsers = MAX_MEMBERS,
        diskFreeBytes = s.config.dataDir.usableSpace,
        dataBytes = s.blobs.dataBytes(),
        uploadsBlocked = s.blobs.uploadsBlocked(),
        lastBackupAt = field("lastSuccessAt")?.toLongOrNull()?.times(1000),
        lastBackupOk = field("ok")?.toBooleanStrictOrNull(),
        backupMessage = field("message"),
        version = s.config.version,
        commit = s.config.commit,
        startedAt = s.startedAt,
        latestVersion = latest?.version,
        // By commit when both sides know theirs: version numbers restart when the CI workflow is renamed.
        updateAvailable = latest != null && (
            if (latest.commit != null && s.config.commit != null) latest.commit != s.config.commit else latest.version != s.config.version
            ),
    )
}

/** One WebSocket per device: sync nudges, typing, presence, and E2EE call signalling relayed between users. */
fun Route.realtimeRoutes(s: Services) {
    webSocket("/api/v1/ws", protocol = "hush.v1") {
        val offered = call.request.headers[HttpHeaders.SecWebSocketProtocol].orEmpty().split(",").map { it.trim() }
        val token = offered.firstOrNull { it.startsWith("auth.") }?.removePrefix("auth.")
        val session = blocking { s.accounts.authenticate(token) }
        if (session == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val me = session.userId
        val connection = Presence.Connection(session.id, me, this)
        val wasOnline = s.presence.isOnline(me)
        s.presence.register(connection)
        s.presence.send(me, WsFrame(WsType.SYNC))
        if (!wasOnline) broadcastPresence(s, me, true)
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = runCatching { Wire.json.decodeFromString(WsFrame.serializer(), frame.readText()) }.getOrNull() ?: continue
                handleFrame(s, me, message, connection)
            }
        } finally {
            s.presence.unregister(connection)
            if (!s.presence.isOnline(me)) {
                blocking { s.db.tx { exec("UPDATE users SET last_active_at = ? WHERE id = ?", s.clock.now(), me) } }
                broadcastPresence(s, me, false)
            }
        }
    }
}

private suspend fun handleFrame(s: Services, me: String, frame: WsFrame, connection: Presence.Connection) {
    when (frame.t) {
        WsType.PING -> connection.socket.outgoing.trySend(Frame.Text(Wire.json.encodeToString(WsFrame.serializer(), WsFrame(WsType.PONG))))
        WsType.TYPING -> {
            val threadId = frame.threadId ?: return
            val members = blocking { s.db.tx { strings("SELECT user_id FROM thread_members WHERE thread_id = ?", threadId) } }
            if (me !in members) return
            members.filter { it != me }.forEach { s.presence.send(it, WsFrame(WsType.TYPING, threadId = threadId, userId = me)) }
        }
        WsType.SIGNAL -> {
            val to = frame.to ?: return
            val signal = frame.signal ?: return
            if (signal.sealed.length > 96 * 1024) return
            val allowed = blocking {
                s.db.tx {
                    exists("SELECT 1 FROM users WHERE id = ? AND deleted = 0", to) && !exists(
                        "SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)", me, to, to, me,
                    )
                }
            }
            if (!allowed) return
            val delivered = s.presence.send(to, WsFrame(WsType.SIGNAL, from = me, signal = signal, callId = frame.callId, ring = frame.ring))
            if (!delivered && frame.ring == true) s.realtime.pushTo(to, "call", me, frame.callId, urgent = true)
        }
    }
}

private suspend fun broadcastPresence(s: Services, userId: String, online: Boolean) {
    val visibleTo = blocking {
        s.db.tx {
            val shows = one("SELECT show_activity FROM users WHERE id = ?", userId) { it.bool("show_activity") } ?: false
            if (!shows) emptyList() else strings("SELECT id FROM users WHERE deleted = 0 AND show_activity = 1 AND id != ?", userId)
        }
    }
    visibleTo.forEach { s.presence.send(it, WsFrame(WsType.PRESENCE, userId = userId, online = online)) }
}
