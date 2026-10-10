package xyz.aezdek.aezprint.feature.print

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.aezdek.aezprint.domain.layout.Imposer
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import xyz.aezdek.aezprint.domain.repository.NetworkStatus
import xyz.aezdek.aezprint.domain.repository.PresetRepository
import xyz.aezdek.aezprint.domain.repository.PrintQueue
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.PrintServerException
import xyz.aezdek.aezprint.domain.validation.OptionsValidator
import xyz.aezdek.aezprint.feature.print.options.OptionEdits
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class PrintViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documents: DocumentRepository,
    private val server: PrintServer,
    private val presets: PresetRepository,
    private val queue: PrintQueue,
    network: NetworkStatus,
) : ViewModel() {

    private val documentId: String? = savedStateHandle[ARG_DOCUMENT_ID]
    private val documentUri: String? = savedStateHandle[ARG_URI]

    private val _state = MutableStateFlow(PrintUiState())
    val state: StateFlow<PrintUiState> = _state.asStateFlow()

    private val _events = Channel<PrintEvent>(Channel.BUFFERED)
    val events: Flow<PrintEvent> = _events.receiveAsFlow()

    private val images = LruCache<String, ImageBitmap>(IMAGE_CACHE_SIZE)
    private var presetsApplied = false

    init {
        viewModelScope.launch { network.isOnline.collect { online -> _state.update { it.copy(online = online) } } }
        viewModelScope.launch { loadDocument() }
        viewModelScope.launch { loadPrinter() }
        viewModelScope.launch { observePresets() }
        viewModelScope.launch { renderOnChange() }
    }

    private suspend fun loadDocument() {
        try {
            val document = when {
                documentUri != null -> documents.import(documentUri)
                documentId != null -> documents.get(documentId)?.also { documents.touch(it.id) }
                else -> null
            } ?: throw IllegalStateException("This document is no longer available")
            _state.update { it.copy(document = document, documentError = null).derive() }
            uploadForPreview(document)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(documentError = e.message ?: "Couldn't open this file") }
        }
    }

    private suspend fun uploadForPreview(document: LocalDocument) {
        _state.update { it.copy(upload = UploadState.Uploading(0f)) }
        try {
            val remote = server.upload(document) { progress ->
                _state.update { state -> if (state.upload is UploadState.Uploading) state.copy(upload = UploadState.Uploading(progress)) else state }
            }
            _state.update { it.copy(upload = UploadState.Uploaded(remote.documentId)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrintServerException) {
            _state.update { it.copy(upload = UploadState.Failed(e.message ?: "Upload failed", needsSetup = e is PrintServerException.NotConfigured)) }
        }
    }

    fun retryPreview() {
        val document = _state.value.document ?: return
        viewModelScope.launch { uploadForPreview(document) }
        if (_state.value.printer == null) viewModelScope.launch { loadPrinter() }
    }

    private suspend fun loadPrinter() {
        try {
            val printer = server.printer()
            _state.update { state ->
                val firstPage = state.document?.pages?.firstOrNull()?.toPageSize()
                state.copy(
                    printer = printer,
                    printerError = null,
                    options = OptionEdits.withPrinterDefaults(state.options, printer, firstPage),
                ).derive()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrintServerException) {
            _state.update { it.copy(printerError = e.message) }
        }
    }

    private suspend fun observePresets() {
        presets.library.collect { library ->
            _state.update { state ->
                val default = library.default
                if (!presetsApplied && default != null) {
                    presetsApplied = true
                    state.copy(presets = library, options = fromPreset(default.options, state), activePresetId = default.id).derive()
                } else {
                    presetsApplied = true
                    state.copy(presets = library)
                }
            }
        }
    }

    private fun fromPreset(preset: PrintOptions, state: PrintUiState): PrintOptions {
        val printer = state.printer ?: return preset.copy(pages = PageSelection.All)
        val firstPage = state.document?.pages?.firstOrNull()?.toPageSize()
        return OptionEdits.withPrinterDefaults(preset.copy(pages = PageSelection.All), printer, firstPage)
    }

    private suspend fun renderOnChange() {
        _state
            .map { state ->
                val remote = (state.upload as? UploadState.Uploaded)?.remoteDocumentId
                RenderRequest(remote, state.options, state.localErrors.isEmpty())
            }
            .distinctUntilChanged()
            .debounce(RENDER_DEBOUNCE_MS)
            .flowOn(Dispatchers.Default)
            .collectLatest { request -> render(request) }
    }

    private suspend fun render(request: RenderRequest) {
        val remoteId = request.remoteDocumentId ?: return
        if (!request.valid) return
        _state.update { it.copy(render = RenderState.Rendering(it.shownRender)) }
        try {
            val render = server.render(remoteId, request.options)
            _state.update { it.copy(render = RenderState.Ready(render, request.options), serverErrors = emptyMap()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrintServerException.Unsupported) {
            val message = e.message ?: "Not supported"
            _state.update { state ->
                state.copy(
                    render = state.shownRender ?: RenderState.Failed(message),
                    serverErrors = e.fields.associateWith { message }.ifEmpty { mapOf(GENERAL_ERROR to message) },
                )
            }
        } catch (e: PrintServerException.NotFound) {
            _state.value.document?.let { uploadForPreview(it) }
        } catch (e: PrintServerException) {
            _state.update { it.copy(render = RenderState.Failed(e.message ?: "Preview unavailable")) }
        }
    }

    suspend fun sheetImage(renderId: String, face: Int, widthPx: Int): ImageBitmap? {
        val bucket = ((widthPx + WIDTH_BUCKET - 1) / WIDTH_BUCKET * WIDTH_BUCKET).coerceIn(WIDTH_BUCKET, MAX_WIDTH)
        val key = "$renderId/$face/$bucket"
        images.get(key)?.let { return it }
        return try {
            val bytes = server.sheet(renderId, face, bucket)
            val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            bitmap?.also { images.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrintServerException) {
            null
        }
    }

    fun updateOptions(transform: (PrintOptions) -> PrintOptions) {
        _state.update { state ->
            val updated = transform(state.options)
            if (updated == state.options) return@update state
            state.copy(
                options = updated,
                activePresetId = state.activePresetId?.takeIf { id -> state.presets.presets.firstOrNull { it.id == id }?.options == updated },
                serverErrors = emptyMap(),
            ).derive()
        }
    }

    fun selectFace(index: Int) {
        _state.update { state ->
            val face = index.coerceIn(0, (state.faceCount - 1).coerceAtLeast(0))
            val page = state.imposition.faces.getOrNull(face)?.placements?.firstOrNull()?.page ?: state.currentPage
            state.copy(currentFace = face, currentPage = if (state.options.pages is PageSelection.Current) state.currentPage else page)
        }
    }

    /** Tapping a page in the preview makes it the "current page", like clicking a page in Acrobat. */
    fun tapPreview(faceIndex: Int, fx: Float, fy: Float) {
        val page = _state.value.imposition.faces.getOrNull(faceIndex)?.pageAt(fx.toDouble(), fy.toDouble()) ?: return
        _state.update { it.copy(currentPage = page) }
        if (_state.value.options.pages is PageSelection.Current) {
            updateOptions { it.copy(pages = PageSelection.Current(page)) }
        } else {
            _events.trySend(PrintEvent.Message("Page $page is now the current page"))
        }
    }

    fun setPageSelection(selection: PageSelection) {
        val resolved = if (selection is PageSelection.Current) PageSelection.Current(_state.value.currentPage) else selection
        updateOptions { it.copy(pages = resolved) }
        if (resolved is PageSelection.Current) _state.update { it.copy(currentFace = 0) }
    }

    fun applyPreset(id: String) {
        _state.update { state ->
            val preset = state.presets.presets.firstOrNull { it.id == id } ?: return@update state
            state.copy(options = fromPreset(preset.options, state), activePresetId = id, serverErrors = emptyMap()).derive()
        }
    }

    fun savePreset(name: String, makeDefault: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val preset = presets.save(name, _state.value.options.copy(pages = PageSelection.All))
            if (makeDefault) presets.setDefault(preset.id)
            _state.update { it.copy(activePresetId = preset.id) }
            _events.send(PrintEvent.Message("Saved preset \"${preset.name}\""))
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { presets.delete(id) }
    }

    fun setDefaultPreset(id: String?) {
        viewModelScope.launch { presets.setDefault(id) }
    }

    fun print() {
        val state = _state.value
        val document = state.document ?: return
        if (!state.canPrint) {
            _events.trySend(PrintEvent.Message(state.localErrors.firstOrNull()?.message ?: "Fix the highlighted options first"))
            return
        }
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            try {
                val localId = queue.enqueue(document, state.options)
                _events.send(PrintEvent.OpenJob(localId))
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    private fun PrintUiState.derive(): PrintUiState {
        val document = document ?: return this
        val pages = document.pages.map { it.toPageSize() }
        val media = printer?.media.orEmpty()
        val imposition = Imposer.impose(options, pages, media)
        return copy(
            localErrors = OptionsValidator.validate(options, document.pageCount, printer),
            imposition = imposition,
            currentFace = currentFace.coerceAtMost((imposition.faces.size - 1).coerceAtLeast(0)),
        )
    }

    private data class RenderRequest(val remoteDocumentId: String?, val options: PrintOptions, val valid: Boolean)

    companion object {
        const val ARG_DOCUMENT_ID = "documentId"
        const val ARG_URI = "uri"
        const val GENERAL_ERROR = "_general"
        private const val RENDER_DEBOUNCE_MS = 350L
        private const val IMAGE_CACHE_SIZE = 40
        private const val WIDTH_BUCKET = 256
        private const val MAX_WIDTH = 2048
    }
}
