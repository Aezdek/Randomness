package xyz.aezdek.hush.server

import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** An error the API reports as `{error, message}` with [status]. */
class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

fun badRequest(message: String, code: String = "bad_request"): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, message)
fun unauthorized(message: String = "Sign in again"): Nothing = throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", message)
fun forbidden(message: String): Nothing = throw ApiException(HttpStatusCode.Forbidden, "forbidden", message)
fun notFound(message: String = "Not found"): Nothing = throw ApiException(HttpStatusCode.NotFound, "not_found", message)
fun conflict(message: String, code: String = "conflict"): Nothing = throw ApiException(HttpStatusCode.Conflict, code, message)

/** Injectable time, so expiry can be tested without waiting. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

object Ids {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private const val INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    fun random(bytes: Int = 16): String = b64.encodeToString(ByteArray(bytes).also(random::nextBytes))
    fun token(): String = random(32)
    fun invite(): String = (1..10).map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")
        .let { "${it.take(5)}-${it.drop(5)}" }

    private val objectId = Regex("^[A-Za-z0-9_-]{16,32}$")
    fun isObjectId(id: String) = objectId.matches(id)
}

object B64u {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun enc(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun dec(text: String, field: String, size: Int? = null, max: Int? = null): ByteArray {
        val bytes = try {
            decoder.decode(text)
        } catch (_: IllegalArgumentException) {
            badRequest("$field is not base64url")
        }
        if (size != null && bytes.size != size) badRequest("$field must be $size bytes")
        if (max != null && bytes.size > max) badRequest("$field is too large")
        return bytes
    }
}

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

val USERNAME = Regex("^[a-z0-9._]{2,30}$")
