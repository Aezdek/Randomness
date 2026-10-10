package xyz.aezdek.aezprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.aezprint.domain.layout.Imposer
import xyz.aezdek.aezprint.domain.model.Binding
import xyz.aezdek.aezprint.domain.model.BookletOptions
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.MultipleOptions
import xyz.aezdek.aezprint.domain.model.PageOrder
import xyz.aezdek.aezprint.domain.model.PageSize
import xyz.aezdek.aezprint.domain.model.PosterOptions
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Sizing
import xyz.aezdek.aezprint.domain.model.SizingMode

class ImposerTest {

    private val a4 = MediaSize.fromPwg("iso_a4_210x297mm")!!
    private val a4Page = PageSize(a4.widthPt, a4.heightPt)
    private fun doc(pages: Int) = List(pages) { a4Page }
    private fun impose(options: PrintOptions, pages: Int) = Imposer.impose(options, doc(pages), listOf(a4))

    @Test
    fun `one page per face by default`() {
        val result = impose(PrintOptions(), 5)
        assertEquals(5, result.sheetCount)
        assertEquals(listOf(1, 2, 3, 4, 5), result.faces.map { it.placements.single().page })
    }

    @Test
    fun `duplex halves the sheet count`() {
        val result = impose(PrintOptions(duplex = Duplex.LONG_EDGE), 5)
        assertEquals(2, result.sides)
        assertEquals(3, result.sheetCount)
        assertEquals(listOf(false, true, false, true, false), result.faces.map { it.isBack })
    }

    @Test
    fun `four up horizontal fills rows first`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.MULTIPLE), multiple = MultipleOptions(2, 2, PageOrder.HORIZONTAL))
        val face = impose(options, 4).faces.single()
        val (p1, p2, p3) = face.placements
        assertTrue(p2.frame.left > p1.frame.left)
        assertTrue(p3.frame.top > p1.frame.top)
    }

    @Test
    fun `vertical reversed fills columns from the right`() {
        val options = PrintOptions(
            sizing = Sizing(mode = SizingMode.MULTIPLE),
            multiple = MultipleOptions(2, 2, PageOrder.VERTICAL_REVERSED),
        )
        val (p1, p2, p3) = impose(options, 4).faces.single().placements
        assertTrue(p2.frame.top > p1.frame.top)
        assertTrue(p3.frame.left < p1.frame.left)
    }

    @Test
    fun `two up chooses landscape paper automatically`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.MULTIPLE), multiple = MultipleOptions(2, 1))
        val result = impose(options, 3)
        assertEquals(2, result.sheetCount)
        assertTrue(result.faces.first().isLandscape)
    }

    @Test
    fun `borders follow the option`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.MULTIPLE), multiple = MultipleOptions(2, 2, border = true))
        assertEquals(3, impose(options, 3).faces.single().borders.size)
    }

    @Test
    fun `booklet imposes saddle stitch order`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.BOOKLET))
        val result = impose(options, 8)
        assertEquals(2, result.sheetCount)
        val spreads = result.faces.map { face -> face.placements.sortedBy { it.frame.left }.map { it.page } }
        assertEquals(listOf(listOf(8, 1), listOf(2, 7), listOf(6, 3), listOf(4, 5)), spreads)
    }

    @Test
    fun `booklet pads with blanks and honours right binding`() {
        val options = PrintOptions(
            sizing = Sizing(mode = SizingMode.BOOKLET),
            booklet = BookletOptions(BookletSubset.BOTH, Binding.RIGHT),
        )
        val result = impose(options, 3)
        val spreads = result.faces.map { face -> face.placements.sortedBy { it.frame.left }.map { it.page } }
        assertEquals(listOf(listOf(1), listOf(3, 2)), spreads)
    }

    @Test
    fun `booklet front only prints single sided`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.BOOKLET), booklet = BookletOptions(BookletSubset.FRONT_ONLY))
        val result = impose(options, 8)
        assertEquals(1, result.sides)
        assertEquals(2, result.faces.size)
        assertTrue(result.faces.none { it.isBack })
    }

    @Test
    fun `poster at 200 percent tiles a page across several sheets`() {
        val options = PrintOptions(
            sizing = Sizing(mode = SizingMode.POSTER),
            poster = PosterOptions(tileScalePercent = 200, overlapMm = 10, labels = true),
        )
        val result = impose(options, 1)
        assertEquals(9, result.sheetCount)
        assertEquals("Page 1 · row 1, column 1", result.faces.first().label)
        assertTrue(result.faces.all { it.cutMarks != null })
    }

    @Test
    fun `tapping a face resolves the page under the finger`() {
        val options = PrintOptions(sizing = Sizing(mode = SizingMode.MULTIPLE), multiple = MultipleOptions(2, 1))
        val face = impose(options, 2).faces.single()
        assertEquals(1, face.pageAt(0.25, 0.5))
        assertEquals(2, face.pageAt(0.75, 0.5))
    }

    @Test
    fun `copies multiply total sheets`() {
        assertEquals(10, impose(PrintOptions(duplex = Duplex.SHORT_EDGE), 9).totalSheets(copies = 2))
    }

    @Test
    fun `pwg names parse to points`() {
        val letter = MediaSize.fromPwg("na_letter_8.5x11in")!!
        assertEquals(612.0, letter.widthPt, 0.01)
        assertEquals(792.0, letter.heightPt, 0.01)
        assertEquals("A4", a4.displayName)
        assertEquals(null, MediaSize.fromPwg("garbage"))
    }
}
