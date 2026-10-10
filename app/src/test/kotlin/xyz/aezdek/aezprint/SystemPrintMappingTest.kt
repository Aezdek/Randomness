package xyz.aezdek.aezprint

import android.app.Application
import android.print.PageRange
import android.print.PrintAttributes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Sizing
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.printservice.SystemPrintMapping

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SystemPrintMappingTest {

    private fun attributes(duplex: Int) = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setDuplexMode(duplex)
        .build()

    @Test
    fun systemDialogChoicesOverrideAdvancedOptions() {
        val merged = SystemPrintMapping.merge(
            PrintOptions(copies = 1),
            attributes(PrintAttributes.DUPLEX_MODE_LONG_EDGE),
            copies = 3,
            pages = arrayOf(PageRange(0, 2), PageRange(6, 6)),
        )
        assertEquals(3, merged.copies)
        assertEquals(PageSelection.Custom("1-3,7"), merged.pages)
        assertEquals(Duplex.LONG_EDGE, merged.duplex)
    }

    @Test
    fun bookletsKeepTheirOwnDuplex() {
        val booklet = PrintOptions(sizing = Sizing(mode = SizingMode.BOOKLET), duplex = Duplex.SHORT_EDGE)
        val merged = SystemPrintMapping.merge(booklet, attributes(PrintAttributes.DUPLEX_MODE_NONE), 1, arrayOf(PageRange.ALL_PAGES))
        assertEquals(Duplex.SHORT_EDGE, merged.duplex)
        assertEquals(PageSelection.All, merged.pages)
    }
}
