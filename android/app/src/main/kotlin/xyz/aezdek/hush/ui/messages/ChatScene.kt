package xyz.aezdek.hush.ui.messages

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import xyz.aezdek.hush.client.ChatScene
import xyz.aezdek.hush.client.ChatScenes
import xyz.aezdek.hush.client.ChatTheme
import xyz.aezdek.hush.client.ChatThemeLook
import xyz.aezdek.hush.client.Motes
import xyz.aezdek.hush.client.Ridge
import kotlin.math.roundToInt

/**
 * A theme's scenery behind the chat, drawn over its gradient: washes of colour, the scattered marks, the sun or
 * moon, then the ridges in front. The same shapes, sizes and colours as the web app draws (themeBackground in
 * web/src/core/chat.ts), measured in dp where the web measures CSS pixels. [scale] shrinks the whole scene for the
 * small previews in the theme picker.
 */
fun Modifier.chatScene(theme: ChatTheme, dark: Boolean, scale: Float = 1f): Modifier {
    val look = theme.look(dark) ?: return this
    val scene = theme.scene ?: return this
    return drawBehind { drawScene(theme, look, scene, dark, density * scale) }
}

private fun DrawScope.drawScene(theme: ChatTheme, look: ChatThemeLook, scene: ChatScene, dark: Boolean, u: Float) {
    for (glow in scene.glows) {
        val colour = Color(theme.colors[glow.color]).copy(alpha = if (dark) glow.darkAlpha else glow.lightAlpha)
        val center = Offset(glow.x * size.width, glow.top * u)
        val rx = glow.rx * size.width
        // An ellipse: a circle as wide as the wash, squashed to its height.
        withTransform({ scale(1f, glow.ry * u / rx, center) }) {
            drawCircle(Brush.radialGradient(0f to colour, 0.72f to Color.Transparent, center = center, radius = rx), radius = rx, center = center)
        }
    }

    scene.motes?.let { motes ->
        val strength = if (dark) motes.darkAlpha else motes.lightAlpha
        if (strength <= 0f) return@let
        val ink = if (motes.kind == Motes.Kind.STAR && dark) Color.White else Color(look.accent)
        val tile = ChatScenes.MOTE_TILE * u
        val marks = ChatScenes.scatter(motes.seed, motes.count)
        var top = 0f
        while (top < size.height) {
            var left = 0f
            while (left < size.width) {
                for (mark in marks) {
                    val s = (motes.minSize + (motes.maxSize - motes.minSize) * mark.s) * u
                    val colour = ink.copy(alpha = strength * (0.45f + 0.55f * mark.t))
                    // A mark that crosses the tile's edge shows on the far side too, as it does where the web's tile repeats.
                    for (x in wrapped(mark.x * tile, s, tile)) for (y in wrapped(mark.y * tile, s, tile)) {
                        mote(motes.kind, Offset(left + x, top + y), s, mark.t, colour, u)
                    }
                }
                left += tile
            }
            top += tile
        }
    }

    scene.orb?.let { orb ->
        val center = Offset(orb.x * size.width, orb.top?.let { it * u } ?: (size.height - (orb.bottom ?: 0f) * u))
        val colour = if (dark) Color(look.accent) else Color(ChatScenes.mix(theme.colors[0], 0xFFFFFFFF, 0.25f))
        val r = orb.r * u
        val halo = colour.copy(alpha = if (dark) 0.3f else 0.26f)
        drawCircle(Brush.radialGradient(listOf(halo, Color.Transparent), center = center, radius = r * 3.4f), radius = r * 3.4f, center = center)
        drawCircle(colour.copy(alpha = if (dark) 0.92f else 0.8f), r, center)
    }

    if (scene.ridges.isNotEmpty()) {
        val span = scene.span?.let { it * u } ?: size.width
        var left = 0f
        while (left < size.width) {
            for (ridge in scene.ridges) drawPath(ridgePath(ridge, left, span, size.height, u), Color(ChatScenes.ridgeColor(look, ridge.depth, dark)))
            left += span
        }
    }
}

private fun wrapped(at: Float, size: Float, tile: Float): List<Float> = when {
    at < size -> listOf(at, at + tile)
    at > tile - size -> listOf(at, at - tile)
    else -> listOf(at)
}

private fun DrawScope.mote(kind: Motes.Kind, c: Offset, s: Float, turn: Float, ink: Color, u: Float) {
    when (kind) {
        Motes.Kind.STAR, Motes.Kind.DOT -> drawCircle(ink, s, c)
        Motes.Kind.RING -> drawCircle(ink, s, c, style = Stroke(width = 1.2f * u))
        Motes.Kind.PETAL -> rotate((turn * 360f).roundToInt().toFloat(), c) {
            drawOval(ink, topLeft = Offset(c.x - s, c.y - s * 0.55f), size = Size(s * 2f, s * 1.1f))
        }
        Motes.Kind.SPARKLE -> {
            val star = Path().apply {
                moveTo(c.x, c.y - s)
                quadraticTo(c.x, c.y, c.x + s, c.y)
                quadraticTo(c.x, c.y, c.x, c.y + s)
                quadraticTo(c.x, c.y, c.x - s, c.y)
                quadraticTo(c.x, c.y, c.x, c.y - s)
                close()
            }
            drawPath(star, ink)
        }
    }
}

/** One ridge standing on [bottom], from [left] across [width] (ridgePath in the web app). */
private fun ridgePath(ridge: Ridge, left: Float, width: Float, bottom: Float, u: Float): Path = Path().apply {
    val heights = ridge.heights
    fun y(v: Float) = bottom - v * ridge.height * u
    fun x(i: Int) = left + width * i / (heights.size - 1)
    moveTo(left, bottom)
    when (ridge.shape) {
        Ridge.Shape.STEPS -> {
            // Flat roofs: each height holds until the next one starts.
            val each = width / heights.size
            heights.forEachIndexed { i, v ->
                lineTo(left + i * each, y(v))
                lineTo(left + (i + 1) * each, y(v))
            }
        }
        Ridge.Shape.PEAKS -> heights.forEachIndexed { i, v -> lineTo(x(i), y(v)) }
        Ridge.Shape.SMOOTH -> {
            // A curve through the midpoints, bending at each height.
            lineTo(x(0), y(heights[0]))
            for (i in 0 until heights.size - 1) {
                quadraticTo(x(i), y(heights[i]), (x(i) + x(i + 1)) / 2f, (y(heights[i]) + y(heights[i + 1])) / 2f)
            }
            lineTo(x(heights.size - 1), y(heights.last()))
        }
    }
    lineTo(left + width, bottom)
    close()
}
