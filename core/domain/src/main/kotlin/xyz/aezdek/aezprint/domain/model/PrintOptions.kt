package xyz.aezdek.aezprint.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PrintOptions(
    val copies: Int = 1,
    val collate: Boolean = true,
    val pages: PageSelection = PageSelection.All,
    val subset: PageSubset = PageSubset.ALL,
    val reverse: Boolean = false,
    val orientation: Orientation = Orientation.AUTO,
    val media: String? = null,
    val sizing: Sizing = Sizing(),
    val multiple: MultipleOptions = MultipleOptions(),
    val booklet: BookletOptions = BookletOptions(),
    val poster: PosterOptions = PosterOptions(),
    val duplex: Duplex = Duplex.OFF,
    val content: ContentMode = ContentMode.DOCUMENT_AND_MARKUPS,
    val printAsImage: Boolean = false,
    val dpi: Int = 600,
    val tonerSave: Boolean = false,
) {
    val isTwoSided: Boolean
        get() = duplex != Duplex.OFF ||
            (sizing.mode == SizingMode.BOOKLET && booklet.subset == BookletSubset.BOTH)

    companion object {
        const val MIN_COPIES = 1
        const val MAX_COPIES = 99
    }
}

@Serializable
sealed interface PageSelection {
    @Serializable
    data object All : PageSelection

    @Serializable
    data class Current(val page: Int) : PageSelection

    @Serializable
    data class Custom(val expression: String) : PageSelection
}

@Serializable
enum class PageSubset { ALL, ODD, EVEN }

@Serializable
enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

@Serializable
enum class SizingMode { FIT, ACTUAL, SHRINK, CUSTOM, MULTIPLE, BOOKLET, POSTER }

@Serializable
data class Sizing(
    val mode: SizingMode = SizingMode.FIT,
    val scalePercent: Int = 100,
    val paperByPdfSize: Boolean = false,
)

@Serializable
enum class PageOrder { HORIZONTAL, HORIZONTAL_REVERSED, VERTICAL, VERTICAL_REVERSED }

@Serializable
data class MultipleOptions(
    val columns: Int = 2,
    val rows: Int = 1,
    val order: PageOrder = PageOrder.HORIZONTAL,
    val border: Boolean = false,
) {
    val pagesPerSheet: Int get() = columns * rows

    companion object {
        const val MAX_GRID = 8

        val presets: List<Pair<Int, Int>> = listOf(2 to 1, 2 to 2, 3 to 2, 3 to 3, 4 to 4)
    }
}

@Serializable
enum class BookletSubset { BOTH, FRONT_ONLY, BACK_ONLY }

@Serializable
enum class Binding { LEFT, RIGHT }

@Serializable
data class BookletOptions(
    val subset: BookletSubset = BookletSubset.BOTH,
    val binding: Binding = Binding.LEFT,
)

@Serializable
data class PosterOptions(
    val tileScalePercent: Int = 100,
    val overlapMm: Int = 5,
    val cutMarks: Boolean = true,
    val labels: Boolean = true,
)

@Serializable
enum class Duplex { OFF, LONG_EDGE, SHORT_EDGE }

@Serializable
enum class ContentMode { DOCUMENT, DOCUMENT_AND_MARKUPS, DOCUMENT_AND_STAMPS, FORM_FIELDS_ONLY }
