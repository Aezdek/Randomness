package xyz.aezdek.aezgram.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import xyz.aezdek.aezgram.crypto.AccountKeys
import xyz.aezdek.aezgram.crypto.B64
import xyz.aezdek.aezgram.crypto.KdfParams
import xyz.aezdek.aezgram.crypto.ObjectCrypto
import xyz.aezdek.aezgram.crypto.ObjectHeader
import xyz.aezdek.aezgram.crypto.PasswordKeys
import xyz.aezdek.aezgram.protocol.AuthResponse
import xyz.aezdek.aezgram.protocol.DeviceInfo
import xyz.aezdek.aezgram.protocol.InviteDto
import xyz.aezdek.aezgram.protocol.LoginRequest
import xyz.aezdek.aezgram.protocol.ObjectDto
import xyz.aezdek.aezgram.protocol.ObjectUpload
import xyz.aezdek.aezgram.protocol.PreloginRequest
import xyz.aezdek.aezgram.protocol.PreloginResponse
import xyz.aezdek.aezgram.protocol.SignupRequest
import xyz.aezdek.aezgram.protocol.SyncResponse
import xyz.aezdek.aezgram.protocol.UserDto
import xyz.aezdek.aezgram.protocol.Wire
import xyz.aezdek.aezgram.protocol.WrapDto
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64

class MutableClock(var time: Long = 1_700_000_000_000) : Clock {
    override fun now() = time
    fun advance(ms: Long) {
        time += ms
    }
}

class Harness(val builder: ApplicationTestBuilder, val services: Services, val clock: MutableClock, val dataDir: File) {
    val http: HttpClient = builder.createClient {
        install(ContentNegotiation) { json(Wire.json) }
        install(WebSockets)
    }

    fun firstInvite(): String = File(dataDir, "first-invite.txt").readText().trim()

    suspend fun newInvite(admin: Member): String = admin.post("/api/v1/admin/invites", null).body<InviteDto>().code

    /** Signs up the admin (first invite) and [others] (invites from the admin). */
    suspend fun members(vararg names: String): List<Member> {
        val out = ArrayList<Member>()
        names.forEachIndexed { index, name ->
            val member = Member(this, name)
            member.signup(if (index == 0) firstInvite() else newInvite(out[0]))
            out += member
        }
        return out
    }
}

fun serverTest(block: suspend Harness.() -> Unit) {
    val dir = Files.createTempDirectory("aezgram-test").toFile()
    val clock = MutableClock()
    var services: Services? = null
    try {
        testApplication {
            application {
                services = aezgram(ServerConfig(dataDir = dir, runJobs = false, minFreeBytes = 0, version = "test"), clock)
            }
            startApplication()
            Harness(this, services!!, clock, dir).block()
        }
    } finally {
        services?.db?.close()
        dir.deleteRecursively()
    }
}

private val random = SecureRandom()
fun newId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))

/**
 * A simulated client: real crypto from shared:crypto, real HTTP against the in-process server. Mirrors what the
 * Android and web clients do, so these tests exercise the protocol end to end.
 */
class Member(private val h: Harness, val username: String, val password: String = "pw-$username-long") {
    lateinit var keys: AccountKeys
    lateinit var token: String
    lateinit var id: String
    var cursor = 0L
    val received = LinkedHashMap<String, ObjectDto>()
    val deleted = HashSet<String>()
    val users = HashMap<String, UserDto>()

