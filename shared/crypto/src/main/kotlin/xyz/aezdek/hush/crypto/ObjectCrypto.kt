package xyz.aezdek.hush.crypto

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.box.Box

/**
 * The server-visible fields of an object. All of them are covered by the author's signature, so the server can route
 * and access-check on them but cannot change any of them unnoticed.
 */
data class ObjectHeader(
    val id: String,
    val kind: String,
    val authorId: String,
    val parentId: String?,
    val threadId: String?,
    val audience: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val version: Int,
    val blobIds: List<String>,
    val refIds: List<String>,
)

/** What an author uploads for one object version. */
class SealedObject(
    val header: ObjectHeader,
    val payload: ByteArray,
    val commitment: ByteArray,
    val signature: ByteArray,
)

/**
 * Object envelopes: every post, comment, story, message, reaction and profile is one of these.
 *
 * - A random 32-byte content key encrypts the payload with XChaCha20-Poly1305.
 * - The author signs the header, a hash of the ciphertext and a *commitment* to the content key.
 * - The content key reaches readers either as a sealed-box *wrap* to their X25519 key ([wrap]) or, for children such
 *   as likes, comments and chat messages, by derivation from the parent's key ([childKey]).
 *
 * Because the signature covers the key commitment rather than the wraps, wraps can be added later (new followers,
 * new members, key resets) without the author re-signing; a reader accepts a key only if it matches the commitment.
 */
object ObjectCrypto {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 24

    fun newContentKey(): ByteArray = Sodium.random(KEY_BYTES)

    fun commitment(contentKey: ByteArray): ByteArray =
        Sodium.blake2b(32, concat(utf8("aezgram/v1/commit\n"), contentKey))

    /** Key for a child object (like, comment, reply, reaction, chat message) from its parent's key. */
    fun childKey(parentKey: ByteArray, childId: String): ByteArray =
        Sodium.blake2b(KEY_BYTES, utf8("aezgram/v1/child\n$childId"), key = parentKey)

    fun encryptPayload(id: String, version: Int, plaintext: ByteArray, contentKey: ByteArray): ByteArray {
        Sodium.init()
        val nonce = Sodium.random(NONCE_BYTES)
        val sealed = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            plaintext.asUByteArray(), payloadAd(id, version).asUByteArray(), nonce.asUByteArray(),
            contentKey.asUByteArray(),
        ).asByteArray()
        return concat(nonce, sealed)
    }

    fun decryptPayload(id: String, version: Int, payload: ByteArray, contentKey: ByteArray): ByteArray {
        Sodium.init()
        if (payload.size < NONCE_BYTES + 16) throw CryptoException("payload too short")
        return try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                payload.copyOfRange(NONCE_BYTES, payload.size).asUByteArray(),
                payloadAd(id, version).asUByteArray(),
                payload.copyOfRange(0, NONCE_BYTES).asUByteArray(),
                contentKey.asUByteArray(),
            ).asByteArray()
        } catch (e: Exception) {
            throw CryptoException("payload did not decrypt", e)
        }
    }

    /** The exact bytes the author signs. Documented in docs/PROTOCOL.md and mirrored by the web client. */
    fun signingInput(header: ObjectHeader, payload: ByteArray, commitment: ByteArray): ByteArray = utf8(
        listOf(
            "aezgram/v1/object",
            header.id,
            header.kind,
            header.authorId,
            header.parentId.orEmpty(),
            header.threadId.orEmpty(),
            header.audience,
            header.createdAt.toString(),
            header.expiresAt?.toString().orEmpty(),
            header.version.toString(),
            header.blobIds.sorted().joinToString(","),
            header.refIds.sorted().joinToString(","),
            B64.encode(Sodium.blake2b(32, payload)),
            B64.encode(commitment),
        ).joinToString("\n"),
    )

    fun seal(header: ObjectHeader, plaintext: ByteArray, contentKey: ByteArray, author: AccountKeys): SealedObject {
        val payload = encryptPayload(header.id, header.version, plaintext, contentKey)
        val commitment = commitment(contentKey)
        val signature = Signing.sign(signingInput(header, payload, commitment), author.signSk)
        return SealedObject(header, payload, commitment, signature)
    }

    /**
     * Verifies the author's signature and that [contentKey] matches the signed commitment, then decrypts.
     * Throws [CryptoException] if anything does not check out.
     */
    fun open(
        header: ObjectHeader,
        payload: ByteArray,
        commitment: ByteArray,
        signature: ByteArray,
        authorSignPk: ByteArray,
        contentKey: ByteArray,
    ): ByteArray {
        if (!Signing.verify(signingInput(header, payload, commitment), signature, authorSignPk)) {
            throw CryptoException("bad signature on ${header.id}")
        }
        if (!Sodium.constantTimeEquals(commitment(contentKey), commitment)) {
            throw CryptoException("content key does not match the commitment on ${header.id}")
        }
        return decryptPayload(header.id, header.version, payload, contentKey)
    }

    /** Seals a content key to one reader. */
    fun wrap(contentKey: ByteArray, recipientBoxPk: ByteArray): ByteArray {
        Sodium.init()
        return Box.seal(contentKey.asUByteArray(), recipientBoxPk.asUByteArray()).asByteArray()
    }

    /** Opens a wrap and checks it against the object's signed commitment. */
    fun unwrap(wrap: ByteArray, me: AccountKeys, commitment: ByteArray): ByteArray {
        Sodium.init()
        val key = try {
            Box.sealOpen(wrap.asUByteArray(), me.boxPk.asUByteArray(), me.boxSk.asUByteArray()).asByteArray()
        } catch (e: Exception) {
            throw CryptoException("wrap did not open", e)
        }
        if (!Sodium.constantTimeEquals(commitment(key), commitment)) {
            throw CryptoException("wrapped key does not match the commitment")
        }
        return key
    }

    private fun payloadAd(id: String, version: Int) = utf8("aezgram/v1/payload\n$id\n$version")
}
