package xyz.aezdek.aezprint.feature.jobs.progress

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.repository.NetworkStatus
import xyz.aezdek.aezprint.domain.repository.PrintQueue
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import javax.inject.Inject

data class JobProgressUiState(
    val loading: Boolean = true,
    val record: PrintRecord? = null,
    val busy: Boolean = false,
    val online: Boolean = true,
)

sealed interface JobEvent {
    data class OpenJob(val localId: String) : JobEvent
    data class Message(val text: String) : JobEvent
}

@HiltViewModel
class JobProgressViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    records: PrintRecordRepository,
    network: NetworkStatus,
    private val queue: PrintQueue,
) : ViewModel() {

    private val localId: String = checkNotNull(savedStateHandle[ARG_LOCAL_ID])
    private val busy = MutableStateFlow(false)
    private val _events = Channel<JobEvent>(Channel.BUFFERED)
    val events: Flow<JobEvent> = _events.receiveAsFlow()

    val state: StateFlow<JobProgressUiState> =
        combine(records.record(localId), busy, network.isOnline) { record, busy, online ->
            JobProgressUiState(loading = false, record = record, busy = busy, online = online)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobProgressUiState())

    fun cancel() = act("Couldn't cancel") { queue.cancel(localId) }

    fun continueAfterFlip() = act("Couldn't resume the job") { queue.continueAfterFlip(localId) }

    fun reprint() = act("Couldn't reprint") { _events.send(JobEvent.OpenJob(queue.reprint(localId))) }

    private fun act(failure: String, block: suspend () -> Unit) {
        if (busy.value) return
        busy.update { true }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(JobEvent.Message(e.message ?: failure))
            } finally {
                busy.update { false }
            }
        }
    }

    companion object {
        const val ARG_LOCAL_ID = "localId"
    }
}
