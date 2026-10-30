package xyz.aezdek.hush.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import xyz.aezdek.hush.client.SecretStore
import xyz.aezdek.hush.client.StoredSession
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The session token and our private keys, sealed with AES-GCM under a key that never leaves the Android Keystore
 * (hardware-backed where the phone has it). Copying the app's files to another device yields nothing usable.
 */
class KeystoreSecrets(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("hush.secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun load(): StoredSession? {
        val sealed = prefs.getString(SLOT, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            StoredSession.decode(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
        }.getOrNull()
    }

    override fun save(session: StoredSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(session.encode().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(SLOT, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(SLOT).apply()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hush.session"
        const val SLOT = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
