package xyz.aezdek.hush.ui.messages

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import xyz.aezdek.hush.client.ChatPattern
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A theme's faint pattern, tiled behind the chat: the same marks, sizes and strength as the web app draws
 * (patternTile in web/src/core/chat.ts), measured in dp where the web measures CSS pixels.
 */
fun Modifier.chatPattern(pattern: ChatPattern?, onDark: Boolean): Modifier =
    if (pattern == null) this else drawBehind {
        val ink = if (onDark) Color.White.copy(alpha = 0.075f) else Color.Black.copy(alpha = 0.055f)
        val unit = density
        val tile = unit * when (pattern) {
            ChatPattern.DOTS -> 44f
            ChatPattern.PETALS -> 64f
            ChatPattern.SPARKLES -> 60f
            ChatPattern.WAVES -> 48f
            ChatPattern.GRID -> 32f
        }
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                tile(pattern, x, y, unit, ink)
                x += tile
            }
            y += tile
        }
    }

private fun DrawScope.tile(pattern: ChatPattern, ox: Float, oy: Float, u: Float, ink: Color) {
    fun at(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    when (pattern) {
        ChatPattern.DOTS -> {
            drawCircle(ink, 1.8f * u, at(11f, 11f))
            drawCircle(ink, 1.8f * u, at(33f, 33f))
        }
        ChatPattern.PETALS -> {
            for ((cx, cy, k) in listOf(Triple(16f, 18f, 1f), Triple(46f, 46f, 0.7f))) {
                for (a in 0 until 5) {
                    val r = a * 72.0 * PI / 180.0
                    drawCircle(ink, 2.3f * k * u, at(cx + (sin(r) * 3.4 * k).toFloat(), cy - (cos(r) * 3.4 * k).toFloat()))
                }
            }
        }
        ChatPattern.SPARKLES -> {
            for ((cx, cy, s) in listOf(Triple(15f, 18f, 5f), Triple(44f, 42f, 3.2f))) {
                val c = at(cx, cy)
                val path = Path().apply {
                    moveTo(c.x, c.y - s * u)
                    quadraticTo(c.x, c.y, c.x + s * u, c.y)
                    quadraticTo(c.x, c.y, c.x, c.y + s * u)
                    quadraticTo(c.x, c.y, c.x - s * u, c.y)
                    quadraticTo(c.x, c.y, c.x, c.y - s * u)
                    close()
                }
                drawPath(path, ink)
            }
            drawCircle(ink, 1.1f * u, at(46f, 12f))
        }
        ChatPattern.WAVES -> {
            for (base in listOf(14f, 38f)) {
                val path = Path().apply {
                    val start = at(0f, base)
                    moveTo(start.x, start.y)
                    at(12f, base - 7f).let { c -> at(24f, base).let { e -> quadraticTo(c.x, c.y, e.x, e.y) } }
                    at(36f, base + 7f).let { c -> at(48f, base).let { e -> quadraticTo(c.x, c.y, e.x, e.y) } }
                }
                drawPath(path, ink, style = Stroke(width = 1.4f * u))
            }
        }
        ChatPattern.GRID -> {
            drawLine(ink, at(32f, 0f), at(0f, 0f), strokeWidth = u)
            drawLine(ink, at(0f, 0f), at(0f, 32f), strokeWidth = u)
        }
    }
}