    suspend fun signup(invite: String): AuthResponse {
        keys = AccountKeys.generate()
        val salt = PasswordKeys.newSalt()
        val derived = PasswordKeys.derive(password, salt, KdfParams.FAST_FOR_TESTS)
        val pub = keys.publicKeys()
        val response = h.http.post("/api/v1/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody(
                SignupRequest(
                    inviteCode = invite, username = username, salt = B64.encode(salt),
                    opsLimit = KdfParams.FAST_FOR_TESTS.opsLimit, memLimit = KdfParams.FAST_FOR_TESTS.memLimitBytes,
                    authKey = B64.encode(derived.authKey), signPk = B64.encode(pub.signPk), boxPk = B64.encode(pub.boxPk),
                    boxPkSig = B64.encode(pub.boxPkSig), vault = B64.encode(keys.sealVault(derived.vaultKey)),
                    device = DeviceInfo("test", "web"),
                ),
            )
        }
        assertEquals(response.status.description, HttpStatusCode.OK, response.status)
        val auth = response.body<AuthResponse>()
        token = auth.token
        id = auth.me.id
        return auth
    }

    /** Logs in as a fresh device: prelogin → Argon2id → authKey → vault → keys. */
    suspend fun loginFresh(passwordOverride: String = password): HttpResponse {
        val pre = h.http.post("/api/v1/auth/prelogin") {
            contentType(ContentType.Application.Json)
            setBody(PreloginRequest(username))
        }.body<PreloginResponse>()
        val derived = PasswordKeys.derive(passwordOverride, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit))
        val response = h.http.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, B64.encode(derived.authKey), DeviceInfo("second", "android")))
        }
        if (response.status == HttpStatusCode.OK) {
            val auth = response.body<AuthResponse>()
            token = auth.token
            keys = AccountKeys.openVault(B64.decode(auth.vault), derived.vaultKey)
        }
        return response
    }

    private fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $token")

    suspend fun get(path: String) = h.http.get(path) { auth() }
    suspend fun delete(path: String) = h.http.delete(path) { auth() }
    suspend fun post(path: String, body: Any?) = h.http.post(path) {
        auth()
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    suspend fun put(path: String, body: Any) = h.http.put(path) {
        auth()
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    suspend fun patch(path: String, body: Any) = h.http.patch(path) {
        auth()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun user(other: Member): UserDto = get("/api/v1/users/${other.id}").body()

    /** Seals [plaintext] for [readers] (plus self) as a top-level object. Returns the upload and the content key. */
    fun seal(
        kind: String,
        audience: String,
        plaintext: String,
        readers: List<Member> = emptyList(),
        objectId: String = newId(),
        parentId: String? = null,
        threadId: String? = null,
        expiresAt: Long? = null,
        blobIds: List<String> = emptyList(),
        refIds: List<String> = emptyList(),
        key: ByteArray = ObjectCrypto.newContentKey(),
        version: Int = 1,
        createdAt: Long = h.clock.now(),
        wrap: Boolean = true,
    ): Pair<ObjectUpload, ByteArray> {
        val header = ObjectHeader(objectId, kind, id, parentId, threadId, audience, createdAt, expiresAt, version, blobIds, refIds)
        val sealed = ObjectCrypto.seal(header, plaintext.encodeToByteArray(), key, keys)
        val wraps = if (!wrap) emptyList() else (readers + this).distinctBy { it.id }.map {
            WrapDto(it.id, B64.encode(ObjectCrypto.wrap(key, it.keys.boxPk)))
        }
        return ObjectUpload(
            id = objectId, kind = kind, parentId = parentId, threadId = threadId, audience = audience, createdAt = header.createdAt,
            expiresAt = expiresAt, version = version, blobIds = blobIds, refIds = refIds, payload = B64.encode(sealed.payload),
            commitment = B64.encode(sealed.commitment), signature = B64.encode(sealed.signature), wraps = wraps,
        ) to key
    }

    suspend fun create(upload: ObjectUpload): HttpResponse = post("/api/v1/objects", upload)

    suspend fun sync(): SyncResponse {
        var last: SyncResponse
        do {
            last = get("/api/v1/sync?cursor=$cursor&limit=50").body()
            last.objects.forEach {
                received[it.id] = it
                deleted -= it.id
            }
            last.deletedIds.forEach {
                received.remove(it)
                deleted += it
            }
            last.users.forEach { users[it.id] = it }
            cursor = last.cursor
        } while (last.hasMore)
        return last
    }

    /** Opens an object with a wrap addressed to us, verifying the author's signature. */
    fun open(dto: ObjectDto, author: Member, key: ByteArray? = null): String {
        val contentKey = key ?: ObjectCrypto.unwrap(B64.decode(dto.wrap!!), keys, B64.decode(dto.commitment))
        return ObjectCrypto.open(dto.header(), B64.decode(dto.payload), B64.decode(dto.commitment), B64.decode(dto.signature), author.keys.signPk, contentKey)
            .decodeToString()
    }
}

fun ObjectDto.header() = ObjectHeader(id, kind, authorId, parentId, threadId, audience, createdAt, expiresAt, version, blobIds, refIds)
