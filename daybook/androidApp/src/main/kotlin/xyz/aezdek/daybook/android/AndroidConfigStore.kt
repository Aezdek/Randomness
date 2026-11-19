package xyz.aezdek.daybook.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import xyz.aezdek.daybook.sync.Appearance
import xyz.aezdek.daybook.sync.ConfigStore
import xyz.aezdek.daybook.sync.SyncConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sync settings in private SharedPreferences. The token and the Cloudflare secret are stored only as
 * AES-256-GCM ciphertext under a non-exportable Android Keystore key; backups are off for the app.
 */
class AndroidConfigStore(context: Context) : ConfigStore {
    private val prefs = context.getSharedPreferences("daybook.config", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher()

    override fun loadSync(): SyncConfig = SyncConfig(
        serverUrl = prefs.getString(KEY_URL, "").orEmpty(),
        token = secret(KEY_TOKEN),
        accessClientId = prefs.getString(KEY_ACCESS_ID, "").orEmpty(),
        accessClientSecret = secret(KEY_ACCESS_SECRET),
    )

    override fun saveSync(config: SyncConfig) {
        prefs.edit {
            putString(KEY_URL, config.serverUrl)
            putString(KEY_ACCESS_ID, config.accessClientId)
            putSecret(KEY_TOKEN, config.token)
            putSecret(KEY_ACCESS_SECRET, config.accessClientSecret)
        }
    }

    override fun loadAppearance(): Appearance =
        runCatching { Appearance.valueOf(prefs.getString(KEY_APPEARANCE, null) ?: "System") }.getOrDefault(Appearance.System)

    override fun saveAppearance(appearance: Appearance) = prefs.edit { putString(KEY_APPEARANCE, appearance.name) }

    private fun secret(key: String): String {
        val sealed = prefs.getString(key, null) ?: return ""
        // A key lost with a factory reset or restored backup can't decrypt: treat it as not set.
        return runCatching { cipher.decrypt(sealed) }.getOrDefault("")
    }

    private fun android.content.SharedPreferences.Editor.putSecret(key: String, value: String) {
        if (value.isEmpty()) remove(key) else putString(key, cipher.encrypt(value))
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_ACCESS_ID = "access_client_id"
        const val KEY_ACCESS_SECRET = "access_client_secret"
        const val KEY_APPEARANCE = "appearance"
    }
}

/** AES-256-GCM with a non-exportable key that lives in the Android Keystore. */
private class KeystoreCipher {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).decodeToString()
    }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "daybook.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
