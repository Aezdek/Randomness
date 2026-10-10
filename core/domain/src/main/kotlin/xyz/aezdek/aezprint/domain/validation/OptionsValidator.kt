package xyz.aezdek.aezprint.domain.validation

import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.DuplexSupport
import xyz.aezdek.aezprint.domain.model.MultipleOptions
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.domain.pages.PageRange
import xyz.aezdek.aezprint.domain.pages.PageSelector

/** Field names match the API's options object so server 422 `fields[]` land on the same controls. */
object OptionField {
    const val COPIES = "copies"
    const val COLLATE = "collate"
    const val PAGE_RANGE = "pageRange"
    const val SUBSET = "subset"
    const val REVERSE = "reverse"
    const val ORIENTATION = "orientation"
    const val MEDIA = "media"
    const val SIZING_MODE = "sizing.mode"
    const val SCALE = "sizing.scalePercent"
    const val PAPER_BY_PDF_SIZE = "sizing.paperByPdfSize"
    const val MULTIPLE_COLUMNS = "multiple.columns"
    const val MULTIPLE_ROWS = "multiple.rows"
    const val MULTIPLE_ORDER = "multiple.order"
    const val MULTIPLE_BORDER = "multiple.border"
    const val BOOKLET_SUBSET = "booklet.subset"
    const val BOOKLET_BINDING = "booklet.binding"
    const val POSTER_SCALE = "poster.tileScalePercent"
    const val POSTER_OVERLAP = "poster.overlapMm"
    const val POSTER_CUT_MARKS = "poster.cutMarks"
    const val POSTER_LABELS = "poster.labels"
    const val DUPLEX = "duplex"
    const val CONTENT = "content"
    const val PRINT_AS_IMAGE = "printAsImage"
    const val DPI = "dpi"
    const val TONER_SAVE = "tonerSave"
}

data class FieldError(val field: String, val message: String)

object OptionsValidator {

    val SCALE_RANGE = 10..400
    val POSTER_SCALE_RANGE = 10..1000
    val OVERLAP_RANGE_MM = 0..50
    val GRID_RANGE = 1..MultipleOptions.MAX_GRID

    fun validate(options: PrintOptions, pageCount: Int, printer: Printer?): List<FieldError> = buildList {
        if (options.copies !in PrintOptions.MIN_COPIES..PrintOptions.MAX_COPIES) {
            add(FieldError(OptionField.COPIES, "Copies must be between ${PrintOptions.MIN_COPIES} and ${PrintOptions.MAX_COPIES}"))
        }

        when (val pages = options.pages) {
            PageSelection.All -> Unit
            is PageSelection.Current -> if (pages.page !in 1..pageCount) {
                add(FieldError(OptionField.PAGE_RANGE, "Page ${pages.page} is outside this document"))
            }
            is PageSelection.Custom -> {
                val result = PageRange.parse(pages.expression, pageCount)
                if (result is PageRange.Result.Invalid) add(FieldError(OptionField.PAGE_RANGE, result.error.message))
            }
        }
        if (none { it.field == OptionField.PAGE_RANGE } && PageSelector.select(options, pageCount).isEmpty()) {
            add(FieldError(OptionField.SUBSET, "No pages left to print with this odd/even filter"))
        }

        when (options.sizing.mode) {
            SizingMode.CUSTOM -> if (options.sizing.scalePercent !in SCALE_RANGE) {
                add(FieldError(OptionField.SCALE, "Scale must be ${SCALE_RANGE.first}–${SCALE_RANGE.last}%"))
            }
            SizingMode.MULTIPLE -> {
                if (options.multiple.columns !in GRID_RANGE) {
                    add(FieldError(OptionField.MULTIPLE_COLUMNS, "Use 1–${GRID_RANGE.last} columns"))
                }
                if (options.multiple.rows !in GRID_RANGE) {
                    add(FieldError(OptionField.MULTIPLE_ROWS, "Use 1–${GRID_RANGE.last} rows"))
                }
                if (options.multiple.pagesPerSheet < 2 && none { it.field.startsWith("multiple.") }) {
                    add(FieldError(OptionField.MULTIPLE_COLUMNS, "Put at least 2 pages on each sheet"))
                }
            }
            SizingMode.BOOKLET -> if (options.duplex == Duplex.LONG_EDGE) {
                add(FieldError(OptionField.DUPLEX, "Booklets flip on the short edge"))
            }
            SizingMode.POSTER -> {
                if (options.poster.tileScalePercent !in POSTER_SCALE_RANGE) {
                    add(FieldError(OptionField.POSTER_SCALE, "Tile scale must be ${POSTER_SCALE_RANGE.first}–${POSTER_SCALE_RANGE.last}%"))
                }
                if (options.poster.overlapMm !in OVERLAP_RANGE_MM) {
                    add(FieldError(OptionField.POSTER_OVERLAP, "Overlap must be ${OVERLAP_RANGE_MM.first}–${OVERLAP_RANGE_MM.last} mm"))
                }
            }
            SizingMode.FIT, SizingMode.ACTUAL, SizingMode.SHRINK -> Unit
        }

        if (printer != null) {
            val media = options.media
            if (media != null && !options.sizing.paperByPdfSize && printer.media.none { it.id == media }) {
                add(FieldError(OptionField.MEDIA, "The printer doesn't have this paper size"))
            }
            if (options.isTwoSided && printer.capabilities.duplex == DuplexSupport.NONE) {
                val field = if (options.sizing.mode == SizingMode.BOOKLET && options.booklet.subset == BookletSubset.BOTH) {
                    OptionField.BOOKLET_SUBSET
                } else {
                    OptionField.DUPLEX
                }
                add(FieldError(field, "This printer can't print on both sides"))
            }
            val resolutions = printer.capabilities.resolutions
            if (options.printAsImage && resolutions.isNotEmpty() && options.dpi !in resolutions) {
                add(FieldError(OptionField.DPI, "Choose ${resolutions.joinToString(" or ")} dpi"))
            }
        }
    }
}
