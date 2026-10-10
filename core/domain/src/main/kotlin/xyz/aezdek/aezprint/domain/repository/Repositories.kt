package xyz.aezdek.aezprint.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.aezdek.aezprint.domain.model.AppSettings
import xyz.aezdek.aezprint.domain.model.FakeServerConfig
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.Preset
import xyz.aezdek.aezprint.domain.model.PresetLibrary
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.model.ServerCredentials
import xyz.aezdek.aezprint.domain.model.ThemeMode

interface SettingsRepository {
    val settings: Flow<AppSettings>
    val fakeServerConfig: Flow<FakeServerConfig>

    suspend fun setServerUrl(url: String)
    suspend fun setUseFakeServer(enabled: Boolean)
    suspend fun setBiometricLock(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun updateFakeServer(transform: (FakeServerConfig) -> FakeServerConfig)
}

interface CredentialsRepository {
    val credentials: Flow<ServerCredentials?>

    suspend fun current(): ServerCredentials?
    suspend fun save(credentials: ServerCredentials)
    suspend fun clear()
}

interface PresetRepository {
    val library: Flow<PresetLibrary>

    suspend fun save(name: String, options: PrintOptions): Preset
    suspend fun delete(id: String)
    suspend fun setDefault(id: String?)
}

interface DocumentRepository {
    val recents: Flow<List<LocalDocument>>

    /** Copies the PDF at [uri] into app storage and records it as recent. */
    suspend fun import(uri: String): LocalDocument
    suspend fun get(id: String): LocalDocument?
    suspend fun touch(id: String)
    suspend fun remove(id: String)
}

interface PrintRecordRepository {
    val records: Flow<List<PrintRecord>>

    fun record(localId: String): Flow<PrintRecord?>
    suspend fun get(localId: String): PrintRecord?
    suspend fun upsert(record: PrintRecord)
    suspend fun update(localId: String, transform: (PrintRecord) -> PrintRecord)
}

/** Durable job pipeline: survives process death and retries on bad connections. */
interface PrintQueue {
    suspend fun enqueue(document: LocalDocument, options: PrintOptions): String
    suspend fun reprint(localId: String): String
    suspend fun cancel(localId: String)
    suspend fun continueAfterFlip(localId: String)
}

interface NetworkStatus {
    val isOnline: Flow<Boolean>
}
