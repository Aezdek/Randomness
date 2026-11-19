package xyz.aezdek.daybook.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A rounded rectangle with continuous curvature: each corner eases out of the straight edge through a
 * Bézier, then a short arc, then another Bézier, instead of a circular arc meeting a straight line. This
 * is the shape of Apple's "continuous" corner style.
 *
 * The construction follows Figma's corner smoothing (Desperately Seeking Squircles); 0.6 is the
 * smoothing that matches iOS.
 */
@Immutable
class ContinuousShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
    private val smoothing: Float = 0.6f,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val (tl, tr, br, bl) = with(density) {
            val ts = topStart.toPx()
            val te = topEnd.toPx()
            val be = bottomEnd.toPx()
            val bs = bottomStart.toPx()
            if (ltr) listOf(ts, te, be, bs) else listOf(te, ts, bs, be)
        }
        val w = size.width
        val h = size.height
        val budget = min(w, h) / 2f
        val cTL = corner(tl, budget)
        val cTR = corner(tr, budget)
        val cBR = corner(br, budget)
        val cBL = corner(bl, budget)

        val path = Path().apply {
            moveTo(cTL.p, 0f)
            lineTo(w - cTR.p, 0f)
            if (cTR.r > 0f) {
                relativeCubicTo(cTR.a, 0f, cTR.a + cTR.b, 0f, cTR.a + cTR.b + cTR.c, cTR.d)
                arcTo(Rect(w - 2 * cTR.r, 0f, w, 2 * cTR.r), -90f + cTR.startOffset, cTR.sweep, false)
                relativeCubicTo(cTR.d, cTR.c, cTR.d, cTR.b + cTR.c, cTR.d, cTR.a + cTR.b + cTR.c)
            }
            lineTo(w, h - cBR.p)
            if (cBR.r > 0f) {
                relativeCubicTo(0f, cBR.a, 0f, cBR.a + cBR.b, -cBR.d, cBR.a + cBR.b + cBR.c)
                arcTo(Rect(w - 2 * cBR.r, h - 2 * cBR.r, w, h), 0f + cBR.startOffset, cBR.sweep, false)
                relativeCubicTo(-cBR.c, cBR.d, -(cBR.b + cBR.c), cBR.d, -(cBR.a + cBR.b + cBR.c), cBR.d)
            }
            lineTo(cBL.p, h)
            if (cBL.r > 0f) {
                relativeCubicTo(-cBL.a, 0f, -(cBL.a + cBL.b), 0f, -(cBL.a + cBL.b + cBL.c), -cBL.d)
                arcTo(Rect(0f, h - 2 * cBL.r, 2 * cBL.r, h), 90f + cBL.startOffset, cBL.sweep, false)
                relativeCubicTo(-cBL.d, -cBL.c, -cBL.d, -(cBL.b + cBL.c), -cBL.d, -(cBL.a + cBL.b + cBL.c))
            }
            lineTo(0f, cTL.p)
            if (cTL.r > 0f) {
                relativeCubicTo(0f, -cTL.a, 0f, -(cTL.a + cTL.b), cTL.d, -(cTL.a + cTL.b + cTL.c))
                arcTo(Rect(0f, 0f, 2 * cTL.r, 2 * cTL.r), 180f + cTL.startOffset, cTL.sweep, false)
                relativeCubicTo(cTL.c, -cTL.d, cTL.b + cTL.c, -cTL.d, cTL.a + cTL.b + cTL.c, -cTL.d)
            }
            close()
        }
        return Outline.Generic(path)
    }

    private class Corner(
        val r: Float,
        /** Distance along each edge that the corner occupies. */
        val p: Float,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        /** Degrees from the edge direction to where the arc starts. */
        val startOffset: Float,
        val sweep: Float,
    )

    private fun corner(radius: Float, budget: Float): Corner {
        val r = min(radius, budget).coerceAtLeast(0f)
        if (r <= 0f) return Corner(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        var s = smoothing
        var p = (1 + s) * r
        if (p > budget) {
            s = (budget / r - 1).coerceIn(0f, s)
            p = min(p, budget)
        }
        val arcMeasure = 90f * (1 - s)
        val arcLength = sin(rad(arcMeasure / 2)) * r * sqrt(2f)
        val angleAlpha = (90f - arcMeasure) / 2
        val p3ToP4 = r * tan(rad(angleAlpha / 2))
        val angleBeta = 45f * s
        val c = p3ToP4 * cos(rad(angleBeta))
        val d = c * tan(rad(angleBeta))
        val b = (p - arcLength - c - d) / 3
        val a = 2 * b
        return Corner(r, p, a, b, c, d, startOffset = 45f * s, sweep = arcMeasure)
    }

    private fun rad(degrees: Float): Float = (degrees * PI / 180.0).toFloat()

    override fun equals(other: Any?): Boolean = other is ContinuousShape &&
        topStart == other.topStart && topEnd == other.topEnd && bottomEnd == other.bottomEnd &&
        bottomStart == other.bottomStart && smoothing == other.smoothing

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return 31 * result + smoothing.hashCode()
    }
}

/** Corner radii, so every nested shape can be made concentric with its container. */
object Radii {
    /** Inset grouped list sections. */
    val section = 24.dp

    /** Sheets and the sidebar. */
    val sheet = 34.dp

    /** Popover menus. */
    val menu = 24.dp

    val heatCell = 3.dp

    /** The radius of a shape inset by [padding] inside a container with radius [outer]. */
    fun concentric(outer: Dp, padding: Dp): Dp = (outer - padding).coerceAtLeast(0.dp)
}
