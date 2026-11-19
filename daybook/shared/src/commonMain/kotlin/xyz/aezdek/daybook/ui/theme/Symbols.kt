package xyz.aezdek.daybook.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import xyz.aezdek.daybook.core.Subject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Line icons drawn in the spirit of SF Symbols (which may not be shipped outside Apple platforms):
 * a 24-unit grid, round caps and joins, and a stroke that matches body text weight. Tab bar items have
 * filled variants for the selected state.
 */
object Symbols {
    val plus by lazy { symbol("plus", stroke("M12 5v14M5 12h14", width = 2f)) }
    val checkmark by lazy { symbol("checkmark", stroke("M5 12.5l4.5 4.5L19 7.5", width = 2.2f)) }
    val chevronLeft by lazy { symbol("chevron.left", stroke("M14.5 5.5L8 12l6.5 6.5", width = 2f)) }
    val chevronRight by lazy { symbol("chevron.right", stroke("M9.5 5.5L16 12l-6.5 6.5", width = 2f)) }
    val chevronDown by lazy { symbol("chevron.down", stroke("M6 9.5l6 6 6-6", width = 2f)) }
    val chevronUpDown by lazy { symbol("chevron.up.chevron.down", stroke("M8 9.5l4-4 4 4M8 14.5l4 4 4-4", width = 1.8f)) }
    val xmark by lazy { symbol("xmark", stroke("M6.5 6.5l11 11M17.5 6.5l-11 11", width = 2f)) }
    val trash by lazy {
        symbol(
            "trash",
            stroke("M4.5 6.5h15M9.5 6.5V5a1.5 1.5 0 0 1 1.5-1.5h2A1.5 1.5 0 0 1 14.5 5v1.5"),
            stroke("M6.5 6.5l.9 12.1a2 2 0 0 0 2 1.9h5.2a2 2 0 0 0 2-1.9l.9-12.1M10 10.5v6M14 10.5v6"),
        )
    }
    val moveToTomorrow by lazy { symbol("arrowshape.turn.up.right", stroke("M5 19v-4a5 5 0 0 1 5-5h9M15 6l4 4-4 4")) }
    val undo by lazy { symbol("arrow.uturn.backward", stroke("M9 15l-4.5-4.5L9 6M4.5 10.5H15a4.5 4.5 0 0 1 0 9h-3")) }
    val pencil by lazy { symbol("pencil", stroke("M15.5 4.5l4 4L9 19H5v-4zM13.5 6.5l4 4")) }
    val ellipsis by lazy {
        symbol(
            "ellipsis.circle",
            stroke("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z"),
            fill("M7 12a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0-2.5 0zM10.75 12a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0-2.5 0zM14.5 12a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0-2.5 0z"),
        )
    }

    // Tab bar
    val calendar by lazy {
        symbol(
            "calendar",
            stroke("M5.5 5h13a2 2 0 0 1 2 2v11.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3.5 9.5h17M8 3v3.5M16 3v3.5"),
            fill("M7 13a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM11 13a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM15 13a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM7 16.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM11 16.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0z"),
        )
    }
    val calendarFill by lazy {
        symbol(
            "calendar.fill",
            fill(
                "M5.5 4.25h13a2.75 2.75 0 0 1 2.75 2.75v11.5a2.75 2.75 0 0 1-2.75 2.75h-13a2.75 2.75 0 0 1-2.75-2.75V7a2.75 2.75 0 0 1 2.75-2.75z" +
                    "M7 13a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM11 13a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM15 13a1 1 0 1 0 2 0a1 1 0 1 0-2 0z" +
                    "M7 16.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM11 16.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0zM3 9h18v1H3z",
                evenOdd = true,
            ),
            stroke("M8 2.75v3M16 2.75v3", width = 1.9f),
        )
    }
    val chart by lazy {
        symbol(
            "chart.bar",
            stroke("M5.5 13h1a1 1 0 0 1 1 1v5.5h-3V14a1 1 0 0 1 1-1zM11.5 8h1a1 1 0 0 1 1 1v10.5h-3V9a1 1 0 0 1 1-1zM17.5 3.5h1a1 1 0 0 1 1 1v15h-3v-15a1 1 0 0 1 1-1z"),
        )
    }
    val chartFill by lazy {
        symbol(
            "chart.bar.fill",
            fill("M5.25 12.5h1.5a1.25 1.25 0 0 1 1.25 1.25V20H4v-6.25a1.25 1.25 0 0 1 1.25-1.25zM11.25 7.5h1.5A1.25 1.25 0 0 1 14 8.75V20h-4V8.75a1.25 1.25 0 0 1 1.25-1.25zM17.25 3h1.5A1.25 1.25 0 0 1 20 4.25V20h-4V4.25A1.25 1.25 0 0 1 17.25 3z"),
        )
    }
    val gear by lazy { symbol("gearshape", stroke(gearOutline()), stroke(circle(12f, 12f, 3.2f))) }
    val gearFill by lazy { symbol("gearshape.fill", fill(gearOutline() + circle(12f, 12f, 3.2f), evenOdd = true)) }

