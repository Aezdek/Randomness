package xyz.aezdek.daybook.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** The content layer that glass controls blur. Screens register their scrolling content with it. */
val LocalHazeState = staticCompositionLocalOf<HazeState> { error("No HazeState") }

enum class GlassVariant {
    /** Tab bar, toolbar buttons, sidebar, menus: blurred, luminosity-adjusted, legible over anything. */
    Regular,

    /** The one prominent control on a screen: glass tinted with the accent. */
    Prominent,
}

/**
 * Liquid Glass, approximated: the content behind is blurred and tinted, a light rim catches the top
 * edge, and a soft, wide, low-opacity shadow lifts the control off the page. Only for the control layer;
 * content never sits on glass.
 */
@Composable
fun Modifier.glass(shape: Shape, variant: GlassVariant = GlassVariant.Regular): Modifier {
    val colors = Apple.colors
    val haze = LocalHazeState.current
    val style = remember(colors, variant) {
        val tint = if (variant == GlassVariant.Prominent) colors.glassProminentTint else colors.glassTint
        HazeBlurStyle {
            blurRadius(24.dp)
            noiseFactor(0f)
            backgroundColor(colors.background)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
        }
    }
    return this
        .shadow(elevation = 14.dp, shape = shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
        .clip(shape)
        .hazeBlur(HazeInput.Sources(haze), style)
        .border(1.dp, Brush.verticalGradient(listOf(colors.glassRimTop, colors.glassRimBottom)), shape)
}

/**
 * The scroll edge effect: where content slides under the floating top controls it blurs progressively
 * and fades into the background, instead of meeting an opaque bar.
 */
@Composable
fun Modifier.scrollEdge(top: Boolean): Modifier {
    val colors = Apple.colors
    val haze = LocalHazeState.current
    val style = remember(colors, top) {
        val fade = if (top) listOf(colors.background.copy(alpha = 0.92f), colors.background.copy(alpha = 0f))
        else listOf(colors.background.copy(alpha = 0f), colors.background.copy(alpha = 0.92f))
        HazeBlurStyle {
            blurRadius(16.dp)
            noiseFactor(0f)
            backgroundColor(colors.background)
            colorEffects(listOf(HazeColorEffect.tint(Brush.verticalGradient(fade))))
            progressive(
                if (top) HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
                else HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f),
            )
        }
    }
    return hazeBlur(HazeInput.Sources(haze), style)
}

val Transparent = Color.Transparent
