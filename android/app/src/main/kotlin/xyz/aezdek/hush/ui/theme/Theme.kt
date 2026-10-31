package xyz.aezdek.hush.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.data.ThemeMode

/** The same tokens as web/src/styles/tokens.css, so both clients look like one product. */
@Immutable
data class AezColors(
    val isDark: Boolean,
    val bg: Color,
    val elev1: Color,
    val elev2: Color,
    val elev3: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val border: Color,
    val borderStrong: Color,
    val glass: Color,
    val link: Color,
    val danger: Color,
    val success: Color,
    /** Primary actions are monochrome: white on dark, black on light. */
    val primary: Color,
    val onPrimary: Color,
    val bubbleOther: Color,
)

val DarkColors = AezColors(
    isDark = true,
    bg = Color(0xFF08080A),
    elev1 = Color(0xFF111114),
    elev2 = Color(0xFF18181D),
    elev3 = Color(0xFF222229),
    text = Color(0xFFF5F5F7),
    text2 = Color(0xFFB4B4BE),
    text3 = Color(0xFF7A7A86),
    border = Color(0x1FFFFFFF),
    borderStrong = Color(0x33FFFFFF),
    glass = Color(0xCC08080A),
    link = Color(0xFF8AB4FF),
    danger = Color(0xFFFF4D67),
    success = Color(0xFF2FD07D),
    primary = Color(0xFFF5F5F7),
    onPrimary = Color(0xFF08080A),
    bubbleOther = Color(0xFF1E1E24),
)

val LightColors = AezColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    elev1 = Color(0xFFF6F6F8),
    elev2 = Color(0xFFEFEFF3),
    elev3 = Color(0xFFE5E5EB),
    text = Color(0xFF0B0B10),
    text2 = Color(0xFF4A4A55),
    text3 = Color(0xFF8A8A96),
    border = Color(0x14000000),
    borderStrong = Color(0x26000000),
    glass = Color(0xD9FFFFFF),
    link = Color(0xFF2D5BE3),
    danger = Color(0xFFE5294A),
    success = Color(0xFF14A85F),
    primary = Color(0xFF0B0B10),
    onPrimary = Color(0xFFFFFFFF),
    bubbleOther = Color(0xFFEFEFF3),
)

object Brand {
    val violet = Color(0xFF7C5CFF)
    val pink = Color(0xFFE84393)
    val orange = Color(0xFFFF8A3D)
    val closeFriends = Color(0xFF1DBF73)
    val live = Color(0xFFFF2D55)
    val colors = listOf(violet, pink, orange)
    val gradient: Brush get() = Brush.linearGradient(colors)
    val sweep: Brush get() = Brush.sweepGradient(listOf(violet, pink, orange, pink, violet))
    val messageGradient: Brush get() = Brush.linearGradient(listOf(Color(0xFF8A5CFF), Color(0xFFC04BD8)))
}

val LocalAez = staticCompositionLocalOf { DarkColors }

object Aez {
    val colors: AezColors
        @Composable @ReadOnlyComposable get() = LocalAez.current
}

/** The wordmark: a serif italic, like the web's Instrument Serif. */
/** The "Hush" wordmark: the system font, bold and tightly set (the italic serif read as out of place). */
val Wordmark = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-1.0).sp)

private fun typography(c: AezColors) = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = c.text),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = c.text),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = c.text),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = c.text),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = c.text),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = c.text),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = c.text2),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text2),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.text3),
)

val AezShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AezTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, secondary = Brand.pink, background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text, surfaceVariant = c.elev2, onSurfaceVariant = c.text2, surfaceContainer = c.elev1,
            surfaceContainerHigh = c.elev2, surfaceContainerHighest = c.elev3, surfaceContainerLow = c.elev1, outline = c.borderStrong,
            outlineVariant = c.border, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, secondary = Brand.pink, background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text, surfaceVariant = c.elev2, onSurfaceVariant = c.text2, surfaceContainer = c.elev1,
            surfaceContainerHigh = c.elev2, surfaceContainerHighest = c.elev3, surfaceContainerLow = c.elev1, outline = c.borderStrong,
            outlineVariant = c.border, error = c.danger,
        )
    }
    CompositionLocalProvider(LocalAez provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography(c), shapes = AezShapes, content = content)
    }
}
