package xyz.aezdek.salah

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aezdek.salah.core.Backup
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.data.SalahStore

class MainViewModel(private val store: SalahStore) : ViewModel() {

    /** Null until the stored data has been read. */
    val data: StateFlow<SalahData?> = store.data.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun edit(transform: (SalahData) -> SalahData) {
        viewModelScope.launch { store.update(transform) }
    }

    suspend fun backupText(exportedAt: String): String = Backup.encode(store.data.first(), exportedAt)

    suspend fun restore(data: SalahData) = store.update { data }

    companion object {
        val Factory = viewModelFactory {
            initializer { MainViewModel((this[APPLICATION_KEY] as SalahApplication).store) }
        }
    }
}
