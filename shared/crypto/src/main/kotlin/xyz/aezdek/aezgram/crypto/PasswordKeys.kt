package xyz.aezdek.aezgram.crypto

import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import java.text.Normalizer

/** Argon2id cost. Stored per user on the server so it can be raised later without breaking old accounts. */
data class KdfParams(val opsLimit: Long, val memLimitBytes: Int) {
    companion object {
        /** About a second on a phone or in a browser's WebAssembly libsodium. */
        val DEFAULT = KdfParams(opsLimit = 3, memLimitBytes = 64 * 1024 * 1024)

        /** Argon2id's minimum memory cost; only for tests. */
        val FAST_FOR_TESTS = KdfParams(opsLimit = 1, memLimitBytes = 8 * 1024 * 1024)
    }
}

/**
 * Password → (authKey, vaultKey), the Bitwarden split.
 *
 * Only [Derived.authKey] is ever sent to the server, which stores a hash of it. [Derived.vaultKey] stays on the device
 * and decrypts the account's private keys (see [AccountKeys.openVault]).
 */
object PasswordKeys {
    const val SALT_BYTES = 16
    private const val ARGON2ID13 = 2
    private const val AUTH_CONTEXT = "aezgauth"
    private const val VAULT_CONTEXT = "aezgvalt"

    class Derived(val authKey: ByteArray, val vaultKey: ByteArray)

    fun newSalt(): ByteArray = Sodium.random(SALT_BYTES)

    fun derive(password: String, salt: ByteArray, params: KdfParams): Derived {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        Sodium.init()
        val master = PasswordHash.pwhash(
            outputLength = 32,
            password = argonInput(password),
            salt = salt.asUByteArray(),
            opsLimit = params.opsLimit.toULong(),
            memLimit = params.memLimitBytes,
            algorithm = ARGON2ID13,
        )
        val authKey = Kdf.deriveFromKey(1u, 32, AUTH_CONTEXT, master).asByteArray()
        val vaultKey = Kdf.deriveFromKey(2u, 32, VAULT_CONTEXT, master).asByteArray()
        master.fill(0u)
        return Derived(authKey, vaultKey)
    }

    /**
     * The exact string handed to Argon2id: lowercase hex of the NFKC-normalised password's UTF-8 bytes.
     *
     * Hex keeps the input ASCII. The JVM/Android binding passes `String.length` (UTF-16 units) as the byte count, so a
     * raw non-ASCII password would hash differently there than in libsodium.js.
     */
    fun argonInput(password: String): String =
        utf8(Normalizer.normalize(password, Normalizer.Form.NFKC)).toHex()
}
