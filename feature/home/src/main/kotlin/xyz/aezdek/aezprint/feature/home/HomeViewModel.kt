package xyz.aezdek.aezprint.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.repository.CredentialsRepository
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import xyz.aezdek.aezprint.domain.repository.NetworkStatus
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.PrintServerException
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import javax.inject.Inject

sealed interface PrinterCardState {
    data object Loading : PrinterCardState
    data class Ready(val printer: Printer) : PrinterCardState
    data class Unreachable(val message: String, val needsSetup: Boolean) : PrinterCardState
}

data class HomeUiState(
    val printer: PrinterCardState = PrinterCardState.Loading,
    val recents: List<LocalDocument> = emptyList(),
    val activeJobs: List<PrintRecord> = emptyList(),
    val recentJobs: List<PrintRecord> = emptyList(),
    val online: Boolean = true,
    val usingFakeServer: Boolean = false,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val server: PrintServer,
    private val documents: DocumentRepository,
    records: PrintRecordRepository,
    network: NetworkStatus,
    settings: SettingsRepository,
    credentials: CredentialsRepository,
) : ViewModel() {

    private val refresh = MutableStateFlow(0)

    /** Re-polls every few seconds while the screen is visible, and immediately when settings change. */
    private val printer: Flow<PrinterCardState> = combine(refresh, settings.settings, credentials.credentials) { _, s, c -> s to c }
        .flatMapLatest { (appSettings, creds) ->
            flow {
                emit(PrinterCardState.Loading)
                while (true) {
                    emit(
                        try {
                            PrinterCardState.Ready(server.printer())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: PrintServerException) {
                            PrinterCardState.Unreachable(
                                e.message ?: "Can't reach the printer",
                                needsSetup = !appSettings.useFakeServer && (creds == null || e is PrintServerException.NotConfigured || e is PrintServerException.Unauthorized),
                            )
                        },
                    )
                    delay(POLL_MS)
                }
            }
        }

    val state: StateFlow<HomeUiState> = combine(
        printer,
        documents.recents,
        records.records,
        network.isOnline,
        settings.settings,
    ) { printer, recents, records, online, appSettings ->
        HomeUiState(
            printer = printer,
            recents = recents,
            activeJobs = records.filter { it.isActive },
            recentJobs = records.filterNot { it.isActive }.take(3),
            online = online,
            usingFakeServer = appSettings.useFakeServer,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun retryPrinter() {
        refresh.value++
    }

    fun removeRecent(id: String) {
        viewModelScope.launch { documents.remove(id) }
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
