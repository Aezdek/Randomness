package xyz.aezdek.aezprint.domain.layout

import xyz.aezdek.aezprint.domain.model.Binding
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.MediaSize
import xyz.aezdek.aezprint.domain.model.Orientation
import xyz.aezdek.aezprint.domain.model.PageOrder
import xyz.aezdek.aezprint.domain.model.PageSize
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.domain.pages.PageSelector
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Computes the sheet layout for a set of options. The server is the source of truth for what prints;
 * the app uses this to animate layout changes instantly and the fake server uses it to draw previews.
 */
object Imposer {

    const val MARGIN_PT = 12.0
    private const val GUTTER_PT = 6.0

    fun impose(
        options: PrintOptions,
        pages: List<PageSize>,
        availableMedia: List<MediaSize>,
    ): Imposition {
        val selected = PageSelector.select(options, pages.size)
        if (selected.isEmpty()) return Imposition.EMPTY
        val paper = choosePaper(options, pages[selected.first() - 1], availableMedia)

        return when (options.sizing.mode) {
            SizingMode.MULTIPLE -> nUp(options, selected, pages, paper)
            SizingMode.BOOKLET -> booklet(options, selected, pages, paper)
            SizingMode.POSTER -> poster(options, selected, pages, paper)
            else -> single(options, selected, pages, paper)
        }
    }

    private fun choosePaper(options: PrintOptions, firstPage: PageSize, media: List<MediaSize>): PageSize {
        val chosen = when {
            options.sizing.paperByPdfSize -> media.firstOrNull { it.matches(firstPage) } ?: media.firstOrNull { it.id == options.media }
            else -> media.firstOrNull { it.id == options.media }
        } ?: media.firstOrNull()
        val width = chosen?.widthPt ?: firstPage.widthPt
        val height = chosen?.heightPt ?: firstPage.heightPt
        return PageSize(min(width, height), max(width, height))
    }

    private fun oriented(paper: PageSize, landscape: Boolean) =
        if (landscape) PageSize(paper.heightPt, paper.widthPt) else paper

    private fun wantsLandscape(orientation: Orientation, auto: () -> Boolean) = when (orientation) {
        Orientation.PORTRAIT -> false
        Orientation.LANDSCAPE -> true
        Orientation.AUTO -> auto()
    }

    private fun sidesFor(options: PrintOptions) = if (options.duplex == Duplex.OFF) 1 else 2

    private fun withSheets(faces: List<UnnumberedFace>, sides: Int) = Imposition(
        sides = sides,
        faces = faces.mapIndexed { index, face ->
            Face(
                sheetNumber = index / sides + 1,
                isBack = sides == 2 && index % 2 == 1,
                widthPt = face.size.widthPt,
                heightPt = face.size.heightPt,
                placements = face.placements,
                borders = face.borders,
                cutMarks = face.cutMarks,
                label = face.label,
            )
        },
    )

    private fun single(options: PrintOptions, selected: List<Int>, pages: List<PageSize>, paper: PageSize): Imposition {
        val faces = selected.map { pageNumber ->
            val page = pages[pageNumber - 1]
            val face = oriented(paper, wantsLandscape(options.orientation) { page.isLandscape })
            val area = Rect(MARGIN_PT, MARGIN_PT, face.widthPt - MARGIN_PT, face.heightPt - MARGIN_PT)
            val fit = min(area.width / page.widthPt, area.height / page.heightPt)
            val scale = when (options.sizing.mode) {
                SizingMode.ACTUAL -> 1.0
                SizingMode.SHRINK -> min(1.0, fit)
                SizingMode.CUSTOM -> options.sizing.scalePercent / 100.0
                else -> fit
            }
            val frame = centered(face.widthPt / 2, face.heightPt / 2, page.widthPt * scale, page.heightPt * scale)
            val clip = Rect(0.0, 0.0, face.widthPt, face.heightPt)
            UnnumberedFace(face, listOf(Placement(pageNumber, frame, clip)))
        }
        return withSheets(faces, sidesFor(options))
    }

    private fun nUp(options: PrintOptions, selected: List<Int>, pages: List<PageSize>, paper: PageSize): Imposition {
        val multiple = options.multiple
        val sample = pages[selected.first() - 1]
        val landscape = wantsLandscape(options.orientation) {
            gridScale(oriented(paper, true), sample, multiple.columns, multiple.rows) >
                gridScale(paper, sample, multiple.columns, multiple.rows)
        }
        val face = oriented(paper, landscape)
        val cells = gridCells(face, multiple.columns, multiple.rows, multiple.order)
        val faces = selected.chunked(cells.size).map { chunk ->
            val placements = chunk.mapIndexed { i, pageNumber -> fitInto(cells[i], pageNumber, pages[pageNumber - 1]) }
            UnnumberedFace(face, placements, borders = if (multiple.border) cells.take(chunk.size) else emptyList())
        }
        return withSheets(faces, sidesFor(options))
    }

    private fun gridScale(face: PageSize, page: PageSize, columns: Int, rows: Int): Double {
        val cellW = (face.widthPt - 2 * MARGIN_PT) / columns
        val cellH = (face.heightPt - 2 * MARGIN_PT) / rows
        return min(cellW / page.widthPt, cellH / page.heightPt)
    }

