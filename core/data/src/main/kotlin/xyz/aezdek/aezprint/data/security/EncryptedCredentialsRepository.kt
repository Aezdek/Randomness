package xyz.aezdek.aezprint.data.security

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.aezdek.aezprint.data.store.StoredSecret
import xyz.aezdek.aezprint.domain.model.ServerCredentials
import xyz.aezdek.aezprint.domain.repository.CredentialsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedCredentialsRepository @Inject constructor(
    private val store: DataStore<StoredSecret>,
    private val cipher: KeystoreCipher,
    private val json: Json,
) : CredentialsRepository {

    @Volatile
    private var cache: Pair<String, ServerCredentials?>? = null

    override val credentials: Flow<ServerCredentials?> =
        store.data.map { decode(it.ciphertext) }.distinctUntilChanged()

    override suspend fun current(): ServerCredentials? = credentials.first()

    override suspend fun save(credentials: ServerCredentials) {
        val plaintext = json.encodeToString(Plain.serializer(), Plain(credentials.clientId.trim(), credentials.clientSecret.trim(), credentials.apiKey.trim()))
        val sealed = cipher.encrypt(plaintext)
        store.updateData { StoredSecret(sealed) }
    }

    override suspend fun clear() {
        store.updateData { StoredSecret(null) }
        cache = null
        cipher.deleteKey()
    }

    private fun decode(ciphertext: String?): ServerCredentials? {
        if (ciphertext == null) return null
        cache?.let { (key, value) -> if (key == ciphertext) return value }
        // A wiped or rotated Keystore key leaves unreadable ciphertext; treat it as "not configured".
        val value = runCatching {
            val plain = json.decodeFromString(Plain.serializer(), cipher.decrypt(ciphertext))
            ServerCredentials(plain.clientId, plain.clientSecret, plain.apiKey)
        }.getOrNull()
        cache = ciphertext to value
        return value
    }

    @Serializable
    private data class Plain(val clientId: String, val clientSecret: String, val apiKey: String)
}
