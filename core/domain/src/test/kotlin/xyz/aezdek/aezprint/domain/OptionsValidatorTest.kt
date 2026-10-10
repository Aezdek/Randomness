package xyz.aezdek.aezprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.aezprint.domain.model.BookletOptions
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.DuplexSupport
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.MultipleOptions
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PageSubset
import xyz.aezdek.aezprint.domain.model.PosterOptions
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.PrinterCapabilities
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.Sizing
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.domain.validation.OptionField
import xyz.aezdek.aezprint.domain.validation.OptionsValidator

class OptionsValidatorTest {

    private val printer = Printer(
        name = "ML-2165",
        status = PrinterStatus.IDLE,
        stateReasons = emptyList(),
        accepting = true,
        media = listOfNotNull(MediaSize.fromPwg("iso_a4_210x297mm"), MediaSize.fromPwg("na_letter_8.5x11in")),
        capabilities = PrinterCapabilities(color = false, duplex = DuplexSupport.MANUAL, resolutions = listOf(600, 1200)),
    )

    private fun fields(options: PrintOptions, pageCount: Int = 10, printer: Printer? = this.printer) =
        OptionsValidator.validate(options, pageCount, printer).map { it.field }

    @Test
    fun `defaults are valid`() {
        assertEquals(emptyList<String>(), fields(PrintOptions()))
    }

    @Test
    fun `copies must be 1 to 99`() {
        assertEquals(listOf(OptionField.COPIES), fields(PrintOptions(copies = 0)))
        assertEquals(listOf(OptionField.COPIES), fields(PrintOptions(copies = 100)))
        assertEquals(emptyList<String>(), fields(PrintOptions(copies = 99)))
    }

    @Test
    fun `invalid custom range is reported on pageRange`() {
        assertEquals(listOf(OptionField.PAGE_RANGE), fields(PrintOptions(pages = PageSelection.Custom("3-1"))))
        assertEquals(listOf(OptionField.PAGE_RANGE), fields(PrintOptions(pages = PageSelection.Current(11))))
    }

    @Test
    fun `subset that leaves no pages is rejected`() {
        val options = PrintOptions(pages = PageSelection.Custom("2"), subset = PageSubset.ODD)
        assertEquals(listOf(OptionField.SUBSET), fields(options))
    }

    @Test
    fun `custom scale is bounded`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.CUSTOM, scalePercent = 5))
        assertEquals(listOf(OptionField.SCALE), fields(options))
        assertEquals(emptyList<String>(), fields(options.copy(sizing = options.sizing.copy(scalePercent = 250))))
    }

    @Test
    fun `scale is ignored outside custom mode`() {
        assertEquals(emptyList<String>(), fields(PrintOptions(sizing = Sizing(mode = SizingMode.FIT, scalePercent = 5))))
    }

    @Test
    fun `multiple pages per sheet grid limits`() {
        fun multiple(columns: Int, rows: Int) =
            PrintOptions(sizing = Sizing(mode = SizingMode.MULTIPLE), multiple = MultipleOptions(columns, rows))
        assertEquals(listOf(OptionField.MULTIPLE_COLUMNS, OptionField.MULTIPLE_ROWS), fields(multiple(9, 0)))
        assertEquals(listOf(OptionField.MULTIPLE_COLUMNS), fields(multiple(1, 1)))
        assertEquals(emptyList<String>(), fields(multiple(4, 4)))
    }

    @Test
    fun `booklets cannot flip on the long edge`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.BOOKLET), duplex = Duplex.LONG_EDGE)
        assertEquals(listOf(OptionField.DUPLEX), fields(options))
    }

    @Test
    fun `poster limits`() {
        val options = PrintOptions(
            sizing = Sizing(mode = SizingMode.POSTER),
            poster = PosterOptions(tileScalePercent = 5, overlapMm = 80),
        )
        assertEquals(listOf(OptionField.POSTER_SCALE, OptionField.POSTER_OVERLAP), fields(options))
    }

    @Test
    fun `media must be offered by the printer unless sized by the pdf`() {
        assertEquals(listOf(OptionField.MEDIA), fields(PrintOptions(media = "iso_a3_297x420mm")))
        assertEquals(
            emptyList<String>(),
            fields(PrintOptions(media = "iso_a3_297x420mm", sizing = Sizing(paperByPdfSize = true))),
        )
    }

    @Test
    fun `two sided needs duplex support`() {
        val simplex = printer.copy(capabilities = printer.capabilities.copy(duplex = DuplexSupport.NONE))
        assertEquals(listOf(OptionField.DUPLEX), fields(PrintOptions(duplex = Duplex.LONG_EDGE), printer = simplex))
        val booklet = PrintOptions(sizing = Sizing(mode = SizingMode.BOOKLET), booklet = BookletOptions(BookletSubset.BOTH))
        assertEquals(listOf(OptionField.BOOKLET_SUBSET), fields(booklet, printer = simplex))
        assertTrue(fields(booklet.copy(booklet = BookletOptions(BookletSubset.FRONT_ONLY)), printer = simplex).isEmpty())
    }

    @Test
    fun `print as image dpi must be a printer resolution`() {
        assertEquals(listOf(OptionField.DPI), fields(PrintOptions(printAsImage = true, dpi = 300)))
        assertEquals(emptyList<String>(), fields(PrintOptions(printAsImage = true, dpi = 1200)))
        assertEquals(emptyList<String>(), fields(PrintOptions(printAsImage = false, dpi = 300)))
    }

    @Test
    fun `printer checks are skipped while the printer is unknown`() {
        assertEquals(emptyList<String>(), fields(PrintOptions(media = "made_up"), printer = null))
    }
}
