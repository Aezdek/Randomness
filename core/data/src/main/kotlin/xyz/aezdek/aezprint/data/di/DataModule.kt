package xyz.aezdek.aezprint.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import xyz.aezdek.aezprint.data.documents.DocumentRepositoryImpl
import xyz.aezdek.aezprint.data.jobs.WorkManagerPrintQueue
import xyz.aezdek.aezprint.data.security.EncryptedCredentialsRepository
import xyz.aezdek.aezprint.data.server.SwitchingPrintServer
import xyz.aezdek.aezprint.data.store.PresetRepositoryImpl
import xyz.aezdek.aezprint.data.store.PrintRecordRepositoryImpl
import xyz.aezdek.aezprint.data.store.SettingsRepositoryImpl
import xyz.aezdek.aezprint.data.store.StoredDocuments
import xyz.aezdek.aezprint.data.store.StoredPresets
import xyz.aezdek.aezprint.data.store.StoredRecords
import xyz.aezdek.aezprint.data.store.StoredSecret
import xyz.aezdek.aezprint.data.store.StoredSettings
import xyz.aezdek.aezprint.data.store.jsonDataStore
import xyz.aezdek.aezprint.data.system.NetworkMonitor
import xyz.aezdek.aezprint.domain.repository.CredentialsRepository
import xyz.aezdek.aezprint.domain.repository.NetworkStatus
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import xyz.aezdek.aezprint.domain.repository.PresetRepository
import xyz.aezdek.aezprint.domain.repository.PrintQueue
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds abstract fun printServer(impl: SwitchingPrintServer): PrintServer
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun credentials(impl: EncryptedCredentialsRepository): CredentialsRepository
    @Binds abstract fun presets(impl: PresetRepositoryImpl): PresetRepository
    @Binds abstract fun documents(impl: DocumentRepositoryImpl): DocumentRepository
    @Binds abstract fun records(impl: PrintRecordRepositoryImpl): PrintRecordRepository
    @Binds abstract fun queue(impl: WorkManagerPrintQueue): PrintQueue
    @Binds abstract fun network(impl: NetworkMonitor): NetworkStatus
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun storeScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context, json: Json): DataStore<StoredSettings> =
        context.jsonDataStore("settings", StoredSettings(), StoredSettings.serializer(), json, storeScope())

    @Provides
    @Singleton
    fun presetsStore(@ApplicationContext context: Context, json: Json): DataStore<StoredPresets> =
        context.jsonDataStore("presets", StoredPresets(), StoredPresets.serializer(), json, storeScope())

    @Provides
    @Singleton
    fun documentsStore(@ApplicationContext context: Context, json: Json): DataStore<StoredDocuments> =
        context.jsonDataStore("documents", StoredDocuments(), StoredDocuments.serializer(), json, storeScope())

    @Provides
    @Singleton
    fun recordsStore(@ApplicationContext context: Context, json: Json): DataStore<StoredRecords> =
        context.jsonDataStore("records", StoredRecords(), StoredRecords.serializer(), json, storeScope())

    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context, json: Json): DataStore<StoredSecret> =
        context.jsonDataStore("secret", StoredSecret(), StoredSecret.serializer(), json, storeScope())

    @Provides
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
