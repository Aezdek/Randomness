package xyz.aezdek.aezprint.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.motion.AezMotion
import xyz.aezdek.aezprint.designsystem.theme.PaperWhite

/** A sheet sliding out of the printer while [active]; the output stack grows with [progress]. */
@Composable
fun PaperFeedAnimation(active: Boolean, progress: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "feed")
    val feed by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "feedOffset",
    )
    val stack by animateFloatAsState(progress.coerceIn(0f, 1f), AezMotion.standard(600), label = "stack")
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier.aspectRatio(1.2f).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val printerTop = h * 0.08f
        val printerHeight = h * 0.38f
        val slotY = printerTop + printerHeight * 0.72f
        val paperWidth = w * 0.46f
        val paperLeft = (w - paperWidth) / 2
        val paperHeight = h * 0.42f

        val stackSheets = (stack * 6).toInt()
        val trayTop = h * 0.86f
        repeat(stackSheets) { i ->
            val y = trayTop - i * 3.dp.toPx()
            drawRoundRect(PaperWhite, Offset(paperLeft, y), Size(paperWidth, 6.dp.toPx()), CornerRadius(2.dp.toPx()))
            drawRoundRect(outline, Offset(paperLeft, y), Size(paperWidth, 6.dp.toPx()), CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
        }

        if (active) {
            val travel = feed * (trayTop - slotY - paperHeight * 0.2f)
            clipRect(top = slotY) {
                val top = slotY - paperHeight + travel + paperHeight * 0.25f
                drawRoundRect(PaperWhite, Offset(paperLeft, top), Size(paperWidth, paperHeight), CornerRadius(3.dp.toPx()))
                drawRoundRect(outline, Offset(paperLeft, top), Size(paperWidth, paperHeight), CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
                for (line in 1..5) {
                    val y = top + paperHeight * line / 7f
                    drawLine(ink, Offset(paperLeft + paperWidth * 0.14f, y), Offset(paperLeft + paperWidth * (if (line == 5) 0.6f else 0.86f), y), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }

        drawRoundRect(body, Offset(w * 0.12f, printerTop), Size(w * 0.76f, printerHeight), CornerRadius(18.dp.toPx()))
        drawRoundRect(outline, Offset(w * 0.12f, printerTop), Size(w * 0.76f, printerHeight), CornerRadius(18.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawLine(outline, Offset(paperLeft - 6.dp.toPx(), slotY), Offset(paperLeft + paperWidth + 6.dp.toPx(), slotY), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(if (active) accent else outline, 5.dp.toPx(), Offset(w * 0.78f, printerTop + printerHeight * 0.28f))
    }
}
