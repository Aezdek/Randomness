package xyz.aezdek.aezgram.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-platform test vectors. Kotlin writes crypto-vectors/kotlin-v1.json (when missing, or with
 * -Daezgram.writeVectors=true); the web client writes web-v1.json. Each side must open, verify and decrypt the
 * other's output byte for byte, so Android, the web and the server tests can never drift apart.
 */
class VectorsTest {
    private val dir = File(System.getProperty("aezgram.vectorsDir") ?: "crypto-vectors")
    private val json = Json { prettyPrint = true }

    @Test
    fun kotlinVectorsVerify() {
        val file = File(dir, "kotlin-v1.json")
        if (!file.exists() || System.getProperty("aezgram.writeVectors") == "true") {
            dir.mkdirs()
            file.writeText(json.encodeToString(JsonObject.serializer(), generate()) + "\n")
        }
        verify(json.parseToJsonElement(file.readText()).jsonObject)
    }

    @Test
    fun webVectorsVerify() {
        val file = File(dir, "web-v1.json")
        if (!file.exists()) return
        verify(json.parseToJsonElement(file.readText()).jsonObject)
    }

    private fun generate(): JsonObject {
        val password = "pässwörd ✓ 🔐"
        val salt = ByteArray(16) { (it * 7).toByte() }
        val params = KdfParams.FAST_FOR_TESTS
        val derived = PasswordKeys.derive(password, salt, params)
        val account = AccountKeys.fromSeeds(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val pub = account.publicKeys()

        val header = ObjectHeader(
            id = "vectorObject0001", kind = "post", authorId = "author1", parentId = "parent1", threadId = null,
            audience = "followers", createdAt = 1_700_000_000_123, expiresAt = 1_700_086_400_000, version = 3,
            blobIds = listOf("bbb", "aaa"), refIds = listOf("r2", "r1"),
        )
        val contentKey = ByteArray(32) { (it + 100).toByte() }
        val plaintext = """{"caption":"héllo 🌍","media":[]}"""
        val sealed = ObjectCrypto.seal(header, plaintext.encodeToByteArray(), contentKey, account)

        val fileKey = ByteArray(32) { (255 - it).toByte() }
        val plainMedia = ByteArray(1000) { (it * 13).toByte() }
        val other = AccountKeys.fromSeeds(ByteArray(32) { 3 }, ByteArray(32) { 4 })
        val signal = SignalCrypto.seal("""{"type":"offer","sdp":"v=0"}""".encodeToByteArray(), "alice", "bob", 1_700_000_000_000, other, account.boxPk)

        return buildJsonObject {
            putJsonObject("password") {
                put("password", password)
                put("salt", B64.encode(salt))
                put("opsLimit", params.opsLimit)
                put("memLimit", params.memLimitBytes)
                put("argonInput", PasswordKeys.argonInput(password))
                put("authKey", B64.encode(derived.authKey))
                put("vaultKey", B64.encode(derived.vaultKey))
            }
            putJsonObject("account") {
                put("signSeed", B64.encode(ByteArray(32) { 1 }))
                put("boxSeed", B64.encode(ByteArray(32) { 2 }))
                put("signPk", B64.encode(account.signPk))
                put("signSk", B64.encode(account.signSk))
                put("boxPk", B64.encode(account.boxPk))
                put("boxSk", B64.encode(account.boxSk))
                put("boxPkSig", B64.encode(pub.boxPkSig))
                put("vault", B64.encode(account.sealVault(derived.vaultKey)))
            }
            putJsonObject("object") {
                putJsonObject("header") {
                    put("id", header.id)
                    put("kind", header.kind)
                    put("authorId", header.authorId)
                    put("parentId", header.parentId)
                    put("threadId", header.threadId)
                    put("audience", header.audience)
                    put("createdAt", header.createdAt)
                    put("expiresAt", header.expiresAt)
                    put("version", header.version)
                    putJsonArray("blobIds") { header.blobIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    putJsonArray("refIds") { header.refIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                }
                put("plaintext", plaintext)
                put("contentKey", B64.encode(contentKey))
                put("payload", B64.encode(sealed.payload))
                put("commitment", B64.encode(sealed.commitment))
                put("signature", B64.encode(sealed.signature))
                put("wrap", B64.encode(ObjectCrypto.wrap(contentKey, account.boxPk)))
            }
            putJsonObject("child") {
                put("parentKey", B64.encode(contentKey))
                put("childId", "comment-ü-1")
                put("key", B64.encode(ObjectCrypto.childKey(contentKey, "comment-ü-1")))
            }
            putJsonObject("media") {
                put("fileKey", B64.encode(fileKey))
                put("chunkSize", 256)
                put("plain", B64.encode(plainMedia))
                put("cipher", B64.encode(MediaCipher.encrypt(plainMedia, fileKey, chunkSize = 256)))
            }
            putJsonObject("safety") {
                put("signPkA", B64.encode(account.signPk))
                put("signPkB", B64.encode(other.signPk))
                put("codeA", SafetyNumber.code(account.signPk))
                put("pair", SafetyNumber.pair(account.signPk, other.signPk))
            }
            putJsonObject("signal") {
                put("from", "alice")
                put("to", "bob")
                put("senderSignPk", B64.encode(other.signPk))
                put("sentAt", signal.sentAt)
                put("sealed", B64.encode(signal.sealed))
                put("signature", B64.encode(signal.signature))
                put("plaintext", """{"type":"offer","sdp":"v=0"}""")
            }
        }
    }

    private fun JsonObject.s(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.b(name: String) = B64.decode(s(name))

    private fun verify(v: JsonObject) {
        val pw = v.getValue("password").jsonObject
        assertEquals(pw.s("argonInput"), PasswordKeys.argonInput(pw.s("password")))
        val derived = PasswordKeys.derive(
            pw.s("password"), pw.b("salt"), KdfParams(pw.getValue("opsLimit").jsonPrimitive.long, pw.getValue("memLimit").jsonPrimitive.int),
        )
        assertArrayEquals(pw.b("authKey"), derived.authKey)
        assertArrayEquals(pw.b("vaultKey"), derived.vaultKey)

        val acc = v.getValue("account").jsonObject
        val account = AccountKeys.fromSeeds(acc.b("signSeed"), acc.b("boxSeed"))
        assertArrayEquals(acc.b("signPk"), account.signPk)
        assertArrayEquals(acc.b("boxPk"), account.boxPk)
        assertTrue(PublicKeys(account.signPk, account.boxPk, acc.b("boxPkSig")).isSelfSigned())
        val opened = AccountKeys.openVault(acc.b("vault"), derived.vaultKey)
        assertArrayEquals(account.signSk, opened.signSk)
        assertArrayEquals(account.boxSk, opened.boxSk)

        val obj = v.getValue("object").jsonObject
        val h = obj.getValue("header").jsonObject
        fun nullable(name: String) = h[name]?.jsonPrimitive?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
        val header = ObjectHeader(
            id = h.s("id"), kind = h.s("kind"), authorId = h.s("authorId"), parentId = nullable("parentId"), threadId = nullable("threadId"),
            audience = h.s("audience"), createdAt = h.getValue("createdAt").jsonPrimitive.long,
            expiresAt = nullable("expiresAt")?.toLong(), version = h.getValue("version").jsonPrimitive.int,
            blobIds = h.getValue("blobIds").jsonArray.map { it.jsonPrimitive.content },
            refIds = h.getValue("refIds").jsonArray.map { it.jsonPrimitive.content },
        )
        assertArrayEquals(obj.b("commitment"), ObjectCrypto.commitment(obj.b("contentKey")))
        val key = ObjectCrypto.unwrap(obj.b("wrap"), account, obj.b("commitment"))
        assertArrayEquals(obj.b("contentKey"), key)
        val plain = ObjectCrypto.open(header, obj.b("payload"), obj.b("commitment"), obj.b("signature"), account.signPk, key)
        assertEquals(obj.s("plaintext"), plain.decodeToString())

        val child = v.getValue("child").jsonObject
        assertArrayEquals(child.b("key"), ObjectCrypto.childKey(child.b("parentKey"), child.s("childId")))

        val media = v.getValue("media").jsonObject
        assertArrayEquals(media.b("plain"), MediaCipher.decrypt(media.b("cipher"), media.b("fileKey")))

        val safety = v.getValue("safety").jsonObject
        assertEquals(safety.s("codeA"), SafetyNumber.code(safety.b("signPkA")))
        assertEquals(safety.s("pair"), SafetyNumber.pair(safety.b("signPkA"), safety.b("signPkB")))

        val sig = v.getValue("signal").jsonObject
        val sentAt = sig.getValue("sentAt").jsonPrimitive.long
        val message = SignalCrypto.open(
            SignalCrypto.Sealed(sig.b("sealed"), sig.b("signature"), sentAt), sig.s("from"), sig.s("to"), sig.b("senderSignPk"), account, sentAt,
        )
        assertEquals(sig.s("plaintext"), message.decodeToString())
    }
}
