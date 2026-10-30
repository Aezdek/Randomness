package xyz.aezdek.aezgram.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {

    private val params = KdfParams.FAST_FOR_TESTS

    private fun header(id: String = "obj1", version: Int = 1) = ObjectHeader(
        id = id, kind = "post", authorId = "alice", parentId = null, threadId = null, audience = "followers",
        createdAt = 1_700_000_000_000, expiresAt = null, version = version, blobIds = listOf("b2", "b1"), refIds = emptyList(),
    )

    private inline fun assertCryptoFails(block: () -> Unit) {
        try {
            block()
            fail("expected CryptoException")
        } catch (_: CryptoException) {
        }
    }

    @Test
    fun passwordDerivationIsDeterministicAndSplitsKeys() {
        val salt = ByteArray(16) { it.toByte() }
        val a = PasswordKeys.derive("correct horse", salt, params)
        val b = PasswordKeys.derive("correct horse", salt, params)
        assertArrayEquals(a.authKey, b.authKey)
        assertArrayEquals(a.vaultKey, b.vaultKey)
        assertFalse(a.authKey.contentEquals(a.vaultKey))
        val other = PasswordKeys.derive("correct horsf", salt, params)
        assertFalse(a.authKey.contentEquals(other.authKey))
    }

    @Test
    fun passwordsAreNormalisedBeforeHashing() {
        // "é" precomposed vs. "e" + combining acute: same password after NFKC.
        val salt = ByteArray(16)
        val composed = PasswordKeys.derive("café", salt, params)
        val decomposed = PasswordKeys.derive("café", salt, params)
        assertArrayEquals(composed.authKey, decomposed.authKey)
        assertEquals("636166c3a9", PasswordKeys.argonInput("café"))
    }

    @Test
    fun vaultRoundTripsAndRejectsTheWrongPassword() {
        val keys = AccountKeys.generate()
        val salt = PasswordKeys.newSalt()
        val vault = keys.sealVault(PasswordKeys.derive("pw-one", salt, params).vaultKey)
        val opened = AccountKeys.openVault(vault, PasswordKeys.derive("pw-one", salt, params).vaultKey)
        assertArrayEquals(keys.signPk, opened.signPk)
        assertArrayEquals(keys.signSk, opened.signSk)
        assertArrayEquals(keys.boxPk, opened.boxPk)
        assertArrayEquals(keys.boxSk, opened.boxSk)
        assertCryptoFails { AccountKeys.openVault(vault, PasswordKeys.derive("pw-two", salt, params).vaultKey) }
    }

    @Test
    fun publicKeysAreSelfSigned() {
        val keys = AccountKeys.generate()
        assertTrue(keys.publicKeys().isSelfSigned())
        val swapped = PublicKeys(keys.signPk, AccountKeys.generate().boxPk, keys.publicKeys().boxPkSig)
        assertFalse(swapped.isSelfSigned())
    }

    @Test
    fun objectsSealOpenAndRejectTampering() {
        val alice = AccountKeys.generate()
        val bob = AccountKeys.generate()
        val key = ObjectCrypto.newContentKey()
        val sealed = ObjectCrypto.seal(header(), "hello".encodeToByteArray(), key, alice)

        val wrap = ObjectCrypto.wrap(key, bob.boxPk)
        val unwrapped = ObjectCrypto.unwrap(wrap, bob, sealed.commitment)
        val plain = ObjectCrypto.open(sealed.header, sealed.payload, sealed.commitment, sealed.signature, alice.signPk, unwrapped)
        assertEquals("hello", plain.decodeToString())

        // Blob order is canonicalised, so the same set in another order still verifies.
        val reordered = sealed.header.copy(blobIds = listOf("b1", "b2"))
        ObjectCrypto.open(reordered, sealed.payload, sealed.commitment, sealed.signature, alice.signPk, key)

        // Any change to a server-visible field breaks the signature.
        assertCryptoFails {
            ObjectCrypto.open(sealed.header.copy(parentId = "x"), sealed.payload, sealed.commitment, sealed.signature, alice.signPk, key)
        }
        assertCryptoFails {
            ObjectCrypto.open(sealed.header.copy(audience = "members"), sealed.payload, sealed.commitment, sealed.signature, alice.signPk, key)
        }
        // A signature by someone else.
        assertCryptoFails {
            ObjectCrypto.open(sealed.header, sealed.payload, sealed.commitment, sealed.signature, bob.signPk, key)
        }
        // A flipped ciphertext bit.
        val flipped = sealed.payload.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
        assertCryptoFails {
            ObjectCrypto.open(sealed.header, flipped, sealed.commitment, sealed.signature, alice.signPk, key)
        }
    }

    @Test
    fun aWrapOfTheWrongKeyIsRejectedByTheCommitment() {
        val alice = AccountKeys.generate()
        val bob = AccountKeys.generate()
        val sealed = ObjectCrypto.seal(header(), "x".encodeToByteArray(), ObjectCrypto.newContentKey(), alice)
        val bogus = ObjectCrypto.wrap(ObjectCrypto.newContentKey(), bob.boxPk)
        assertCryptoFails { ObjectCrypto.unwrap(bogus, bob, sealed.commitment) }
        // A wrap for someone else does not open for bob.
        val forAlice = ObjectCrypto.wrap(ObjectCrypto.newContentKey(), alice.boxPk)
        assertCryptoFails { ObjectCrypto.unwrap(forAlice, bob, sealed.commitment) }
    }

    @Test
    fun childKeysAreDerivedFromTheParent() {
        val parent = ObjectCrypto.newContentKey()
        val a = ObjectCrypto.childKey(parent, "comment1")
        assertArrayEquals(a, ObjectCrypto.childKey(parent, "comment1"))
        assertFalse(a.contentEquals(ObjectCrypto.childKey(parent, "comment2")))
        assertFalse(a.contentEquals(ObjectCrypto.childKey(ObjectCrypto.newContentKey(), "comment1")))
    }

    @Test
    fun editsKeepTheKeyButNeedTheRightVersion() {
        val alice = AccountKeys.generate()
        val key = ObjectCrypto.newContentKey()
        val v2 = ObjectCrypto.seal(header(version = 2), "edited".encodeToByteArray(), key, alice)
        assertEquals("edited", ObjectCrypto.decryptPayload("obj1", 2, v2.payload, key).decodeToString())
        assertCryptoFails { ObjectCrypto.decryptPayload("obj1", 1, v2.payload, key) }
    }

    @Test
    fun mediaRoundTripsAtEveryBoundary() {
        val key = MediaCipher.newFileKey()
        for (size in listOf(0, 1, 15, 16, 17, 32, 33, 100)) {
            val plain = Sodium.random(size)
            val cipher = MediaCipher.encrypt(plain, key, chunkSize = 16)
            assertEquals(MediaCipher.cipherLength(size.toLong(), 16), cipher.size.toLong())
            assertArrayEquals("size $size", plain, MediaCipher.decrypt(cipher, key))
        }
    }

    @Test
    fun mediaChunksCanBeOpenedOutOfOrder() {
        val key = MediaCipher.newFileKey()
        val plain = Sodium.random(1000)
        val cipher = MediaCipher.encrypt(plain, key, chunkSize = 64)
        val header = MediaCipher.parseHeader(cipher)
        for (index in (0 until header.chunkCount).reversed()) {
            val range = header.cipherRange(index)
            val chunk = MediaCipher.openChunk(header, index, cipher.copyOfRange(range.first.toInt(), range.last.toInt() + 1), key)
            val start = (index * 64).toInt()
            assertArrayEquals(plain.copyOfRange(start, start + chunk.size), chunk)
        }
    }

    @Test
    fun mediaRejectsTruncationReorderingAndTampering() {
        val key = MediaCipher.newFileKey()
        val cipher = MediaCipher.encrypt(Sodium.random(100), key, chunkSize = 16)
        val header = MediaCipher.parseHeader(cipher)

        // Truncated: drop the final chunk.
        val last = header.cipherRange(header.chunkCount - 1)
        assertCryptoFails { MediaCipher.decrypt(cipher.copyOfRange(0, last.first.toInt()), key) }
        // Truncated and relabelled as shorter: the header is in every chunk's AD.
        val shorter = cipher.copyOf().also { it[15] = 32 }
        assertCryptoFails { MediaCipher.decrypt(shorter.copyOfRange(0, MediaCipher.cipherLength(32, 16).toInt()), key) }
        // Swapped chunks.
        val r0 = header.cipherRange(0)
        val r1 = header.cipherRange(1)
        val swapped = cipher.copyOf()
        cipher.copyInto(swapped, r1.first.toInt(), r0.first.toInt(), r0.last.toInt() + 1)
        cipher.copyInto(swapped, r0.first.toInt(), r1.first.toInt(), r1.last.toInt() + 1)
        assertCryptoFails { MediaCipher.decrypt(swapped, key) }
        // A chunk from another file encrypted under the same key.
        val other = MediaCipher.encrypt(Sodium.random(100), key, chunkSize = 16)
        val spliced = cipher.copyOf()
        other.copyInto(spliced, r0.first.toInt(), r0.first.toInt(), r0.last.toInt() + 1)
        assertCryptoFails { MediaCipher.decrypt(spliced, key) }
        // Wrong key.
        assertCryptoFails { MediaCipher.decrypt(cipher, MediaCipher.newFileKey()) }
    }

    @Test
    fun safetyNumbersAreStableAndSymmetric() {
        val a = AccountKeys.generate().signPk
        val b = AccountKeys.generate().signPk
        assertEquals(30, SafetyNumber.code(a).length)
        assertTrue(SafetyNumber.code(a).all { it.isDigit() })
        assertEquals(SafetyNumber.code(a), SafetyNumber.code(a.copyOf()))
        assertEquals(SafetyNumber.pair(a, b), SafetyNumber.pair(b, a))
        assertNotEquals(SafetyNumber.code(a), SafetyNumber.code(b))
        val qr = SafetyNumber.qrPayload("u1", a)
        val parsed = SafetyNumber.parseQr(qr)!!
        assertEquals("u1", parsed.first)
        assertArrayEquals(a, parsed.second)
        assertEquals(null, SafetyNumber.parseQr("https://example.com"))
    }

    @Test
    fun signalsAreSignedSealedAndFresh() {
        val alice = AccountKeys.generate()
        val bob = AccountKeys.generate()
        val sent = SignalCrypto.seal("offer".encodeToByteArray(), "alice", "bob", 1_000_000, alice, bob.boxPk)
        assertEquals("offer", SignalCrypto.open(sent, "alice", "bob", alice.signPk, bob, now = 1_000_500).decodeToString())
        assertCryptoFails { SignalCrypto.open(sent, "alice", "bob", alice.signPk, bob, now = 1_000_000 + 10 * 60_000) }
        assertCryptoFails { SignalCrypto.open(sent, "mallory", "bob", alice.signPk, bob, now = 1_000_500) }
        assertCryptoFails { SignalCrypto.open(sent, "alice", "bob", bob.signPk, bob, now = 1_000_500) }
        assertCryptoFails { SignalCrypto.open(sent, "alice", "bob", alice.signPk, alice, now = 1_000_500) }
    }
}
