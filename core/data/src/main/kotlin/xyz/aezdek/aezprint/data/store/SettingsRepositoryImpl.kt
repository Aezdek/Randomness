package xyz.aezdek.aezprint.data.store

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import xyz.aezdek.aezprint.domain.model.AppSettings
import xyz.aezdek.aezprint.domain.model.FakeServerConfig
import xyz.aezdek.aezprint.domain.model.ThemeMode
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: DataStore<StoredSettings>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data.map { it.app }.distinctUntilChanged()
    override val fakeServerConfig: Flow<FakeServerConfig> = store.data.map { it.fake }.distinctUntilChanged()

    private suspend fun app(transform: (AppSettings) -> AppSettings) {
        store.updateData { it.copy(app = transform(it.app)) }
    }

    override suspend fun setServerUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        require(trimmed.startsWith("https://", ignoreCase = true)) { "The server URL must start with https://" }
        app { it.copy(serverUrl = trimmed) }
    }

    override suspend fun setUseFakeServer(enabled: Boolean) = app { it.copy(useFakeServer = enabled) }
    override suspend fun setBiometricLock(enabled: Boolean) = app { it.copy(biometricLock = enabled) }
    override suspend fun setThemeMode(mode: ThemeMode) = app { it.copy(themeMode = mode) }
    override suspend fun setDynamicColor(enabled: Boolean) = app { it.copy(dynamicColor = enabled) }

    override suspend fun updateFakeServer(transform: (FakeServerConfig) -> FakeServerConfig) {
        store.updateData { it.copy(fake = transform(it.fake)) }
    }
}
