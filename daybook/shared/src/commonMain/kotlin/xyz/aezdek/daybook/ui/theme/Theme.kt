package xyz.aezdek.daybook.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalAppleColors = staticCompositionLocalOf { LightColors }
val LocalAppleType = staticCompositionLocalOf<AppleTypography> { error("DaybookTheme is missing") }

/** True when the system asks for reduced motion. Springs lose their bounce and slides become fades. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Screen margin: 16 in compact width, 20 in regular. */
val LocalScreenMargin = staticCompositionLocalOf { 16.dp }

object Apple {
    val colors: AppleColors
        @Composable @ReadOnlyComposable get() = LocalAppleColors.current
    val type: AppleTypography
        @Composable @ReadOnlyComposable get() = LocalAppleType.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
    val margin: Dp
        @Composable @ReadOnlyComposable get() = LocalScreenMargin.current
}

/**
 * Motion is springs throughout, so animations keep the velocity of the gesture that started them and can
 * be grabbed mid-flight. Under Reduce Motion they are critically damped: same destination, no bounce.
 */
object Motion {
    /** Most movement: sheets, menus, the segmented control's thumb. */
    fun <T> standard(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)
        else spring(dampingRatio = 0.84f, stiffness = 420f)

    /** Small, quick feedback such as a checkbox filling. */
    fun <T> snappy(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)
        else spring(dampingRatio = 0.6f, stiffness = 700f)
}

@Composable
fun DaybookTheme(dark: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val type = rememberAppleTypography()
    CompositionLocalProvider(
        LocalAppleColors provides colors,
        LocalAppleType provides type,
        LocalReduceMotion provides reduceMotion,
        LocalIndication provides HighlightIndication,
        LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = 0.3f)),
        content = content,
    )
}