    // Sync state
    private const val CLOUD = "M7 18.5h10a4 4 0 0 0 .8-7.92A6 6 0 0 0 6.3 9.3A4.6 4.6 0 0 0 7 18.5z"
    val cloud by lazy { symbol("icloud", stroke(CLOUD)) }
    val cloudCheck by lazy { symbol("checkmark.icloud", stroke(CLOUD), stroke("M9.6 14.2l1.8 1.8 3.6-3.7", width = 1.8f)) }
    val cloudSlash by lazy { symbol("icloud.slash", stroke(CLOUD), stroke("M4 4l16 16")) }
    val syncArrows by lazy {
        symbol("arrow.triangle.2.circlepath", stroke("M19 12a7 7 0 0 1-12.2 4.7M5 12a7 7 0 0 1 12.2-4.7M17.5 3.5v4h-4M6.5 20.5v-4h4"))
    }
    val warning by lazy {
        symbol(
            "exclamationmark.triangle",
            stroke("M10.3 4.6L2.9 17.5A2 2 0 0 0 4.6 20.5h14.8a2 2 0 0 0 1.7-3L13.7 4.6a2 2 0 0 0-3.4 0zM12 9.5v4"),
            fill(circle(12f, 16.9f, 1.1f)),
        )
    }
    val arrowClockwise by lazy { symbol("arrow.clockwise", stroke("M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4")) }

    // Subjects
    val code by lazy { symbol("chevron.left.forwardslash.chevron.right", stroke("M8 7.5L3.5 12 8 16.5M16 7.5l4.5 4.5-4.5 4.5M13.5 5l-3 14", width = 1.9f)) }
    val sum by lazy { symbol("sum", stroke("M17.5 5.5h-11l6 6.5-6 6.5h11", width = 1.9f)) }
    val atom by lazy { symbol("atom", stroke(atomOrbits(), width = 1.5f), fill(circle(12f, 12f, 1.7f))) }

    // Everything else
    val flame by lazy { symbol("flame", stroke("M12 3c.6 3.2 4.8 5.2 4.8 10a4.8 4.8 0 0 1-9.6 0c0-2 .8-3.4 1.9-4.6.2 1.5.9 2.6 2 3.1-.4-3 .2-5.8.9-8.5z")) }
    val graduationCap by lazy {
        symbol("graduationcap", stroke("M2.5 9.5L12 5l9.5 4.5L12 14zM6.5 11.5V16c0 1.4 2.5 2.8 5.5 2.8s5.5-1.4 5.5-2.8v-4.5M21.5 9.5v5"))
    }
    val link by lazy {
        symbol("link", stroke("M10.5 13.5a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 0 0-5-5l-1 1M13.5 10.5a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 0 0 5 5l1-1"))
    }
    val key by lazy { symbol("key", stroke(circle(8f, 15.5f, 4.5f) + "M11.2 12.3L19.5 4M16.5 7l2.5 2.5M14.5 9l1.8 1.8")) }
    val shield by lazy { symbol("lock.shield", stroke("M12 3l7.5 3v5.6c0 4.4-3.1 8.1-7.5 9.4-4.4-1.3-7.5-5-7.5-9.4V6z"), stroke("M9.5 11.5h5v4h-5zM10.5 11.5v-1.2a1.5 1.5 0 0 1 3 0v1.2", width = 1.4f)) }
    val eye by lazy { symbol("eye", stroke("M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z"), stroke(circle(12f, 12f, 2.8f))) }
    val eyeSlash by lazy { symbol("eye.slash", stroke("M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z"), stroke(circle(12f, 12f, 2.8f)), stroke("M4 4l16 16")) }
    val info by lazy { symbol("info.circle", stroke(circle(12f, 12f, 9f) + "M12 11v5.5"), fill(circle(12f, 7.9f, 1.1f))) }
    val checklist by lazy {
        symbol("checklist", stroke("M3.5 6.5l1.5 1.5L7.5 5.5M3.5 12.5l1.5 1.5 2.5-2.5M3.5 18.5l1.5 1.5 2.5-2.5M11 7h9.5M11 13h9.5M11 19h9.5"))
    }
    val clock by lazy { symbol("clock", stroke(circle(12f, 12f, 9f) + "M12 7.5V12l3 2")) }
    val doc by lazy { symbol("doc.text", stroke("M7 3.5h6.5l4.5 4.5v11.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1zM13.5 3.5V8H18M9 12.5h6M9 16h6")) }
    val laptop by lazy { symbol("laptopcomputer", stroke("M5 6.5A1.5 1.5 0 0 1 6.5 5h11A1.5 1.5 0 0 1 19 6.5V16H5zM2.5 16h19l-1 2.5h-17z")) }
    val circleHalf by lazy { symbol("circle.lefthalf.filled", stroke(circle(12f, 12f, 8.5f)), fill("M12 3.5a8.5 8.5 0 0 0 0 17z")) }

