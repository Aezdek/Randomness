package xyz.aezdek.aezgram.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import xyz.aezdek.aezgram.ui.theme.Brand

/** The AezGram mark: a keyhole in a rounded frame (the geometry of web/public/favicon.svg, on a 48-unit grid). */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 48f
        val brush = Brush.linearGradient(Brand.colors, Offset.Zero, Offset(size.width, size.height))
        drawRoundRect(
            brush = brush,
            topLeft = Offset(3.5f * u, 3.5f * u),
            size = Size(41f * u, 41f * u),
            cornerRadius = CornerRadius(15f * u),
            style = Stroke(width = 4f * u),
        )
        drawCircle(brush = brush, radius = 6f * u, center = Offset(24f * u, 20.5f * u))
        val stem = Path().apply {
            moveTo(21.2f * u, 24.5f * u)
            lineTo(26.8f * u, 24.5f * u)
            lineTo(29f * u, 35.7f * u)
            quadraticTo(29.2f * u, 37.4f * u, 27.6f * u, 37.4f * u)
            lineTo(20.4f * u, 37.4f * u)
            quadraticTo(18.8f * u, 37.4f * u, 19f * u, 35.7f * u)
            close()
        }
        drawPath(stem, brush)
    }
}
