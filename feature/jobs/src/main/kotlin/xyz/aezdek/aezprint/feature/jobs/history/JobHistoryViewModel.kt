package xyz.aezdek.aezprint.feature.jobs.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.repository.PrintQueue
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import xyz.aezdek.aezprint.feature.jobs.progress.JobEvent
import javax.inject.Inject

data class JobHistoryUiState(val loading: Boolean = true, val records: List<PrintRecord> = emptyList())

@HiltViewModel
class JobHistoryViewModel @Inject constructor(
    records: PrintRecordRepository,
    private val queue: PrintQueue,
) : ViewModel() {

    val state: StateFlow<JobHistoryUiState> = records.records
        .map { JobHistoryUiState(loading = false, records = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobHistoryUiState())

    private val _events = Channel<JobEvent>(Channel.BUFFERED)
    val events: Flow<JobEvent> = _events.receiveAsFlow()

    fun reprint(localId: String) {
        viewModelScope.launch {
            try {
                _events.send(JobEvent.OpenJob(queue.reprint(localId)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(JobEvent.Message(e.message ?: "Couldn't reprint"))
            }
        }
    }
}
