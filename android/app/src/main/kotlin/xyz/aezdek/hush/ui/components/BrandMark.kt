package xyz.aezdek.hush.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

private val VIOLET = Color(0xFF7C5CFF)
private val ORANGE = Color(0xFFFF8A3D)

/**
 * The Hush mark: a lowercase h on a violet tile, with an orange dot. Flat colours, the geometry of
 * web/public/favicon.svg on a 48-unit grid.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 48f
        drawRoundRect(VIOLET, Offset.Zero, Size(48f * u, 48f * u), CornerRadius(14f * u))
        val h = Path().apply {
            moveTo(16.5f * u, 12f * u)
            lineTo(16.5f * u, 36f * u)
            moveTo(16.5f * u, 26f * u)
            arcTo(Rect(16.5f * u, 18.5f * u, 31.5f * u, 33.5f * u), 180f, 180f, false)
            lineTo(31.5f * u, 36f * u)
        }
        drawPath(h, Color.White, style = Stroke(width = 5.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(ORANGE, radius = 3f * u, center = Offset(33f * u, 13.5f * u))
    }
}
