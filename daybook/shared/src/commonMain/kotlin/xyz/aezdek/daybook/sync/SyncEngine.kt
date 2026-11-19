package xyz.aezdek.daybook.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.aezdek.daybook.core.Limits
import xyz.aezdek.daybook.core.PingResponse
import xyz.aezdek.daybook.core.SyncRequest
import xyz.aezdek.daybook.data.LocalStore

sealed interface SyncStatus {
    /** When the last successful sync finished, in epoch milliseconds. */
    val lastSync: Long?

    data object NotConfigured : SyncStatus {
        override val lastSync: Long? = null
    }

    data class Synced(override val lastSync: Long?) : SyncStatus
    data class Syncing(override val lastSync: Long?) : SyncStatus
    data class Offline(override val lastSync: Long?, val message: String) : SyncStatus
    data class Failed(override val lastSync: Long?, val kind: SyncException.Kind, val message: String) : SyncStatus
}

/**
 * Keeps the local store and the server in step.
 *
 * - A local edit triggers a push shortly afterwards, so a burst of edits goes out in one request.
 * - While the app is in the foreground a long poll waits for other devices' changes and pulls them as
 *   soon as they land, so a tick on the phone shows up on the laptop within a second or two.
 * - Failures back off from 2 s to a minute and keep retrying; nothing is lost, because unsent edits stay
 *   marked in the local store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngine(
    private val store: LocalStore,
    private val api: SyncApi,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val clock: () -> Long,
    private val pushDelayMillis: Long = 600,
) {
    private val _config = MutableStateFlow(configStore.loadSync())
    val config: StateFlow<SyncConfig> = _config.asStateFlow()

    private val _status = MutableStateFlow<SyncStatus>(if (_config.value.isConfigured) SyncStatus.Syncing(null) else SyncStatus.NotConfigured)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val foreground = MutableStateFlow(true)
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var pushJob: Job? = null
    private var started = false

    /**
     * Sets the status from what the store knows: "Synced" only after a real sync, otherwise "Syncing"
     * until the first attempt finishes.
     */
    suspend fun loadStatus() {
        val last = store.meta(LocalStore.META_LAST_SYNC)?.toLongOrNull()
        _status.value = when {
            !_config.value.isConfigured -> SyncStatus.NotConfigured
            last != null && store.pendingCount() == 0 -> SyncStatus.Synced(last)
            else -> SyncStatus.Syncing(last)
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { requests.receiveAsFlow().collect { syncNow() } }
        scope.launch {
            combine(_config, foreground) { config, fg -> config.isConfigured && fg }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) watchServer() }
        }
    }

    /** Ask for a sync soon. Cheap to call often: requests made while one is running collapse into one. */
    fun requestSync() {
        requests.trySend(Unit)
    }

    /** Called after every local edit. */
    fun onLocalChange() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(pushDelayMillis)
            requestSync()
        }
    }

    /** Long polling only runs in the foreground. Coming back to the foreground syncs straight away. */
    fun setForeground(value: Boolean) {
        foreground.value = value
        if (value) requestSync()
    }

    fun updateConfig(new: SyncConfig) {
        val old = _config.value
        configStore.saveSync(new)
        scope.launch {
            if (new.baseUrl != old.baseUrl && new.isConfigured) store.resetForNewServer()
            _config.value = new
            loadStatus()
            if (new.isConfigured) requestSync()
        }
    }

    suspend fun testConnection(config: SyncConfig): Result<PingResponse> = try {
        Result.success(api.ping(config))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Pushes everything pending and pulls everything new. Returns true on success. */
    suspend fun syncNow(): Boolean = mutex.withLock {
        val config = _config.value
        if (!config.isConfigured) {
            _status.value = SyncStatus.NotConfigured
            return false
        }
        val last = _status.value.lastSync
        _status.value = SyncStatus.Syncing(last)
        try {
            var rounds = 0
            while (true) {
                val pendingTasks = store.pendingTasks(Limits.MAX_BATCH)
                val pendingSettings = store.pendingSettings()
                val response = api.sync(
                    config,
                    SyncRequest(since = store.lastRev(), deviceId = deviceId, tasks = pendingTasks, settings = pendingSettings),
                )
                store.applySync(response, pendingTasks, pendingSettings)
                rounds++
                val morePending = pendingTasks.size == Limits.MAX_BATCH
                if ((!response.hasMore && !morePending) || rounds >= MAX_ROUNDS) break
            }
            val now = clock()
            store.setMeta(LocalStore.META_LAST_SYNC, now.toString())
            _status.value = SyncStatus.Synced(now)
            true
        } catch (e: CancellationException) {
            _status.value = SyncStatus.Synced(last)
            throw e
        } catch (e: SyncException) {
            _status.value = when (e.kind) {
                SyncException.Kind.Offline -> SyncStatus.Offline(last, e.message ?: "Offline")
                else -> SyncStatus.Failed(last, e.kind, e.message ?: "Sync failed")
            }
            false
        } catch (e: Exception) {
            _status.value = SyncStatus.Failed(last, SyncException.Kind.Server, e.message ?: "Sync failed")
            false
        }
    }

    private suspend fun watchServer() {
        var backoff = MIN_BACKOFF
        while (true) {
            val config = _config.value
            val healthy = _status.value is SyncStatus.Synced
            if (!healthy || store.pendingCount() > 0) {
                if (!syncNow()) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                    continue
                }
            }
            backoff = MIN_BACKOFF
            try {
                val since = store.lastRev()
                val rev = api.waitForChange(config, since)
                if (rev > since) syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncException) {
                val last = _status.value.lastSync
                _status.value = if (e.kind == SyncException.Kind.Offline) {
                    SyncStatus.Offline(last, e.message ?: "Offline")
                } else {
                    SyncStatus.Failed(last, e.kind, e.message ?: "Sync failed")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
            }
        }
    }

    private companion object {
        const val MAX_ROUNDS = 50
        const val MIN_BACKOFF = 2_000L
        const val MAX_BACKOFF = 60_000L
    }
}
