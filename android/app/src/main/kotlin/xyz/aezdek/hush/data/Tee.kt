package xyz.aezdek.hush.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The phone's secure hardware, as far as an app can use it: keys made here are kept and used inside the secure chip
 * (Samsung's Knox Vault, Google's Titan and the like, which Android calls StrongBox) when the phone has one, else in
 * its trusted environment (the TEE). They can be used on this phone only and can never be copied off it.
 */
object Tee {
    private const val STORE = "AndroidKeyStore"
    private const val PIN_KEY = "hush.disguise.pin"
    const val SESSION_KEY = "hush.session"

    private fun store() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun pinKey(): SecretKey {
        (store().getEntry(PIN_KEY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        fun make(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(PIN_KEY, KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256)
            if (strongBox) spec.setIsStrongBoxBacked(true)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).apply { init(spec.build()) }.generateKey()
        }
        // The secure chip first; a phone without one (or whose chip refuses) falls back to the TEE.
        return runCatching { make(strongBox = true) }.getOrElse { make(strongBox = false) }
    }

    /**
     * [secret] mixed with a key only this phone's secure hardware holds. What is kept of a PIN is a hash of this, so a
     * copy of Hush's files can't be used to try PINs anywhere else: every guess has to go through this phone.
     */
    fun bind(secret: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(pinKey()) }
        return mac.doFinal(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Where the key called [alias] lives, in words for Settings; null if there is no such key. */
    fun whereIs(alias: String): String? = runCatching {
        val key = (store().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: return null
        val info = SecretKeyFactory.getInstance(key.algorithm, STORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "the phone's secure chip (Knox Vault on Samsung)"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "the phone's secure area (TEE)"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Android's software keystore"
                else -> "the phone's secure hardware"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "the phone's secure hardware" else "Android's software keystore"
        }
    }.getOrNull()

    /** Where the key the disguise PIN is tied to lives (made on first use). */
    fun whereIsPinKey(): String? = runCatching { pinKey() }.getOrNull()?.let { whereIs(PIN_KEY) }
}