    private fun gridCells(face: PageSize, columns: Int, rows: Int, order: PageOrder): List<Rect> {
        val cellW = (face.widthPt - 2 * MARGIN_PT - (columns - 1) * GUTTER_PT) / columns
        val cellH = (face.heightPt - 2 * MARGIN_PT - (rows - 1) * GUTTER_PT) / rows
        fun cell(col: Int, row: Int) = Rect.ofSize(
            MARGIN_PT + col * (cellW + GUTTER_PT),
            MARGIN_PT + row * (cellH + GUTTER_PT),
            cellW,
            cellH,
        )
        val columnsLtr = 0 until columns
        val columnsRtl = (columns - 1) downTo 0
        return when (order) {
            PageOrder.HORIZONTAL -> (0 until rows).flatMap { r -> columnsLtr.map { c -> cell(c, r) } }
            PageOrder.HORIZONTAL_REVERSED -> (0 until rows).flatMap { r -> columnsRtl.map { c -> cell(c, r) } }
            PageOrder.VERTICAL -> columnsLtr.flatMap { c -> (0 until rows).map { r -> cell(c, r) } }
            PageOrder.VERTICAL_REVERSED -> columnsRtl.flatMap { c -> (0 until rows).map { r -> cell(c, r) } }
        }
    }

    private fun booklet(options: PrintOptions, selected: List<Int>, pages: List<PageSize>, paper: PageSize): Imposition {
        val face = oriented(paper, landscape = options.orientation != Orientation.PORTRAIT)
        val horizontal = face.isLandscape
        val halves = if (horizontal) {
            listOf(
                Rect(MARGIN_PT, MARGIN_PT, face.widthPt / 2 - GUTTER_PT, face.heightPt - MARGIN_PT),
                Rect(face.widthPt / 2 + GUTTER_PT, MARGIN_PT, face.widthPt - MARGIN_PT, face.heightPt - MARGIN_PT),
            )
        } else {
            listOf(
                Rect(MARGIN_PT, MARGIN_PT, face.widthPt - MARGIN_PT, face.heightPt / 2 - GUTTER_PT),
                Rect(MARGIN_PT, face.heightPt / 2 + GUTTER_PT, face.widthPt - MARGIN_PT, face.heightPt - MARGIN_PT),
            )
        }
        val padded: List<Int?> = selected + List((4 - selected.size % 4) % 4) { null }
        val n = padded.size
        val faces = mutableListOf<UnnumberedFace>()
        for (sheet in 0 until n / 4) {
            val front = listOf(padded[n - 1 - 2 * sheet], padded[2 * sheet])
            val back = listOf(padded[2 * sheet + 1], padded[n - 2 - 2 * sheet])
            for ((isBack, spread) in listOf(false to front, true to back)) {
                if (options.booklet.subset == BookletSubset.FRONT_ONLY && isBack) continue
                if (options.booklet.subset == BookletSubset.BACK_ONLY && !isBack) continue
                val ordered = if (options.booklet.binding == Binding.RIGHT) spread.reversed() else spread
                val placements = ordered.mapIndexedNotNull { i, pageNumber ->
                    pageNumber?.let { fitInto(halves[i], it, pages[it - 1]) }
                }
                faces += UnnumberedFace(face, placements)
            }
        }
        val sides = if (options.booklet.subset == BookletSubset.BOTH) 2 else 1
        return withSheets(faces, sides)
    }

    private fun poster(options: PrintOptions, selected: List<Int>, pages: List<PageSize>, paper: PageSize): Imposition {
        val scale = options.poster.tileScalePercent / 100.0
        val overlap = options.poster.overlapMm * MediaSize.POINTS_PER_MM
        val faces = selected.flatMap { pageNumber ->
            val page = pages[pageNumber - 1]
            val face = oriented(paper, wantsLandscape(options.orientation) { page.isLandscape })
            val area = Rect(MARGIN_PT, MARGIN_PT, face.widthPt - MARGIN_PT, face.heightPt - MARGIN_PT)
            val stepX = max(area.width - overlap, 1.0)
            val stepY = max(area.height - overlap, 1.0)
            val scaledW = page.widthPt * scale
            val scaledH = page.heightPt * scale
            val tilesX = max(1, ceil((scaledW - overlap) / stepX).toInt())
            val tilesY = max(1, ceil((scaledH - overlap) / stepY).toInt())
            (0 until tilesY).flatMap { row ->
                (0 until tilesX).map { col ->
                    val frame = Rect.ofSize(area.left - col * stepX, area.top - row * stepY, scaledW, scaledH)
                    UnnumberedFace(
                        size = face,
                        placements = listOf(Placement(pageNumber, frame, clip = area)),
                        cutMarks = area.takeIf { options.poster.cutMarks },
                        label = if (options.poster.labels) "Page $pageNumber · row ${row + 1}, column ${col + 1}" else null,
                    )
                }
            }
        }
        return withSheets(faces, sidesFor(options))
    }

    private fun fitInto(cell: Rect, pageNumber: Int, page: PageSize): Placement {
        val scale = min(cell.width / page.widthPt, cell.height / page.heightPt)
        val frame = centered(
            cell.left + cell.width / 2,
            cell.top + cell.height / 2,
            page.widthPt * scale,
            page.heightPt * scale,
        )
        return Placement(pageNumber, frame)
    }

    private fun centered(cx: Double, cy: Double, width: Double, height: Double) =
        Rect(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)

    private data class UnnumberedFace(
        val size: PageSize,
        val placements: List<Placement>,
        val borders: List<Rect> = emptyList(),
        val cutMarks: Rect? = null,
        val label: String? = null,
    )
}
