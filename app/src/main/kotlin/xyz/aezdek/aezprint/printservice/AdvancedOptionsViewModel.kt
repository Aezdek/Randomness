package xyz.aezdek.aezprint.printservice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PresetLibrary
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.repository.PresetRepository
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.validation.OptionsValidator
import xyz.aezdek.aezprint.feature.print.options.OptionEdits
import javax.inject.Inject

data class AdvancedOptionsState(
    val options: PrintOptions = PrintOptions(),
    val printer: Printer? = null,
    val presets: PresetLibrary = PresetLibrary(),
    val activePresetId: String? = null,
    val pageCount: Int = 1,
    val ready: Boolean = false,
) {
    val errors get() = OptionsValidator.validate(options, pageCount, printer).associate { it.field to it.message }
}

@HiltViewModel
class AdvancedOptionsViewModel @Inject constructor(
    private val presets: PresetRepository,
    private val server: PrintServer,
    private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow(AdvancedOptionsState())
    val state: StateFlow<AdvancedOptionsState> = _state.asStateFlow()
    private var initialized = false

    fun initialize(encoded: String?, pageCount: Int) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val library = presets.library.first()
            val existing = encoded?.let { runCatching { json.decodeFromString(PrintOptions.serializer(), it) }.getOrNull() }
            _state.update {
                it.copy(
                    options = existing ?: library.default?.options?.copy(pages = PageSelection.All) ?: PrintOptions(),
                    presets = library,
                    activePresetId = if (existing == null) library.defaultPresetId else null,
                    pageCount = pageCount.coerceAtLeast(1),
                    ready = true,
                )
            }
            try {
                val printer = server.printer()
                _state.update { it.copy(printer = printer, options = OptionEdits.withPrinterDefaults(it.options, printer, null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Unit
            }
        }
        viewModelScope.launch { presets.library.collect { library -> _state.update { it.copy(presets = library) } } }
    }

    fun update(transform: (PrintOptions) -> PrintOptions) = _state.update { it.copy(options = transform(it.options), activePresetId = null) }

    fun applyPreset(id: String) = _state.update { state ->
        val preset = state.presets.presets.firstOrNull { it.id == id } ?: return@update state
        state.copy(options = preset.options.copy(pages = state.options.pages), activePresetId = id)
    }

    fun savePreset(name: String, makeDefault: Boolean) {
        viewModelScope.launch {
            val preset = presets.save(name, _state.value.options.copy(pages = PageSelection.All))
            if (makeDefault) presets.setDefault(preset.id)
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { presets.delete(id) }
    }

    fun setDefaultPreset(id: String?) {
        viewModelScope.launch { presets.setDefault(id) }
    }

    fun encoded(): String = json.encodeToString(PrintOptions.serializer(), _state.value.options)
}
