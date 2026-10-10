package xyz.aezdek.aezprint.feature.print.preview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.motion.AezMotion
import xyz.aezdek.aezprint.designsystem.theme.PaperWhite
import xyz.aezdek.aezprint.domain.layout.Face
import xyz.aezdek.aezprint.domain.layout.Placement

@Composable
internal fun SheetView(
    index: Int,
    face: Face?,
    renderId: String?,
    stale: Boolean,
    loadImage: suspend (String, Int, Int) -> ImageBitmap?,
    onTap: (Float, Float) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspect = face?.let { (it.widthPt / it.heightPt).toFloat() } ?: A4_ASPECT
    val animatedAspect by animateFloatAsState(aspect, AezMotion.reflow(), label = "sheetAspect")
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
        onZoomChange(scale)
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() } * if (scale > 1.5f) 2 else 1
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        var imageKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(renderId, index, widthPx) {
            if (renderId == null) return@LaunchedEffect
            val loaded = loadImage(renderId, index + 1, widthPx)
            if (loaded != null) {
                image = loaded
                imageKey = renderId
            }
        }
        val showLayout = image == null || stale || imageKey != renderId

        Surface(
            modifier = Modifier
                .wrapContentSize()
                .aspectRatio(animatedAspect)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(transformState, canPan = { scale > 1f })
                .pointerInput(face) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                            onZoomChange(scale)
                        },
                        onTap = { position -> onTap(position.x / size.width, position.y / size.height) },
                    )
                }
                .semantics {
                    testTag = "sheet-$index"
                    contentDescription = sheetDescription(face)
                    customActions = face?.placements.orEmpty().distinctBy { it.page }.map { placement ->
                        CustomAccessibilityAction("Make page ${placement.page} the current page") {
                            val frame = placement.frame
                            onTap(((frame.left + frame.width / 2) / face!!.widthPt).toFloat(), ((frame.top + frame.height / 2) / face.heightPt).toFloat())
                            true
                        }
                    }
                },
            shape = RoundedCornerShape(2.dp),
            color = PaperWhite,
            shadowElevation = 8.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                Crossfade(targetState = image, label = "sheetImage") { bitmap ->
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                if (face != null) {
                    val layoutAlpha by animateFloatAsState(if (showLayout) 1f else 0f, AezMotion.standard(), label = "layoutAlpha")
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = layoutAlpha }) {
                        face.placements.forEachIndexed { slot, placement ->
                            key(slot) { ReflowingPlacement(face, placement, solid = image == null) }
                        }
                    }
                }
                if (stale && renderId != null) {
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).wrapContentSize())
                }
            }
        }
    }
}

/** A page outline that glides to its new spot whenever layout options change, before the server render lands. */
@Composable
private fun ReflowingPlacement(face: Face, placement: Placement, solid: Boolean) {
    val visible = placement.clip?.let(placement.frame::intersect) ?: placement.frame
    val target = Rect(
        (visible.left / face.widthPt).toFloat(),
        (visible.top / face.heightPt).toFloat(),
        (visible.right / face.widthPt).toFloat(),
        (visible.bottom / face.heightPt).toFloat(),
    )
    val rect by animateValueAsState(target, Rect.VectorConverter, AezMotion.reflow(), label = "placement")
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (solid) 0.10f else 0.05f)
    val stroke = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Canvas(Modifier.fillMaxSize()) {
        val topLeft = Offset(rect.left * size.width, rect.top * size.height)
        val box = androidx.compose.ui.geometry.Size(rect.width * size.width, rect.height * size.height)
        drawRect(fill, topLeft, box)
        drawRect(stroke, topLeft, box, style = Stroke(1.dp.toPx()))
    }
}

private fun sheetDescription(face: Face?): String {
    if (face == null) return "Sheet preview"
    val pages = face.placements.map { it.page }.distinct()
    val side = if (face.isBack) "back" else "front"
    val pageText = when (pages.size) {
        0 -> "blank"
        1 -> "page ${pages.single()}"
        else -> "pages ${pages.joinToString(", ")}"
    }
    return "Sheet ${face.sheetNumber} $side, $pageText. Double tap to zoom."
}

private const val A4_ASPECT = 0.7071f
private const val MAX_ZOOM = 5f
