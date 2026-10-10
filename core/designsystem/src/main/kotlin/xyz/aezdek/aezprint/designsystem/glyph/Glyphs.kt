package xyz.aezdek.aezprint.designsystem.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.domain.model.Binding
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.Orientation
import xyz.aezdek.aezprint.domain.model.PageOrder
import xyz.aezdek.aezprint.domain.model.SizingMode

/** Small line drawings used instead of bare dropdowns so options can be recognised at a glance. */
private data class GlyphColors(val paper: Color, val ink: Color, val accent: Color, val muted: Color)

@Composable
private fun glyphColors(selected: Boolean) = GlyphColors(
    paper = MaterialTheme.colorScheme.surface,
    ink = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    accent = MaterialTheme.colorScheme.primary,
    muted = MaterialTheme.colorScheme.outlineVariant,
)

@Composable
private fun Glyph(modifier: Modifier, size: Dp, selected: Boolean, draw: DrawScope.(GlyphColors) -> Unit) {
    val colors = glyphColors(selected)
    Canvas(modifier.size(size)) { draw(colors) }
}

private fun DrawScope.sheet(rect: Rect, colors: GlyphColors) {
    drawRoundRect(colors.paper, rect.topLeft, rect.size, CornerRadius(2.dp.toPx()))
    drawRoundRect(colors.ink, rect.topLeft, rect.size, CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
}

private fun DrawScope.paperRect(landscape: Boolean, fill: Float = 0.82f): Rect {
    val ratio = 1.414f
    val maxW = this.size.width * fill
    val maxH = this.size.height * fill
    val (w, h) = if (landscape) {
        val w = minOf(maxW, maxH * ratio); w to w / ratio
    } else {
        val h = minOf(maxH, maxW * ratio); h / ratio to h
    }
    return Rect(Offset((this.size.width - w) / 2, (this.size.height - h) / 2), Size(w, h))
}

private fun DrawScope.textLines(area: Rect, colors: GlyphColors, lines: Int = 4) {
    val gap = area.height / (lines + 1)
    repeat(lines) { i ->
        val y = area.top + gap * (i + 1)
        val end = if (i == lines - 1) area.left + area.width * 0.6f else area.right
        drawLine(colors.muted, Offset(area.left, y), Offset(end, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
fun OrientationGlyph(orientation: Orientation, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) =
    Glyph(modifier, size, selected) { colors ->
        when (orientation) {
            Orientation.PORTRAIT -> paperRect(false).let { sheet(it, colors); textLines(it.deflate(it.width * 0.2f), colors) }
            Orientation.LANDSCAPE -> paperRect(true).let { sheet(it, colors); textLines(it.deflate(it.height * 0.2f), colors, 3) }
            Orientation.AUTO -> {
                val back = paperRect(true, 0.7f).translate(-this.size.width * 0.08f, -this.size.height * 0.08f)
                drawRoundRect(colors.muted, back.topLeft, back.size, CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                val front = paperRect(false, 0.7f).translate(this.size.width * 0.08f, this.size.height * 0.08f)
                sheet(front, colors)
            }
        }
    }

@Composable
fun LayoutGlyph(
    columns: Int,
    rows: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    order: PageOrder = PageOrder.HORIZONTAL,
    border: Boolean = true,
    size: Dp = 48.dp,
) = Glyph(modifier, size, selected) { colors ->
    val paper = paperRect(landscape = columns > rows)
    sheet(paper, colors)
    val inner = paper.deflate(paper.minDimension * 0.08f)
    val cellW = inner.width / columns
    val cellH = inner.height / rows
    val centers = cellCenters(columns, rows, order).map { (c, r) ->
        Offset(inner.left + cellW * (c + 0.5f), inner.top + cellH * (r + 0.5f))
    }
    for (r in 0 until rows) for (c in 0 until columns) {
        val cell = Rect(Offset(inner.left + c * cellW, inner.top + r * cellH), Size(cellW, cellH)).deflate(minOf(cellW, cellH) * 0.12f)
        drawRect(colors.muted.copy(alpha = 0.6f), cell.topLeft, cell.size)
        if (border) drawRect(colors.ink.copy(alpha = 0.5f), cell.topLeft, cell.size, style = Stroke(1.dp.toPx()))
    }
    if (centers.size > 1 && columns * rows <= 16) {
        val path = Path().apply {
            moveTo(centers.first().x, centers.first().y)
            centers.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, colors.accent, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(colors.accent, 2.5.dp.toPx(), centers.first())
    }
}

private fun cellCenters(columns: Int, rows: Int, order: PageOrder): List<Pair<Int, Int>> {
    val ltr = (0 until columns).toList()
    val rtl = ltr.reversed()
    return when (order) {
        PageOrder.HORIZONTAL -> (0 until rows).flatMap { r -> ltr.map { it to r } }
        PageOrder.HORIZONTAL_REVERSED -> (0 until rows).flatMap { r -> rtl.map { it to r } }
        PageOrder.VERTICAL -> ltr.flatMap { c -> (0 until rows).map { c to it } }
        PageOrder.VERTICAL_REVERSED -> rtl.flatMap { c -> (0 until rows).map { c to it } }
    }
}

@Composable
fun SizingGlyph(mode: SizingMode, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) =
    Glyph(modifier, size, selected) { colors ->
        val paper = paperRect(false)
        sheet(paper, colors)
        val content = when (mode) {
            SizingMode.FIT -> paper.deflate(paper.width * 0.1f)
            SizingMode.ACTUAL -> paper.deflate(paper.width * 0.22f)
            SizingMode.SHRINK -> paper.deflate(paper.width * 0.16f)
            SizingMode.CUSTOM -> paper.deflate(paper.width * 0.28f)
            else -> paper.deflate(paper.width * 0.1f)
        }
        drawRect(colors.accent.copy(alpha = 0.18f), content.topLeft, content.size)
        drawRect(colors.accent, content.topLeft, content.size, style = Stroke(1.5.dp.toPx()))
        if (mode == SizingMode.SHRINK || mode == SizingMode.FIT) {
            val arrow = 3.dp.toPx()
            listOf(content.topLeft, content.bottomRight).forEach { corner ->
                drawCircle(colors.accent, arrow / 2, corner)
            }
        }
        if (mode == SizingMode.CUSTOM) {
            drawLine(colors.ink, content.bottomRight, paper.bottomRight.copy(x = paper.right - paper.width * 0.06f, y = paper.bottom - paper.width * 0.06f), 1.5.dp.toPx(), StrokeCap.Round)
        }
    }

@Composable
fun BookletGlyph(binding: Binding, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) =
    Glyph(modifier, size, selected) { colors ->
        val spread = paperRect(true)
        val half = spread.width / 2
        val left = Rect(spread.topLeft, Size(half, spread.height))
        val right = Rect(Offset(spread.left + half, spread.top), Size(half, spread.height))
        sheet(left, colors)
        sheet(right, colors)
        textLines(left.deflate(left.width * 0.2f), colors, 3)
        textLines(right.deflate(right.width * 0.2f), colors, 3)
        val spineX = if (binding == Binding.LEFT) spread.left else spread.right
        drawLine(colors.accent, Offset(spineX, spread.top - 2.dp.toPx()), Offset(spineX, spread.bottom + 2.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    }

@Composable
fun BookletSubsetGlyph(subset: BookletSubset, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) =
    Glyph(modifier, size, selected) { colors ->
        val back = paperRect(true, 0.66f).translate(this.size.width * 0.08f, this.size.height * 0.1f)
        val front = paperRect(true, 0.66f).translate(-this.size.width * 0.08f, -this.size.height * 0.1f)
        val dashed = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        if (subset == BookletSubset.FRONT_ONLY) {
            drawRect(colors.muted, back.topLeft, back.size, style = dashed)
        } else {
            sheet(back, colors)
            drawRect(colors.accent.copy(alpha = if (subset == BookletSubset.BACK_ONLY) 0.3f else 0.12f), back.topLeft, back.size)
        }
        if (subset == BookletSubset.BACK_ONLY) {
            drawRect(colors.paper, front.topLeft, front.size)
            drawRect(colors.muted, front.topLeft, front.size, style = dashed)
        } else {
            sheet(front, colors)
            drawRect(colors.accent.copy(alpha = 0.3f), front.topLeft, front.size)
        }
    }

@Composable
fun DuplexGlyph(duplex: Duplex, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) =
    Glyph(modifier, size, selected) { colors ->
        val paper = paperRect(false, 0.7f)
        sheet(paper, colors)
        textLines(paper.deflate(paper.width * 0.2f), colors, 3)
        if (duplex == Duplex.OFF) return@Glyph
        val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round)
        val path = Path()
        if (duplex == Duplex.LONG_EDGE) {
            val x = paper.right + this.size.width * 0.06f
            path.moveTo(paper.left + paper.width * 0.5f, paper.top - this.size.height * 0.04f)
            path.quadraticTo(x + this.size.width * 0.06f, paper.center.y - paper.height * 0.4f, x, paper.center.y)
            drawLine(colors.accent, Offset(paper.right, paper.top), Offset(paper.right, paper.bottom), 3.dp.toPx(), StrokeCap.Round)
        } else {
            val y = paper.top - this.size.height * 0.04f
            path.moveTo(paper.left - this.size.width * 0.04f, paper.center.y)
            path.quadraticTo(paper.left, y - this.size.height * 0.04f, paper.center.x, y)
            drawLine(colors.accent, Offset(paper.left, paper.top), Offset(paper.right, paper.top), 3.dp.toPx(), StrokeCap.Round)
        }
        drawPath(path, colors.accent, style = stroke)
    }
