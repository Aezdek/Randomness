package xyz.aezdek.aezprint.data.network.dto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.aezdek.aezprint.domain.model.Binding
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.ContentMode
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.DuplexSupport
import xyz.aezdek.aezprint.domain.model.Job
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.Orientation
import xyz.aezdek.aezprint.domain.model.PageOrder
import xyz.aezdek.aezprint.domain.model.PageSize
import xyz.aezdek.aezprint.domain.model.PageSubset
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.PrinterCapabilities
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.RemoteDocument
import xyz.aezdek.aezprint.domain.model.Render
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.domain.pages.PageSelector
import java.time.Instant

fun PrintOptions.toDto() = OptionsDto(
    copies = copies,
    collate = collate,
    pageRange = PageSelector.apiPageRange(pages),
    subset = when (subset) {
        PageSubset.ALL -> "all"
        PageSubset.ODD -> "odd"
        PageSubset.EVEN -> "even"
    },
    reverse = reverse,
    orientation = when (orientation) {
        Orientation.AUTO -> "auto"
        Orientation.PORTRAIT -> "portrait"
        Orientation.LANDSCAPE -> "landscape"
    },
    media = media,
    sizing = SizingDto(
        mode = when (sizing.mode) {
            SizingMode.FIT -> "fit"
            SizingMode.ACTUAL -> "actual"
            SizingMode.SHRINK -> "shrink"
            SizingMode.CUSTOM -> "custom"
            SizingMode.MULTIPLE -> "multiple"
            SizingMode.BOOKLET -> "booklet"
            SizingMode.POSTER -> "poster"
        },
        scalePercent = sizing.scalePercent,
        paperByPdfSize = sizing.paperByPdfSize,
    ),
    multiple = MultipleDto(
        columns = multiple.columns,
        rows = multiple.rows,
        order = when (multiple.order) {
            PageOrder.HORIZONTAL -> "horizontal"
            PageOrder.HORIZONTAL_REVERSED -> "horizontalReversed"
            PageOrder.VERTICAL -> "vertical"
            PageOrder.VERTICAL_REVERSED -> "verticalReversed"
        },
        border = multiple.border,
    ),
    booklet = BookletDto(
        subset = when (booklet.subset) {
            BookletSubset.BOTH -> "both"
            BookletSubset.FRONT_ONLY -> "frontOnly"
            BookletSubset.BACK_ONLY -> "backOnly"
        },
        binding = when (booklet.binding) {
            Binding.LEFT -> "left"
            Binding.RIGHT -> "right"
        },
    ),
    poster = PosterDto(poster.tileScalePercent, poster.overlapMm, poster.cutMarks, poster.labels),
    duplex = when (duplex) {
        Duplex.OFF -> "off"
        Duplex.LONG_EDGE -> "longEdge"
        Duplex.SHORT_EDGE -> "shortEdge"
    },
    content = when (content) {
        ContentMode.DOCUMENT -> "document"
        ContentMode.DOCUMENT_AND_MARKUPS -> "documentAndMarkups"
        ContentMode.DOCUMENT_AND_STAMPS -> "documentAndStamps"
        ContentMode.FORM_FIELDS_ONLY -> "formFieldsOnly"
    },
    printAsImage = printAsImage,
    dpi = dpi,
    tonerSave = tonerSave,
)

fun PrinterDto.toDomain() = Printer(
    name = name,
    status = printerStatus(state, stateReasons),
    stateReasons = stateReasons,
    accepting = accepting,
    media = media.mapNotNull(::mediaFrom),
    capabilities = PrinterCapabilities(
        color = capabilities.color,
        duplex = duplexSupport(capabilities.duplex),
        resolutions = capabilities.resolutions.mapNotNull { element ->
            (element as? JsonPrimitive)?.content?.takeWhile(Char::isDigit)?.toIntOrNull()
        },
    ),
)

/** Accepts both plain IPP states (`idle`, `processing`, `stopped` + reasons) and friendlier names. */
internal fun printerStatus(state: String, reasons: List<String>): PrinterStatus {
    val all = (reasons + state).map { it.lowercase().removeSuffix("-error").removeSuffix("-warning").removeSuffix("-report") }
    return when {
        all.any { it in setOf("media-jam", "jam", "paper_jam", "paper-jam") } -> PrinterStatus.JAM
        all.any { it in setOf("media-empty", "media-needed", "out_of_paper", "out-of-paper") } -> PrinterStatus.OUT_OF_PAPER
        all.any { it in setOf("offline", "offline-report", "shutdown", "connecting-to-device", "timed-out") } -> PrinterStatus.OFFLINE
        state.lowercase() in setOf("processing", "printing") -> PrinterStatus.PRINTING
        state.lowercase() == "idle" -> PrinterStatus.IDLE
        state.lowercase() == "stopped" -> PrinterStatus.ERROR
        else -> PrinterStatus.ERROR
    }
}

internal fun duplexSupport(element: JsonElement?): DuplexSupport {
    val primitive = element as? JsonPrimitive ?: return DuplexSupport.NONE
    primitive.booleanOrNull?.let { return if (it) DuplexSupport.MANUAL else DuplexSupport.NONE }
    return when (primitive.content.lowercase()) {
        "manual" -> DuplexSupport.MANUAL
        "automatic", "auto", "true" -> DuplexSupport.AUTOMATIC
        else -> DuplexSupport.NONE
    }
}

internal fun mediaFrom(element: JsonElement): MediaSize? = when (element) {
    is JsonPrimitive -> MediaSize.fromPwg(element.content)
    is JsonObject -> {
        val id = (element["id"] ?: element["name"])?.jsonPrimitive?.content
        val label = (element["displayName"] ?: element["label"])?.jsonPrimitive?.content
        val wPt = element["widthPt"]?.jsonPrimitive?.doubleOrNull
            ?: element["widthMm"]?.jsonPrimitive?.doubleOrNull?.times(MediaSize.POINTS_PER_MM)
        val hPt = element["heightPt"]?.jsonPrimitive?.doubleOrNull
            ?: element["heightMm"]?.jsonPrimitive?.doubleOrNull?.times(MediaSize.POINTS_PER_MM)
        when {
            id == null -> null
            wPt != null && hPt != null -> MediaSize(id, label ?: MediaSize.fromPwg(id)?.displayName ?: id, wPt, hPt)
            else -> MediaSize.fromPwg(id)?.let { parsed -> label?.let { parsed.copy(displayName = it) } ?: parsed }
        }
    }
    is JsonArray -> null
}

fun DocumentDto.toDomain() = RemoteDocument(
    documentId = documentId,
    pageCount = pageCount,
    pages = pages.map { PageSize(it.widthPt, it.heightPt) },
    expiresAt = epochMillis(expiresAt),
)

internal fun epochMillis(element: JsonElement?): Long {
    val primitive = element as? JsonPrimitive ?: return 0
    primitive.longOrNull?.let { return if (it < 10_000_000_000L) it * 1000 else it }
    return runCatching { Instant.parse(primitive.content).toEpochMilli() }.getOrDefault(0)
}

fun RenderDto.toDomain() = Render(renderId, sheetCount, sides.coerceIn(1, 2))

fun JobDto.toDomain(fallbackId: String) = Job(
    id = jobId ?: id ?: fallbackId,
    state = JobState.fromApi(state) ?: JobState.FAILED,
    sheetsCompleted = sheetsCompleted,
    sheetsTotal = sheetsTotal,
    message = message ?: if (JobState.fromApi(state) == null) "Unknown job state \"$state\"" else null,
)
