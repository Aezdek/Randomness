package xyz.aezdek.hush.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import xyz.aezdek.hush.ui.theme.Brand

/** The Hush mark, as an app icon: a blue rounded square with a white keyhole (web/src/ui/Brand.tsx's geometry). */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 48f
        drawRoundRect(
            brush = Brush.verticalGradient(Brand.iconColors),
            topLeft = Offset(1f * u, 1f * u),
            size = Size(46f * u, 46f * u),
            cornerRadius = CornerRadius(11f * u),
        )
        drawCircle(Color.White, radius = 6.2f * u, center = Offset(24f * u, 20f * u))
        val stem = Path().apply {
            moveTo(21f * u, 23.8f * u)
            lineTo(27f * u, 23.8f * u)
            lineTo(29.1f * u, 34.8f * u)
            quadraticTo(29.3f * u, 36.5f * u, 27.7f * u, 36.5f * u)
            lineTo(20.3f * u, 36.5f * u)
            quadraticTo(18.7f * u, 36.5f * u, 18.9f * u, 34.8f * u)
            close()
        }
        drawPath(stem, Color.White)
    }
}
