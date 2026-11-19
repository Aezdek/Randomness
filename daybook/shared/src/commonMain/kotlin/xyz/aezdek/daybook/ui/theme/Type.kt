package xyz.aezdek.daybook.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import xyz.aezdek.daybook.resources.Res
import xyz.aezdek.daybook.resources.inter_bold
import xyz.aezdek.daybook.resources.inter_display_bold
import xyz.aezdek.daybook.resources.inter_display_medium
import xyz.aezdek.daybook.resources.inter_display_regular
import xyz.aezdek.daybook.resources.inter_display_semibold
import xyz.aezdek.daybook.resources.inter_medium
import xyz.aezdek.daybook.resources.inter_regular
import xyz.aezdek.daybook.resources.inter_semibold
import kotlin.math.exp

/**
 * Apple's text styles at the default Dynamic Type size, in sp so they follow the system font scale.
 *
 * SF Pro may not be shipped outside Apple platforms, so Inter stands in, with Inter Display from 20 sp
 * up, the same split as SF Pro Text and SF Pro Display. Tracking follows Inter's own size-dependent
 * curve, which plays the part of the SF tracking table.
 */
@Immutable
class AppleTypography(private val text: FontFamily, private val display: FontFamily) {
    val largeTitle = style(34f, 41f, FontWeight.Bold)
    val title1 = style(28f, 34f, FontWeight.Normal)
    val title1Bold = style(28f, 34f, FontWeight.Bold)
    val title2 = style(22f, 28f, FontWeight.Normal)
    val title2Bold = style(22f, 28f, FontWeight.Bold)
    val title3 = style(20f, 25f, FontWeight.Normal)
    val title3Semibold = style(20f, 25f, FontWeight.SemiBold)
    val headline = style(17f, 22f, FontWeight.SemiBold)
    val body = style(17f, 22f, FontWeight.Normal)
    val bodySemibold = style(17f, 22f, FontWeight.SemiBold)
    val callout = style(16f, 21f, FontWeight.Normal)
    val subhead = style(15f, 20f, FontWeight.Normal)
    val subheadSemibold = style(15f, 20f, FontWeight.SemiBold)
    val footnote = style(13f, 18f, FontWeight.Normal)
    val footnoteSemibold = style(13f, 18f, FontWeight.SemiBold)
    val caption1 = style(12f, 16f, FontWeight.Normal)
    val caption1Semibold = style(12f, 16f, FontWeight.SemiBold)
    val caption2 = style(11f, 13f, FontWeight.Normal)
    val caption2Semibold = style(11f, 13f, FontWeight.SemiBold)

    /** Big numerals, such as the streak and the exam countdown. */
    val numeral = style(48f, 52f, FontWeight.Bold)

    private fun style(size: Float, leading: Float, weight: FontWeight) = TextStyle(
        fontFamily = if (size >= 20f) display else text,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = leading.sp,
        letterSpacing = (size * interTracking(size)).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

/** Inter's recommended tracking in em for a given size (its "dynamic metrics"). */
private fun interTracking(size: Float): Float = -0.0223f + 0.185f * exp(-0.1745f * size)

@Composable
fun rememberAppleTypography(): AppleTypography {
    val text = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
    val display = FontFamily(
        Font(Res.font.inter_display_regular, FontWeight.Normal),
        Font(Res.font.inter_display_medium, FontWeight.Medium),
        Font(Res.font.inter_display_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_display_bold, FontWeight.Bold),
    )
    return remember(text, display) { AppleTypography(text, display) }
}
