package xyz.aezdek.daybook.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.aezdek.daybook.data.SnapshotStorage
import xyz.aezdek.daybook.sync.Appearance
import xyz.aezdek.daybook.sync.ConfigStore
import xyz.aezdek.daybook.sync.SyncConfig

/** The task store's saved copy, in this browser's local storage. */
class BrowserSnapshotStorage(private val key: String = "daybook.store") : SnapshotStorage {
    override fun load(): String? = storageGet(key)

    override fun save(snapshot: String) {
        if (!storageSet(key, snapshot)) {
            // Storage is full or turned off: the tasks stay in memory and on the server.
            println("Daybook: couldn't save to browser storage")
        }
    }
}

/**
 * Sync settings in local storage. The server address defaults to wherever this page was loaded from, since
 * the sync server serves the web app itself: you only need to enter the token.
 */
class WebConfigStore(private val key: String = "daybook.config") : ConfigStore {

    @Serializable
    private data class Stored(
        val serverUrl: String = "",
        val token: String = "",
        val accessClientId: String = "",
        val accessClientSecret: String = "",
        val appearance: String = Appearance.System.name,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun read(): Stored = storageGet(key)?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() } ?: Stored()

    private fun write(value: Stored) {
        storageSet(key, json.encodeToString(Stored.serializer(), value))
    }

    override fun loadSync(): SyncConfig = read().let {
        SyncConfig(
            serverUrl = it.serverUrl.ifBlank { pageOrigin() },
            token = it.token,
            accessClientId = it.accessClientId,
            accessClientSecret = it.accessClientSecret,
        )
    }

    override fun saveSync(config: SyncConfig) = write(
        read().copy(
            serverUrl = config.serverUrl,
            token = config.token,
            accessClientId = config.accessClientId,
            accessClientSecret = config.accessClientSecret,
        ),
    )

    override fun loadAppearance(): Appearance = runCatching { Appearance.valueOf(read().appearance) }.getOrDefault(Appearance.System)

    override fun saveAppearance(appearance: Appearance) = write(read().copy(appearance = appearance.name))
}
