package xyz.aezdek.aezgram.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebPushTest {

    /** Decrypts like a browser (RFC 8291), proving the server's encryption is interoperable. */
    @Test
    fun aes128gcmRoundTrip() {
        val ua = WebPush.newP256()
        val uaPublic = WebPush.uncompressed(ua.public as ECPublicKey)
        val auth = ByteArray(16).also(SecureRandom()::nextBytes)
        val db = Db(java.io.File.createTempFile("push", ".db").also { it.deleteOnExit() })
        val push = WebPush(db, Clock.SYSTEM, "mailto:test@example.com")
        val body = push.encrypt("""{"type":"message"}""".encodeToByteArray(), uaPublic, auth)

        val buffer = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { buffer.get(it) }
        assertEquals(4096, buffer.int)
        val idLength = buffer.get().toInt()
        val asPublic = ByteArray(idLength).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ua.private)
        agreement.doPhase(WebPush.decodePoint(asPublic), true)
        val secret = agreement.generateSecret()
        val ikm = WebPush.hkdf(auth, secret, "WebPush: info".encodeToByteArray() + byteArrayOf(0) + uaPublic + asPublic, 32)
        val cek = WebPush.hkdf(salt, ikm, "Content-Encoding: aes128gcm".encodeToByteArray() + byteArrayOf(0), 16)
        val nonce = WebPush.hkdf(salt, ikm, "Content-Encoding: nonce".encodeToByteArray() + byteArrayOf(0), 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val plain = cipher.doFinal(ciphertext)
        assertEquals(2.toByte(), plain.last())
        assertEquals("""{"type":"message"}""", plain.copyOf(plain.size - 1).decodeToString())
        assertTrue(push.publicKey.length > 80)
        db.close()
    }

    @Test
    fun turnAnswersInEitherShapeAreParsed() {
        val array = Turn.parse(
            """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},
               {"urls":["turn:turn.cloudflare.com:3478?transport=udp"],"username":"u","credential":"c"}]}""",
        )
        assertEquals(2, array.iceServers.size)
        assertTrue(array.relay)
        val single = Turn.parse("""{"iceServers":{"urls":["turns:turn.cloudflare.com:443"],"username":"u","credential":"c"}}""")
        assertEquals("u", single.iceServers.single().username)
        assertTrue(single.relay)
    }

    @Test
    fun derSignaturesBecomeJose() {
        val r = ByteArray(33).also { it[1] = 0x7f }
        val s = ByteArray(31) { 1 }
        val der = byteArrayOf(0x30, (2 + r.size + 2 + s.size).toByte(), 0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        val jose = WebPush.derToJose(der)
        assertEquals(64, jose.size)
        assertEquals(0x7f.toByte(), jose[0])
        assertEquals(1.toByte(), jose[63])
        assertEquals(0.toByte(), jose[32])
    }
}
