package xyz.aezdek.aezgram.crypto

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.signature.Signature

/** The public half of an account: what other members pin and verify against. */
class PublicKeys(val signPk: ByteArray, val boxPk: ByteArray, val boxPkSig: ByteArray) {

    /** The box key must be signed by the identity key, or the server could swap in its own. */
    fun isSelfSigned(): Boolean = Signing.verify(boxKeyMessage(boxPk), boxPkSig, signPk)

    fun safetyCode(): String = SafetyNumber.code(signPk)

    companion object {
        internal fun boxKeyMessage(boxPk: ByteArray) = concat(utf8("aezgram/v1/boxkey\n"), boxPk)
    }
}

/**
 * An account's long-term keys: an Ed25519 identity key that signs everything the account publishes, and an X25519
 * key that content keys are sealed to. Shared by all of the account's devices through the server-stored vault.
 */
class AccountKeys(
    val signPk: ByteArray,
    val signSk: ByteArray,
    val boxPk: ByteArray,
    val boxSk: ByteArray,
) {
    fun publicKeys(): PublicKeys =
        PublicKeys(signPk, boxPk, Signing.sign(PublicKeys.boxKeyMessage(boxPk), signSk))

    /** `nonce(24) || XChaCha20-Poly1305(0x01 || signSk || boxSk)`, sealed with the password-derived vault key. */
    fun sealVault(vaultKey: ByteArray): ByteArray {
        Sodium.init()
        val nonce = Sodium.random(NONCE_BYTES)
        val plain = concat(byteArrayOf(VAULT_VERSION), signSk, boxSk)
        val sealed = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            plain.asUByteArray(), VAULT_AD.asUByteArray(), nonce.asUByteArray(), vaultKey.asUByteArray(),
        ).asByteArray()
        plain.fill(0)
        return concat(nonce, sealed)
    }

    companion object {
        private const val NONCE_BYTES = 24
        private const val VAULT_VERSION: Byte = 1
        private val VAULT_AD = utf8("aezgram/v1/vault")

        /** Deterministic keys from two 32-byte seeds. Only for test vectors; real accounts use [generate]. */
        fun fromSeeds(signSeed: ByteArray, boxSeed: ByteArray): AccountKeys {
            Sodium.init()
            val sign = Signature.seedKeypair(signSeed.asUByteArray())
            val box = Box.seedKeypair(boxSeed.asUByteArray())
            return AccountKeys(
                sign.publicKey.asByteArray(), sign.secretKey.asByteArray(),
                box.publicKey.asByteArray(), box.secretKey.asByteArray(),
            )
        }

        fun generate(): AccountKeys {
            Sodium.init()
            val sign = Signature.keypair()
            val box = Box.keypair()
            return AccountKeys(
                sign.publicKey.asByteArray(), sign.secretKey.asByteArray(),
                box.publicKey.asByteArray(), box.secretKey.asByteArray(),
            )
        }

        /** Throws [CryptoException] for a wrong password (wrong vault key) or a tampered vault. */
        fun openVault(vault: ByteArray, vaultKey: ByteArray): AccountKeys {
            Sodium.init()
            if (vault.size < NONCE_BYTES + 16) throw CryptoException("vault too short")
            val plain = try {
                AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                    vault.copyOfRange(NONCE_BYTES, vault.size).asUByteArray(),
                    VAULT_AD.asUByteArray(),
                    vault.copyOfRange(0, NONCE_BYTES).asUByteArray(),
                    vaultKey.asUByteArray(),
                ).asByteArray()
            } catch (e: Exception) {
                throw CryptoException("vault did not open (wrong password?)", e)
            }
            if (plain.size != 1 + 64 + 32 || plain[0] != VAULT_VERSION) throw CryptoException("unknown vault format")
            val signSk = plain.copyOfRange(1, 65)
            val boxSk = plain.copyOfRange(65, 97)
            plain.fill(0)
            return AccountKeys(
                signPk = Signature.ed25519SkToPk(signSk.asUByteArray()).asByteArray(),
                signSk = signSk,
                boxPk = ScalarMultiplication.scalarMultiplicationBase(boxSk.asUByteArray()).asByteArray(),
                boxSk = boxSk,
            )
        }
    }
}

/** Detached Ed25519. */
object Signing {
    fun sign(message: ByteArray, signSk: ByteArray): ByteArray {
        Sodium.init()
        return Signature.detached(message.asUByteArray(), signSk.asUByteArray()).asByteArray()
    }

    fun verify(message: ByteArray, signature: ByteArray, signPk: ByteArray): Boolean {
        Sodium.init()
        if (signature.size != 64 || signPk.size != 32) return false
        return try {
            Signature.verifyDetached(signature.asUByteArray(), message.asUByteArray(), signPk.asUByteArray())
            true
        } catch (_: Exception) {
            false
        }
    }
}
