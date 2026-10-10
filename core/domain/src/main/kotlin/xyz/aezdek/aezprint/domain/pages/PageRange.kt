package xyz.aezdek.aezprint.domain.pages

/**
 * Parses Acrobat-style page ranges such as `1-3,7,10-12`. Open ends are allowed (`5-` means 5 to the
 * last page, `-3` means 1 to 3). Pages are 1-based.
 */
object PageRange {

    sealed interface Result {
        data class Valid(val ranges: List<IntRange>) : Result {
            val pages: List<Int> get() = ranges.flatMap { it.toList() }
            val normalized: String get() = format(ranges)
        }

        data class Invalid(val error: PageRangeError) : Result
    }

    fun parse(input: String, pageCount: Int): Result {
        val text = input.trim()
        if (text.isEmpty()) return Result.Invalid(PageRangeError.Empty)

        val ranges = mutableListOf<IntRange>()
        var offset = 0
        for (rawPart in text.split(',')) {
            val part = rawPart.trim()
            val partStart = offset + rawPart.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            offset += rawPart.length + 1
            if (part.isEmpty()) return Result.Invalid(PageRangeError.Syntax(partStart, rawPart))

            val range = parsePart(part, pageCount) ?: return Result.Invalid(PageRangeError.Syntax(partStart, part))
            if (range.first > range.last) {
                return Result.Invalid(PageRangeError.Descending(part))
            }
            val outside = listOf(range.first, range.last).firstOrNull { it < 1 || it > pageCount }
            if (outside != null) return Result.Invalid(PageRangeError.OutOfBounds(outside, pageCount))
            ranges += range
        }
        return Result.Valid(merge(ranges))
    }

    fun format(ranges: List<IntRange>): String = ranges.joinToString(",") {
        if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}"
    }

    private fun parsePart(part: String, pageCount: Int): IntRange? {
        val dash = part.indexOf('-')
        if (dash < 0) {
            val page = part.toStrictInt() ?: return null
            return page..page
        }
        if (part.indexOf('-', dash + 1) >= 0) return null
        val startText = part.substring(0, dash).trim()
        val endText = part.substring(dash + 1).trim()
        if (startText.isEmpty() && endText.isEmpty()) return null
        val start = if (startText.isEmpty()) 1 else startText.toStrictInt() ?: return null
        val end = if (endText.isEmpty()) pageCount else endText.toStrictInt() ?: return null
        return start..end
    }

    private fun String.toStrictInt(): Int? = if (isNotEmpty() && all(Char::isDigit)) toIntOrNull() else null

    /** Keeps the user's order but folds adjacent or overlapping ranges, so `1-3,2-5` prints pages once. */
    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val result = mutableListOf<IntRange>()
        for (range in ranges) {
            val last = result.lastOrNull()
            if (last != null && range.first in last.first..(last.last + 1)) {
                result[result.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                result += range
            }
        }
        return result
    }
}

sealed interface PageRangeError {
    data object Empty : PageRangeError
    data class Syntax(val position: Int, val token: String) : PageRangeError
    data class Descending(val token: String) : PageRangeError
    data class OutOfBounds(val page: Int, val pageCount: Int) : PageRangeError

    val message: String
        get() = when (this) {
            Empty -> "Enter the pages to print, for example 1-3, 7"
            is Syntax -> "\"${token.trim()}\" isn't a page or range"
            is Descending -> "\"$token\" runs backwards; use Reverse order instead"
            is OutOfBounds -> "Page $page doesn't exist; this document has $pageCount ${if (pageCount == 1) "page" else "pages"}"
        }
}
