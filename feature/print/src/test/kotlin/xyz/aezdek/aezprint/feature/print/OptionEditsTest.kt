package xyz.aezdek.aezprint.feature.print

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.DuplexSupport
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.PageSize
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.PrinterCapabilities
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.feature.print.options.OptionEdits

class OptionEditsTest {

    private val letter = MediaSize.fromPwg("na_letter_8.5x11in")!!
    private val a4 = MediaSize.fromPwg("iso_a4_210x297mm")!!
    private val printer = Printer("p", PrinterStatus.IDLE, emptyList(), true, listOf(a4, letter), PrinterCapabilities(false, DuplexSupport.MANUAL, listOf(600, 1200)))

    @Test
    fun `switching to booklet flips on the short edge and back again`() {
        val booklet = OptionEdits.sizingMode(PrintOptions(), SizingMode.BOOKLET)
        assertEquals(Duplex.SHORT_EDGE, booklet.duplex)
        assertEquals(Duplex.OFF, OptionEdits.sizingMode(booklet, SizingMode.FIT).duplex)
    }

    @Test
    fun `single sided booklet subsets turn duplex off`() {
        val front = OptionEdits.bookletSubset(OptionEdits.sizingMode(PrintOptions(), SizingMode.BOOKLET), BookletSubset.FRONT_ONLY)
        assertEquals(Duplex.OFF, front.duplex)
    }

    @Test
    fun `printer defaults pick paper matching the pdf`() {
        val options = OptionEdits.withPrinterDefaults(PrintOptions(dpi = 300), printer, PageSize(letter.widthPt, letter.heightPt))
        assertEquals(letter.id, options.media)
        assertEquals(600, options.dpi)
    }

    @Test
    fun `an explicit supported paper choice is kept`() {
        val options = OptionEdits.withPrinterDefaults(PrintOptions(media = a4.id), printer, PageSize(letter.widthPt, letter.heightPt))
        assertEquals(a4.id, options.media)
    }
}
