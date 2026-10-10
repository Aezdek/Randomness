package xyz.aezdek.aezprint.data.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PrinterDto(
    val name: String,
    val state: String,
    val stateReasons: List<String> = emptyList(),
    val accepting: Boolean = true,
    val media: List<JsonElement> = emptyList(),
    val capabilities: CapabilitiesDto = CapabilitiesDto(),
)

@Serializable
data class CapabilitiesDto(
    val color: Boolean = false,
    val duplex: JsonElement? = null,
    val resolutions: List<JsonElement> = emptyList(),
)

@Serializable
data class DocumentDto(
    val documentId: String,
    val pageCount: Int,
    val pages: List<PageDto> = emptyList(),
    val expiresAt: JsonElement? = null,
)

@Serializable
data class PageDto(val widthPt: Double, val heightPt: Double)

@Serializable
data class RenderDto(val renderId: String, val sheetCount: Int, val sides: Int = 1)

@Serializable
data class SubmitJobDto(val documentId: String, val options: OptionsDto)

@Serializable
data class JobCreatedDto(val jobId: String)

@Serializable
data class JobDto(
    val jobId: String? = null,
    val id: String? = null,
    val state: String,
    val sheetsCompleted: Int = 0,
    val sheetsTotal: Int = 0,
    val message: String? = null,
)

@Serializable
data class ErrorDto(
    val error: String = "error",
    val message: String = "",
    val fields: List<String> = emptyList(),
)

@Serializable
data class OptionsDto(
    val copies: Int,
    val collate: Boolean,
    val pageRange: String? = null,
    val subset: String,
    val reverse: Boolean,
    val orientation: String,
    val media: String? = null,
    val sizing: SizingDto,
    val multiple: MultipleDto,
    val booklet: BookletDto,
    val poster: PosterDto,
    val duplex: String,
    val content: String,
    val printAsImage: Boolean,
    val dpi: Int,
    val tonerSave: Boolean,
)

@Serializable
data class SizingDto(val mode: String, val scalePercent: Int, val paperByPdfSize: Boolean)

@Serializable
data class MultipleDto(val columns: Int, val rows: Int, val order: String, val border: Boolean)

@Serializable
data class BookletDto(val subset: String, val binding: String)

@Serializable
data class PosterDto(val tileScalePercent: Int, val overlapMm: Int, val cutMarks: Boolean, val labels: Boolean)
