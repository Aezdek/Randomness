package xyz.aezdek.aezgram.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import xyz.aezdek.aezgram.crypto.AccountKeys
import xyz.aezdek.aezgram.crypto.B64
import xyz.aezdek.aezgram.protocol.Me
import xyz.aezdek.aezgram.protocol.ObjectDto
import xyz.aezdek.aezgram.protocol.ThreadDto
import xyz.aezdek.aezgram.protocol.UserDto
import xyz.aezdek.aezgram.protocol.Wire
import java.util.concurrent.ConcurrentHashMap

/**
 * The minimal storage a platform provides: string values in a few named tables. Android backs it with SQLite; tests
 * use [MemoryTables]. What goes in is what the server sent (ciphertext envelopes, public keys), never plaintext.
 */
interface Tables {
    fun all(table: String): List<String>
    fun get(table: String, key: String): String?
    fun put(table: String, key: String, value: String)
    fun delete(table: String, key: String)
    fun transaction(block: () -> Unit)
    fun clear()
}

class MemoryTables : Tables {
    private val data = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private fun table(name: String) = data.getOrPut(name) { ConcurrentHashMap() }
    override fun all(table: String) = table(table).values.toList()
    override fun get(table: String, key: String) = table(table)[key]
    override fun put(table: String, key: String, value: String) { table(table)[key] = value }
    override fun delete(table: String, key: String) { table(table).remove(key) }
    override fun transaction(block: () -> Unit) = synchronized(this) { block() }
    override fun clear() = data.clear()
}

/** The local cache of the server's state, as JSON in [Tables]. */
class LocalDb(private val tables: Tables) {
    private val json = Wire.json

    fun objects(): List<ObjectDto> = tables.all(OBJECTS).mapNotNull { runCatching { json.decodeFromString(ObjectDto.serializer(), it) }.getOrNull() }
    fun users(): List<UserDto> = tables.all(USERS).mapNotNull { runCatching { json.decodeFromString(UserDto.serializer(), it) }.getOrNull() }
    fun threads(): List<ThreadDto> = tables.all(THREADS).mapNotNull { runCatching { json.decodeFromString(ThreadDto.serializer(), it) }.getOrNull() }
    fun pins(): Map<String, Pin> = tables.get(KV, "pins")?.let { runCatching { json.decodeFromString(PINS, it) }.getOrNull() } ?: emptyMap()

    fun apply(objects: List<ObjectDto>, deletedIds: List<String>, users: List<UserDto>, threads: List<ThreadDto>, removedThreads: List<String>) =
        tables.transaction {
            for (dto in objects) tables.put(OBJECTS, dto.id, json.encodeToString(ObjectDto.serializer(), dto))
            for (id in deletedIds) tables.delete(OBJECTS, id)
            for (user in users) tables.put(USERS, user.id, json.encodeToString(UserDto.serializer(), user))
            for (thread in threads) tables.put(THREADS, thread.id, json.encodeToString(ThreadDto.serializer(), thread))
            for (id in removedThreads) tables.delete(THREADS, id)
        }

    fun putObject(dto: ObjectDto) = tables.put(OBJECTS, dto.id, json.encodeToString(ObjectDto.serializer(), dto))
    fun deleteObject(id: String) = tables.delete(OBJECTS, id)
    fun putUser(user: UserDto) = tables.put(USERS, user.id, json.encodeToString(UserDto.serializer(), user))
    fun setPins(pins: Map<String, Pin>) = tables.put(KV, "pins", json.encodeToString(PINS, pins))

    fun cursor(): Long = tables.get(KV, "cursor")?.toLongOrNull() ?: 0
    fun setCursor(cursor: Long) = tables.put(KV, "cursor", cursor.toString())

    fun seenStories(): Map<String, Long> = tables.get(KV, "seenStories")?.let { runCatching { json.decodeFromString(SEEN, it) }.getOrNull() } ?: emptyMap()
    fun setSeenStories(seen: Map<String, Long>) = tables.put(KV, "seenStories", json.encodeToString(SEEN, seen))

    fun value(key: String): String? = tables.get(KV, "x.$key")
    fun setValue(key: String, value: String) = tables.put(KV, "x.$key", value)

    fun clear() = tables.clear()

    companion object {
        const val OBJECTS = "objects"
        const val USERS = "users"
        const val THREADS = "threads"
        const val KV = "kv"
        val TABLES = listOf(OBJECTS, USERS, THREADS, KV)
        private val PINS = MapSerializer(String.serializer(), Pin.serializer())
        private val SEEN = MapSerializer(String.serializer(), Long.serializer())
    }
}

/** What survives an app restart: the session token, who we are, and our private keys. The platform encrypts it at rest. */
@Serializable
data class StoredSession(val token: String, val me: Me, val signPk: String, val signSk: String, val boxPk: String, val boxSk: String) {
    fun keys() = AccountKeys(B64.decode(signPk), B64.decode(signSk), B64.decode(boxPk), B64.decode(boxSk))

    fun encode(): String = Wire.json.encodeToString(serializer(), this)

    companion object {
        fun of(token: String, me: Me, keys: AccountKeys) =
            StoredSession(token, me, B64.encode(keys.signPk), B64.encode(keys.signSk), B64.encode(keys.boxPk), B64.encode(keys.boxSk))

        fun decode(text: String): StoredSession = Wire.json.decodeFromString(serializer(), text)
    }
}

/** Platform storage for [StoredSession] (Android: AES-GCM under a Keystore key). */
interface SecretStore {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

class MemorySecretStore : SecretStore {
    @Volatile
    private var session: StoredSession? = null
    override fun load() = session
    override fun save(session: StoredSession) { this.session = session }
    override fun clear() { session = null }
}
