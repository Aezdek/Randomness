package xyz.aezdek.hush.crypto

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.pwhash.PasswordHash

/**
 * Sealing one key with another, for the server's AezCrypt keyring: the database key is kept sealed under the
 * admin's server password (Argon2id) and under this machine's key, never in the clear.
 *
 * `sealed = nonce(24) || XChaCha20-Poly1305(plain, ad)`.
 */
object KeySealing {
    private const val NONCE_BYTES = 24
    private const val ARGON2ID13 = 2

    /** A 32-byte key from a password, with the same input normalisation as account passwords ([PasswordKeys.argonInput]). */
    fun passwordKey(password: String, salt: ByteArray, params: KdfParams): ByteArray {
        require(salt.size == PasswordKeys.SALT_BYTES) { "salt must be ${PasswordKeys.SALT_BYTES} bytes" }
        Sodium.init()
        return PasswordHash.pwhash(
            outputLength = 32,
            password = PasswordKeys.argonInput(password),
            salt = salt.asUByteArray(),
            opsLimit = params.opsLimit.toULong(),
            memLimit = params.memLimitBytes,
            algorithm = ARGON2ID13,
        ).asByteArray()
    }

    fun seal(key: ByteArray, plain: ByteArray, ad: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        Sodium.init()
        val nonce = Sodium.random(NONCE_BYTES)
        val box = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            plain.asUByteArray(), ad.asUByteArray(), nonce.asUByteArray(), key.asUByteArray(),
        ).asByteArray()
        return concat(nonce, box)
    }

    /** The plain bytes, or null if [key] or [ad] is wrong or [sealed] was changed. */
    fun open(key: ByteArray, sealed: ByteArray, ad: ByteArray): ByteArray? {
        if (key.size != 32 || sealed.size < NONCE_BYTES + 16) return null
        Sodium.init()
        return runCatching {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                sealed.copyOfRange(NONCE_BYTES, sealed.size).asUByteArray(), ad.asUByteArray(),
                sealed.copyOfRange(0, NONCE_BYTES).asUByteArray(), key.asUByteArray(),
            ).asByteArray()
        }.getOrNull()
    }
}
