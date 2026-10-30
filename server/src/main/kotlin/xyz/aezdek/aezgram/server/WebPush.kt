package xyz.aezdek.aezgram.server

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import xyz.aezdek.aezgram.protocol.PushPrefs
import xyz.aezdek.aezgram.protocol.PushSubscriptionRequest
import xyz.aezdek.aezgram.protocol.Wire
import xyz.aezdek.aezgram.protocol.WsType
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What a push carries: the kind of event and who caused it. No content, ever. */
@Serializable
data class PushMessage(val type: String, val from: String? = null, val fromId: String? = null, val ref: String? = null)

/**
 * Web Push (RFC 8030) with message encryption (RFC 8291, aes128gcm) and VAPID (RFC 8292), on plain JDK crypto.
 * The VAPID key pair is generated on first boot and kept in the database, so nothing needs configuring.
 */
class WebPush(private val db: Db, private val clock: Clock, private val subject: String) {
    private val log = LoggerFactory.getLogger(WebPush::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val random = SecureRandom()

    private val vapid: KeyPair by lazy { loadOrCreateVapid() }

    /** Uncompressed P-256 point, base64url: the browser's `applicationServerKey`. */
    val publicKey: String by lazy { B64u.enc(uncompressed(vapid.public as ECPublicKey)) }

    private fun loadOrCreateVapid(): KeyPair = db.tx {
        val factory = KeyFactory.getInstance("EC")
        val stored = one("SELECT value FROM kv WHERE key = 'vapid'") { it.str("value") }
        if (stored != null) {
            val (priv, pub) = stored.split(".")
            KeyPair(
                factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv))),
            )
        } else {
            val pair = newP256()
            val encoded = Base64.getEncoder().encodeToString(pair.private.encoded) + "." + Base64.getEncoder().encodeToString(pair.public.encoded)
            exec("INSERT INTO kv (key, value) VALUES ('vapid', ?)", encoded)
            pair
        }
    }

    fun subscribe(session: Session, req: PushSubscriptionRequest) = db.tx {
        val endpoint = URI(req.endpoint)
        if (endpoint.scheme != "https") badRequest("Push endpoints must be https")
        exec(
            "INSERT OR REPLACE INTO push_subscriptions (endpoint, user_id, session_id, p256dh, auth, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            req.endpoint, session.userId, session.id, B64u.dec(req.p256dh, "p256dh", size = 65), B64u.dec(req.auth, "auth", size = 16), clock.now(),
        )
    }

    fun unsubscribe(session: Session, endpoint: String) = db.tx {
        exec("DELETE FROM push_subscriptions WHERE endpoint = ? AND user_id = ?", endpoint, session.userId)
    }

    fun setPrefs(session: Session, prefs: PushPrefs) = db.tx {
        if (prefs.mutedTypes.size > 32 || prefs.mutedThreads.size > 500) badRequest("Too many entries")
        exec(
            "INSERT OR REPLACE INTO push_prefs (user_id, muted_types, muted_threads) VALUES (?, ?, ?)",
            session.userId, prefs.mutedTypes.joinToString(","), prefs.mutedThreads.joinToString(","),
        )
    }

    fun prefs(userId: String): PushPrefs = db.tx {
        one("SELECT muted_types, muted_threads FROM push_prefs WHERE user_id = ?", userId) {
            PushPrefs(it.str("muted_types").split(',').filter(String::isNotEmpty), it.str("muted_threads").split(',').filter(String::isNotEmpty))
        }
    } ?: PushPrefs()

    /** Test rings always go through; everything else honours the user's mirrored notification switches. */
    fun wanted(userId: String, message: PushMessage): Boolean {
        if (message.type == WsType.RING_TEST) return true
        val prefs = prefs(userId)
        if (message.type in prefs.mutedTypes) return false
        return !(message.type in setOf("message", "reaction") && message.ref != null && message.ref in prefs.mutedThreads)
    }

    fun sendToUser(userId: String, message: PushMessage, urgent: Boolean) {
        if (!wanted(userId, message)) return
        val subscriptions = db.tx {
            query("SELECT endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?", userId) {
                Triple(it.str("endpoint"), it.bytes("p256dh"), it.bytes("auth"))
            }
        }
        val body = Wire.json.encodeToString(PushMessage.serializer(), message).encodeToByteArray()
        for ((endpoint, p256dh, auth) in subscriptions) {
            val status = runCatching { send(endpoint, p256dh, auth, body, urgent) }.getOrElse {
                log.warn("push to {} failed: {}", URI(endpoint).host, it.message)
                continue
            }
            if (status == 404 || status == 410) db.tx { exec("DELETE FROM push_subscriptions WHERE endpoint = ?", endpoint) }
        }
    }

    private fun send(endpoint: String, p256dh: ByteArray, auth: ByteArray, plaintext: ByteArray, urgent: Boolean): Int {
        val request = HttpRequest.newBuilder(URI(endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("TTL", if (urgent) "60" else "86400")
            .header("Urgency", if (urgent) "high" else "normal")
            .header("Authorization", "vapid t=${vapidJwt(endpoint)}, k=$publicKey")
            .POST(HttpRequest.BodyPublishers.ofByteArray(encrypt(plaintext, p256dh, auth)))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /** RFC 8291: one aes128gcm record. */
    fun encrypt(plaintext: ByteArray, uaPublic: ByteArray, authSecret: ByteArray): ByteArray {
        val ephemeral = newP256()
        val asPublic = uncompressed(ephemeral.public as ECPublicKey)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeral.private)
        agreement.doPhase(decodePoint(uaPublic), true)
        val ecdhSecret = agreement.generateSecret()

        val keyInfo = "WebPush: info".encodeToByteArray() + byteArrayOf(0) + uaPublic + asPublic
        val ikm = hkdf(salt = authSecret, ikm = ecdhSecret, info = keyInfo, length = 32)
        val salt = ByteArray(16).also(random::nextBytes)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".encodeToByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".encodeToByteArray() + byteArrayOf(0), 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext + byteArrayOf(2))
        val recordSize = 4096
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + ciphertext.size)
            .put(salt).putInt(recordSize).put(asPublic.size.toByte()).put(asPublic).put(ciphertext).array()
    }

    private fun vapidJwt(endpoint: String): String {
        val uri = URI(endpoint)
        val audience = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val header = b64.encodeToString("""{"typ":"JWT","alg":"ES256"}""".encodeToByteArray())
        val exp = clock.now() / 1000 + 12 * 3600
        val claims = b64.encodeToString("""{"aud":"$audience","exp":$exp,"sub":"$subject"}""".encodeToByteArray())
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(vapid.private as ECPrivateKey)
        signer.update("$header.$claims".encodeToByteArray())
        return "$header.$claims.${b64.encodeToString(derToJose(signer.sign()))}"
    }

    companion object {
        private val p256: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        }

        fun newP256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun uncompressed(key: ECPublicKey): ByteArray =
            byteArrayOf(4) + fixed(key.w.affineX.toByteArray()) + fixed(key.w.affineY.toByteArray())

        fun decodePoint(bytes: ByteArray): ECPublicKey {
            require(bytes.size == 65 && bytes[0] == 4.toByte()) { "not an uncompressed P-256 point" }
            val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256)) as ECPublicKey
        }

        private fun fixed(bytes: ByteArray): ByteArray = when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }

        fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            return mac.doFinal(info + byteArrayOf(1)).copyOf(length)
        }

        /** DER ECDSA signature → the 64-byte R||S form JWS wants. */
        fun derToJose(der: ByteArray): ByteArray {
            var offset = 2
            if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7f
            val rLength = der[offset + 1].toInt()
            val r = der.copyOfRange(offset + 2, offset + 2 + rLength)
            offset += 2 + rLength
            val sLength = der[offset + 1].toInt()
            val s = der.copyOfRange(offset + 2, offset + 2 + sLength)
            return fixed(r) + fixed(s)
        }
    }
}
