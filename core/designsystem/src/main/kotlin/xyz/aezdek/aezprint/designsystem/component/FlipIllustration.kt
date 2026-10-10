package xyz.aezdek.aezprint.designsystem.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.theme.PaperWhite
import xyz.aezdek.aezprint.domain.model.Duplex

/**
 * Shows the printed stack lifting out of the output tray and turning over the edge the job binds
 * on, then dropping into the input tray: the one physical step manual duplex needs from the user.
 */
@Composable
fun FlipIllustration(duplex: Duplex, modifier: Modifier = Modifier) {
    val longEdge = duplex != Duplex.SHORT_EDGE
    val transition = rememberInfiniteTransition(label = "flip")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3200
                0f at 0
                0f at 500
                180f at 1700 using FastOutSlowInEasing
                180f at 3200
            },
            RepeatMode.Restart,
        ),
        label = "flipAngle",
    )
    val lift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3200
                0f at 0
                1f at 500
                1f at 1700
                0f at 2300
                0f at 3200
            },
            RepeatMode.Restart,
        ),
        label = "flipLift",
    )
    val outline = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val accent = MaterialTheme.colorScheme.primary
    val tray = MaterialTheme.colorScheme.surfaceContainerHighest
    val edgeName = if (longEdge) "long" else "short"

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.3f)
            .semantics { contentDescription = "Illustration: lift the printed stack and turn it over along the $edgeName edge, then put it back in the paper tray" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRoundRect(tray, Offset(w * 0.18f, h * 0.78f), Size(w * 0.64f, h * 0.1f), CornerRadius(10.dp.toPx()))
            drawRoundRect(outline, Offset(w * 0.18f, h * 0.78f), Size(w * 0.64f, h * 0.1f), CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        Box(
            Modifier
                .fillMaxSize(0.46f)
                .offset(y = (-24 * lift).dp)
                .graphicsLayer {
                    if (longEdge) rotationY = angle else rotationX = angle
                    cameraDistance = 14 * density
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val showingBack = angle > 90f
                val sheetW = size.width * 0.72f
                val sheetH = size.height
                val left = (size.width - sheetW) / 2
                repeat(3) { i ->
                    val o = (2 - i) * 3.dp.toPx()
                    drawRoundRect(PaperWhite, Offset(left + o, o), Size(sheetW, sheetH), CornerRadius(4.dp.toPx()))
                    drawRoundRect(outline, Offset(left + o, o), Size(sheetW, sheetH), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
                }
                if (!showingBack) {
                    for (line in 1..6) {
                        val y = sheetH * line / 8f
                        drawLine(ink, Offset(left + sheetW * 0.14f, y), Offset(left + sheetW * (if (line == 6) 0.55f else 0.86f), y), 2.5.dp.toPx(), StrokeCap.Round)
                    }
                }
                val hinge = if (longEdge) {
                    Offset(left + sheetW, 0f) to Offset(left + sheetW, sheetH)
                } else {
                    Offset(left, 0f) to Offset(left + sheetW, 0f)
                }
                drawLine(accent, hinge.first, hinge.second, 4.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
