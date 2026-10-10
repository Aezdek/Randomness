package xyz.aezdek.aezprint.domain.pages

import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PageSubset
import xyz.aezdek.aezprint.domain.model.PrintOptions

object PageSelector {

    /** The 1-based page numbers that will be printed, in print order. Empty when the selection is invalid. */
    fun select(options: PrintOptions, pageCount: Int): List<Int> {
        val base = when (val pages = options.pages) {
            PageSelection.All -> (1..pageCount).toList()
            is PageSelection.Current -> if (pages.page in 1..pageCount) listOf(pages.page) else emptyList()
            is PageSelection.Custom -> when (val result = PageRange.parse(pages.expression, pageCount)) {
                is PageRange.Result.Valid -> result.pages
                is PageRange.Result.Invalid -> emptyList()
            }
        }
        val subset = when (options.subset) {
            PageSubset.ALL -> base
            PageSubset.ODD -> base.filter { it % 2 == 1 }
            PageSubset.EVEN -> base.filter { it % 2 == 0 }
        }
        return if (options.reverse) subset.reversed() else subset
    }

    /** The value for the API's `pageRange` field; null means every page. */
    fun apiPageRange(selection: PageSelection): String? = when (selection) {
        PageSelection.All -> null
        is PageSelection.Current -> selection.page.toString()
        is PageSelection.Custom -> selection.expression.filterNot(Char::isWhitespace)
    }
}
