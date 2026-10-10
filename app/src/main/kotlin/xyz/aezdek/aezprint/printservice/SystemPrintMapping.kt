package xyz.aezdek.aezprint.printservice

import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.Orientation
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.SizingMode

/** Translates between the system print framework and AezPrint's options. */
object SystemPrintMapping {

    const val OPTIONS_KEY = "xyz.aezdek.aezprint.OPTIONS"
    private const val MILS_PER_POINT = 1000.0 / 72.0

    private val fallbackMedia = listOfNotNull(MediaSize.fromPwg("iso_a4_210x297mm"), MediaSize.fromPwg("na_letter_8.5x11in"))

    fun printerInfo(id: PrinterId, printer: Printer?, name: String): PrinterInfo {
        val status = when (printer?.status) {
            PrinterStatus.OFFLINE -> PrinterInfo.STATUS_UNAVAILABLE
            PrinterStatus.PRINTING -> PrinterInfo.STATUS_BUSY
            else -> PrinterInfo.STATUS_IDLE // Unknown still accepts jobs: the queue retries until the server answers.
        }
        val media = printer?.media?.takeIf { it.isNotEmpty() } ?: fallbackMedia
        val resolutions = printer?.capabilities?.resolutions?.takeIf { it.isNotEmpty() } ?: listOf(600)
        val capabilities = PrinterCapabilitiesInfo.Builder(id).apply {
            media.forEachIndexed { index, size -> addMediaSize(size.toSystem(), index == 0) }
            resolutions.forEachIndexed { index, dpi ->
                addResolution(PrintAttributes.Resolution("dpi$dpi", "$dpi dpi", dpi, dpi), index == 0)
            }
            setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            setDuplexModes(
                PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                PrintAttributes.DUPLEX_MODE_NONE,
            )
            setMinMargins(PrintAttributes.Margins(170, 170, 170, 170))
        }.build()
        return PrinterInfo.Builder(id, printer?.name ?: name, status)
            .setCapabilities(capabilities)
            .build()
    }

    private fun MediaSize.toSystem(): PrintAttributes.MediaSize =
        PrintAttributes.MediaSize(id, displayName, (widthPt * MILS_PER_POINT).toInt(), (heightPt * MILS_PER_POINT).toInt())

    /**
     * Applies what the user chose in the system dialog on top of AezPrint's own options. Copies, pages
     * and paper always come from the system dialog; two-sided does too unless a booklet decides it.
     */
    fun merge(base: PrintOptions, attributes: PrintAttributes?, copies: Int, pages: Array<PageRange>?): PrintOptions {
        var options = base.copy(copies = copies.coerceIn(PrintOptions.MIN_COPIES, PrintOptions.MAX_COPIES))
        val ranges = pages?.filterNot { it == PageRange.ALL_PAGES }.orEmpty()
        options = if (ranges.isEmpty()) {
            options.copy(pages = if (options.pages is PageSelection.Custom) PageSelection.All else options.pages)
        } else {
            options.copy(pages = PageSelection.Custom(ranges.joinToString(",") { range ->
                if (range.start == range.end) "${range.start + 1}" else "${range.start + 1}-${range.end + 1}"
            }))
        }
        if (attributes == null) return options
        attributes.mediaSize?.let { media ->
            options = options.copy(
                media = media.id,
                orientation = if (options.orientation == Orientation.AUTO) {
                    if (media.isPortrait) Orientation.PORTRAIT else Orientation.LANDSCAPE
                } else {
                    options.orientation
                },
            )
        }
        if (options.sizing.mode != SizingMode.BOOKLET) {
            options = options.copy(
                duplex = when (attributes.duplexMode) {
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE -> Duplex.LONG_EDGE
                    PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> Duplex.SHORT_EDGE
                    else -> options.duplex.takeIf { attributes.duplexMode == 0 } ?: Duplex.OFF
                },
            )
        }
        attributes.resolution?.let { resolution -> options = options.copy(dpi = resolution.horizontalDpi) }
        return options
    }
}
