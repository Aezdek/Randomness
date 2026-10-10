package xyz.aezdek.aezprint.feature.print.options

import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.PageSize
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.SizingMode

/** Option changes that imply other changes, mirroring what Acrobat's dialog does automatically. */
object OptionEdits {

    fun sizingMode(options: PrintOptions, mode: SizingMode): PrintOptions {
        val updated = options.copy(sizing = options.sizing.copy(mode = mode))
        return when {
            mode == SizingMode.BOOKLET && options.booklet.subset == BookletSubset.BOTH -> updated.copy(duplex = Duplex.SHORT_EDGE)
            options.sizing.mode == SizingMode.BOOKLET && mode != SizingMode.BOOKLET -> updated.copy(duplex = Duplex.OFF)
            else -> updated
        }
    }

    fun bookletSubset(options: PrintOptions, subset: BookletSubset): PrintOptions =
        options.copy(
            booklet = options.booklet.copy(subset = subset),
            duplex = if (subset == BookletSubset.BOTH) Duplex.SHORT_EDGE else Duplex.OFF,
        )

    fun gridPreset(options: PrintOptions, columns: Int, rows: Int): PrintOptions =
        options.copy(multiple = options.multiple.copy(columns = columns, rows = rows))

    /** Picks a sensible paper the first time a printer is known: keep the user's choice if it exists. */
    fun withPrinterDefaults(options: PrintOptions, printer: Printer, firstPage: PageSize?): PrintOptions {
        val media = options.media?.takeIf { id -> printer.media.any { it.id == id } }
            ?: firstPage?.let { page -> printer.media.firstOrNull { it.matches(page) } }?.id
            ?: printer.defaultMedia?.id
        val dpi = options.dpi.takeIf { it in printer.capabilities.resolutions }
            ?: printer.capabilities.resolutions.firstOrNull()
            ?: options.dpi
        return options.copy(media = media, dpi = dpi)
    }

    fun mediaLabel(media: MediaSize): String {
        val mm = "%.0f × %.0f mm".format(media.widthMm, media.heightMm)
        return "${media.displayName} · $mm"
    }
}