    fun subject(subject: Subject): ImageVector = when (subject) {
        Subject.CS -> code
        Subject.MATHS -> sum
        Subject.PHYSICS -> atom
    }

    // ---- construction ----

    private class Part(val d: String, val filled: Boolean, val width: Float, val evenOdd: Boolean)

    private fun stroke(d: String, width: Float = 1.75f) = Part(d, filled = false, width = width, evenOdd = false)
    private fun fill(d: String, evenOdd: Boolean = false) = Part(d, filled = true, width = 0f, evenOdd = evenOdd)

    private fun symbol(name: String, vararg parts: Part): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (part in parts) {
                    addPath(
                        pathData = addPathNodes(part.d),
                        pathFillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                        fill = if (part.filled) SolidColor(Color.Black) else null,
                        stroke = if (part.filled) null else SolidColor(Color.Black),
                        strokeLineWidth = part.width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx + r} ${cy}a$r $r 0 1 1 ${-2 * r} 0a$r $r 0 1 1 ${2 * r} 0z"

    /** Eight rounded-off teeth around a ring, like gearshape. */
    private fun gearOutline(): String {
        val teeth = 8
        val outer = 9.6f
        val inner = 7.4f
        val step = 2 * PI / teeth
        fun pt(r: Float, a: Double) = "${12 + r * cos(a).toFloat()} ${12 + r * sin(a).toFloat()}"
        val sb = StringBuilder()
        for (i in 0 until teeth) {
            val mid = i * step - PI / 2
            val baseStart = mid - step * 0.24
            val topStart = mid - step * 0.15
            val topEnd = mid + step * 0.15
            val baseEnd = mid + step * 0.24
            if (i == 0) sb.append("M${pt(inner, baseStart)}")
            sb.append("L${pt(outer, topStart)}")
            sb.append("A$outer $outer 0 0 1 ${pt(outer, topEnd)}")
            sb.append("L${pt(inner, baseEnd)}")
            sb.append("A$inner $inner 0 0 1 ${pt(inner, mid + step - step * 0.24)}")
        }
        return sb.append("z").toString()
    }

    /** Three elliptical orbits at 0°, 60° and 120°. */
    private fun atomOrbits(): String {
        val rx = 9.6f
        val ry = 3.7f
        return listOf(0.0, 60.0, 120.0).joinToString("") { deg ->
            val a = deg * PI / 180
            val x1 = 12 + rx * cos(a).toFloat()
            val y1 = 12 + rx * sin(a).toFloat()
            val x2 = 12 - rx * cos(a).toFloat()
            val y2 = 12 - rx * sin(a).toFloat()
            "M$x1 ${y1}A$rx $ry $deg 0 1 $x2 ${y2}A$rx $ry $deg 0 1 $x1 ${y1}z"
        }
    }
}
