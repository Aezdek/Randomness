package xyz.aezdek.aezprint.feature.print

import xyz.aezdek.aezprint.domain.layout.Face
import xyz.aezdek.aezprint.domain.layout.Imposition
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PresetLibrary
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.Render
import xyz.aezdek.aezprint.domain.validation.FieldError

sealed interface UploadState {
    data object Idle : UploadState
    data class Uploading(val progress: Float) : UploadState
    data class Uploaded(val remoteDocumentId: String) : UploadState
    data class Failed(val message: String, val needsSetup: Boolean = false) : UploadState
}

sealed interface RenderState {
    data object Idle : RenderState
    data class Rendering(val previous: Ready?) : RenderState
    data class Ready(val render: Render, val options: PrintOptions) : RenderState
    data class Failed(val message: String) : RenderState
}

data class PrintUiState(
    val document: LocalDocument? = null,
    val documentError: String? = null,
    val upload: UploadState = UploadState.Idle,
    val printer: Printer? = null,
    val printerError: String? = null,
    val options: PrintOptions = PrintOptions(),
    val presets: PresetLibrary = PresetLibrary(),
    val activePresetId: String? = null,
    val currentPage: Int = 1,
    val localErrors: List<FieldError> = emptyList(),
    val serverErrors: Map<String, String> = emptyMap(),
    val render: RenderState = RenderState.Idle,
    val imposition: Imposition = Imposition.EMPTY,
    val currentFace: Int = 0,
    val submitting: Boolean = false,
    val online: Boolean = true,
) {
    val errors: Map<String, String> get() = serverErrors + localErrors.associate { it.field to it.message }

    val canPrint: Boolean get() = document != null && localErrors.isEmpty() && !submitting

    /** The render currently on screen, possibly for slightly older options while a new one is in flight. */
    val shownRender: RenderState.Ready?
        get() = when (val r = render) {
            is RenderState.Ready -> r
            is RenderState.Rendering -> r.previous
            else -> null
        }

    val renderIsCurrent: Boolean get() = (render as? RenderState.Ready)?.options == options

    val faceCount: Int
        get() = shownRender?.takeIf { renderIsCurrent }?.render?.faceCount ?: imposition.faces.size

    val sheetCount: Int
        get() = shownRender?.takeIf { renderIsCurrent }?.render?.sheetCount ?: imposition.sheetCount

    val sides: Int
        get() = shownRender?.takeIf { renderIsCurrent }?.render?.sides ?: imposition.sides

    val totalSheets: Int get() = sheetCount * options.copies

    fun faceGeometry(index: Int): Face? = imposition.faces.getOrNull(index) ?: imposition.faces.lastOrNull()
}

sealed interface PrintEvent {
    data class OpenJob(val localId: String) : PrintEvent
    data class Message(val text: String) : PrintEvent
}
