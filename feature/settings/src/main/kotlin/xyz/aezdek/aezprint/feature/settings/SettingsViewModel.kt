package xyz.aezdek.aezprint.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.domain.model.AppSettings
import xyz.aezdek.aezprint.domain.model.FakeServerConfig
import xyz.aezdek.aezprint.domain.model.ServerCredentials
import xyz.aezdek.aezprint.domain.model.ThemeMode
import xyz.aezdek.aezprint.domain.repository.CredentialsRepository
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import javax.inject.Inject

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Passed(val printerName: String) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val fake: FakeServerConfig = FakeServerConfig(),
    val credentialsConfigured: Boolean = false,
    val clientIdHint: String? = null,
    val connection: ConnectionTest = ConnectionTest.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val credentials: CredentialsRepository,
    private val server: PrintServer,
) : ViewModel() {

    private val connection = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        settings.fakeServerConfig,
        credentials.credentials,
        connection,
    ) { app, fake, creds, test ->
        SettingsUiState(
            settings = app,
            fake = fake,
            credentialsConfigured = creds?.isComplete == true,
            clientIdHint = creds?.clientId?.let { if (it.length > 8) it.take(6) + "…" else it },
            connection = test,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setServerUrl(url: String) = launch {
        try {
            settings.setServerUrl(url)
            _messages.send("Server address saved")
        } catch (e: IllegalArgumentException) {
            _messages.send(e.message ?: "Invalid address")
        }
    }

    fun saveCredentials(clientId: String, clientSecret: String, apiKey: String) = launch {
        credentials.save(ServerCredentials(clientId, clientSecret, apiKey))
        _messages.send("Credentials saved securely")
    }

    fun applySetupCode(raw: String) = launch {
        val code = SetupCode.parse(raw)
        if (code == null) {
            _messages.send("That QR code isn't an AezPrint setup code")
            return@launch
        }
        code.url?.let { settings.setServerUrl(it) }
        credentials.save(ServerCredentials(code.clientId, code.clientSecret, code.apiKey))
        _messages.send("Server set up from QR code")
    }

    fun clearCredentials() = launch {
        credentials.clear()
        _messages.send("Credentials removed")
    }

    fun testConnection() = launch {
        connection.value = ConnectionTest.Running
        connection.value = try {
            ConnectionTest.Passed(server.printer().name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionTest.Failed(e.message ?: "Connection failed")
        }
    }

    fun setUseFakeServer(enabled: Boolean) = launch {
        settings.setUseFakeServer(enabled)
        connection.value = ConnectionTest.Idle
    }

    fun setBiometricLock(enabled: Boolean) = launch { settings.setBiometricLock(enabled) }
    fun setThemeMode(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = launch { settings.setDynamicColor(enabled) }
    fun updateFake(transform: (FakeServerConfig) -> FakeServerConfig) = launch { settings.updateFakeServer(transform) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
