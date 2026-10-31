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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.data.ThemeMode

/**
 * The same tokens as web/src/styles/tokens.css: Apple's iOS system colours (dark and light), blue as the one accent,
 * grouped backgrounds and hairline separators. Text uses the phone's own font: Apple's San Francisco can't ship in an
 * Android app, so the type follows iOS's scale and weights instead.
 */
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
    /** Filled buttons: the accent, with white on top. */
    val primary: Color,
    val onPrimary: Color,
    val bubbleOther: Color,
    val accent: Color,
    val bubbleMe: Color,
    /** iOS fills: tinted greys for controls on any background. */
    val fill: Color,
    val fill3: Color,
    /** Grouped lists: the page behind, and the rounded group on it. */
    val grouped: Color,
    val card: Color,
    /** The selected segment of a segmented control. */
    val segment: Color,
)

val DarkColors = AezColors(
    isDark = true,
    bg = Color(0xFF000000),
    elev1 = Color(0xFF1C1C1E),
    elev2 = Color(0xFF2C2C2E),
    elev3 = Color(0xFF3A3A3C),
    text = Color(0xFFFFFFFF),
    text2 = Color(0x99EBEBF5),
    text3 = Color(0x52EBEBF5),
    border = Color(0x80545458),
    borderStrong = Color(0xD9545458),
    glass = Color(0xB8161618),
    link = Color(0xFF0A84FF),
    danger = Color(0xFFFF453A),
    success = Color(0xFF30D158),
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    bubbleOther = Color(0xFF26252A),
    accent = Color(0xFF0A84FF),
    bubbleMe = Color(0xFF0A84FF),
    fill = Color(0x5C787880),
    fill3 = Color(0x3D767680),
    grouped = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    segment = Color(0xFF636366),
)

val LightColors = AezColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    elev1 = Color(0xFFF2F2F7),
    elev2 = Color(0xFFE5E5EA),
    elev3 = Color(0xFFD1D1D6),
    text = Color(0xFF000000),
    text2 = Color(0x993C3C43),
    text3 = Color(0x4D3C3C43),
    border = Color(0x2E3C3C43),
    borderStrong = Color(0x5C3C3C43),
    glass = Color(0xCCF9F9F9),
    link = Color(0xFF007AFF),
    danger = Color(0xFFFF3B30),
    success = Color(0xFF34C759),
    primary = Color(0xFF007AFF),
    onPrimary = Color(0xFFFFFFFF),
    bubbleOther = Color(0xFFE9E9EB),
    accent = Color(0xFF007AFF),
    bubbleMe = Color(0xFF007AFF),
    fill = Color(0x33787880),
    fill3 = Color(0x1F767680),
    grouped = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    segment = Color(0xFFFFFFFF),
)

/** One accent, as in iOS: system blue where the old purple-pink-orange gradient was. Green and red keep their iOS meanings. */
object Brand {
    val accent = Color(0xFF0A84FF)
    val closeFriends = Color(0xFF30D158)
    val live = Color(0xFFFF453A)
    /** The app icon's blue, a touch lighter at the top. */
    val iconColors = listOf(Color(0xFF2B95FF), Color(0xFF0066E0))
    val gradient: Brush get() = Brush.verticalGradient(iconColors)
}

val LocalAez = staticCompositionLocalOf { DarkColors }

object Aez {
    val colors: AezColors
        @Composable @ReadOnlyComposable get() = LocalAez.current
}

/** The wordmark: the system sans, bold and tightly tracked, the way Apple sets its product names. */
val Wordmark = TextStyle(fontWeight = FontWeight.Bold, fontSize = 27.sp, letterSpacing = (-1.1).sp)

private fun typography(c: AezColors) = Typography(
    // iOS: Large Title 34, Title 1 28, Title 2 22, Title 3 20, Headline 17, Body 17 (16 here), Subhead 15, Footnote 13.
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = c.text),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = c.text),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = c.text),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = c.text),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = c.text),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = c.text),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp, color = c.text),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.15).sp, color = c.text),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = c.text2),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text2),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.text3),
)

val AezShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
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
            primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text, surfaceVariant = c.elev2, onSurfaceVariant = c.text2, surfaceContainer = c.elev1,
            surfaceContainerHigh = c.elev2, surfaceContainerHighest = c.elev3, surfaceContainerLow = c.elev1, outline = c.borderStrong,
            outlineVariant = c.border, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text, surfaceVariant = c.elev2, onSurfaceVariant = c.text2, surfaceContainer = c.elev1,
            surfaceContainerHigh = c.elev2, surfaceContainerHighest = c.elev3, surfaceContainerLow = c.elev1, outline = c.borderStrong,
            outlineVariant = c.border, error = c.danger,
        )
    }
    CompositionLocalProvider(LocalAez provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography(c), shapes = AezShapes, content = content)
    }
}
