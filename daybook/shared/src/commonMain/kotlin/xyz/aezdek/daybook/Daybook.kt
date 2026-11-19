package xyz.aezdek.daybook

import androidx.sqlite.SQLiteDriver
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import xyz.aezdek.daybook.data.LocalStore
import xyz.aezdek.daybook.data.TaskRepository
import xyz.aezdek.daybook.sync.Appearance
import xyz.aezdek.daybook.sync.ConfigStore
import xyz.aezdek.daybook.sync.SyncApi
import xyz.aezdek.daybook.sync.SyncEngine
import kotlin.time.Clock

/**
 * The app's composition root: one per process. Each platform opens it with its own SQLite driver,
 * database path, HTTP client and config storage.
 */
class Daybook private constructor(
    val store: LocalStore,
    val deviceId: String,
    http: HttpClient,
    private val configStore: ConfigStore,
    val scope: CoroutineScope,
    clock: () -> Long,
) {
    val sync = SyncEngine(store, SyncApi(http), configStore, scope, deviceId, clock)

    val tasks = TaskRepository(store, deviceId, clock, onLocalChange = sync::onLocalChange)

    private val _appearance = MutableStateFlow(configStore.loadAppearance())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun setAppearance(value: Appearance) {
        configStore.saveAppearance(value)
        _appearance.value = value
    }

    fun start() = sync.start()

    companion object {
        suspend fun open(
            driver: SQLiteDriver,
            databasePath: String,
            dbDispatcher: CoroutineDispatcher,
            http: HttpClient,
            configStore: ConfigStore,
            scope: CoroutineScope = CoroutineScope(SupervisorJob()),
            clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        ): Daybook {
            val store = withContext(dbDispatcher) { LocalStore(driver, databasePath, dbDispatcher) }
            return Daybook(store, store.deviceId(), http, configStore, scope, clock).also { it.sync.loadStatus() }
        }
    }
}
