package xyz.aezdek.aezprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.aezprint.domain.pages.PageRange
import xyz.aezdek.aezprint.domain.pages.PageRangeError

class PageRangeTest {

    private fun valid(input: String, pageCount: Int = 20) =
        PageRange.parse(input, pageCount) as PageRange.Result.Valid

    private fun error(input: String, pageCount: Int = 20) =
        (PageRange.parse(input, pageCount) as PageRange.Result.Invalid).error

    @Test
    fun `parses the example from the print dialog`() {
        assertEquals(listOf(1, 2, 3, 7, 10, 11, 12), valid("1-3,7,10-12").pages)
    }

    @Test
    fun `tolerates whitespace`() {
        assertEquals("1-3,7", valid("  1 - 3 , 7 ").normalized)
    }

    @Test
    fun `open ended ranges reach the document bounds`() {
        assertEquals(listOf(18, 19, 20), valid("18-").pages)
        assertEquals(listOf(1, 2, 3), valid("-3").pages)
    }

    @Test
    fun `adjacent and overlapping ranges are merged`() {
        assertEquals("1-5", valid("1-3,2-5").normalized)
        assertEquals("1-4", valid("1-2,3-4").normalized)
    }

    @Test
    fun `user order is preserved when ranges are not contiguous`() {
        assertEquals(listOf(7, 1, 2), valid("7,1-2").pages)
    }

    @Test
    fun `single page document`() {
        assertEquals(listOf(1), valid("1", pageCount = 1).pages)
    }

    @Test
    fun `empty input is rejected`() {
        assertEquals(PageRangeError.Empty, error("   "))
    }

    @Test
    fun `syntax errors report the offending token`() {
        val syntax = error("1-3,abc") as PageRangeError.Syntax
        assertEquals("abc", syntax.token)
        assertEquals(4, syntax.position)
        assertTrue(error("1,,2") is PageRangeError.Syntax)
        assertTrue(error("1-2-3") is PageRangeError.Syntax)
        assertTrue(error("-") is PageRangeError.Syntax)
        assertTrue(error("+3") is PageRangeError.Syntax)
        assertTrue(error("1,") is PageRangeError.Syntax)
    }

    @Test
    fun `descending ranges are rejected`() {
        assertEquals(PageRangeError.Descending("5-2"), error("5-2"))
    }

    @Test
    fun `pages beyond the document are rejected`() {
        assertEquals(PageRangeError.OutOfBounds(21, 20), error("1-21"))
        assertEquals(PageRangeError.OutOfBounds(0, 20), error("0"))
    }

    @Test
    fun `errors have readable messages`() {
        assertEquals("Page 9 doesn't exist; this document has 3 pages", error("9", pageCount = 3).message)
    }
}
