package xyz.aezdek.daybook.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import xyz.aezdek.daybook.core.Subject

/**
 * Semantic colours, modelled on Apple's system palette. Values that iOS ships below 4.5:1 for small text
 * (secondary label, the accent and the subject colours) are taken from the Increase Contrast variants or
 * darkened until they pass on both the grouped background and the cell colour, in both appearances.
 */
@Immutable
data class AppleColors(
    val isDark: Boolean,
    val label: Color,
    val secondaryLabel: Color,
    /** Placeholders, disabled controls and decoration only: below 4.5:1 by design. */
    val tertiaryLabel: Color,
    val background: Color,
    val cell: Color,
    val sheetBackground: Color,
    val sheetCell: Color,
    val separator: Color,
    val fill: Color,
    val secondaryFill: Color,
    val tertiaryFill: Color,
    /** Interactive text and icons. The one accent colour. */
    val accent: Color,
    /** Behind white text on a filled button. */
    val accentFill: Color,
    val onAccent: Color,
    val red: Color,
    val redFill: Color,
    val greenFill: Color,
    val orangeFill: Color,
    val gray: Color,
    val cs: Color,
    val maths: Color,
    val physics: Color,
    val glassTint: Color,
    val glassProminentTint: Color,
    val glassRimTop: Color,
    val glassRimBottom: Color,
    val shadow: Color,
    val scrim: Color,
    val segmentThumb: Color,
    val heat: List<Color>,
)

val LightColors = AppleColors(
    isDark = false,
    label = Color(0xFF000000),
    secondaryLabel = Color(0xC73C3C43),
    tertiaryLabel = Color(0x993C3C43),
    background = Color(0xFFF2F2F7),
    cell = Color(0xFFFFFFFF),
    sheetBackground = Color(0xFFF2F2F7),
    sheetCell = Color(0xFFFFFFFF),
    separator = Color(0x4A3C3C43),
    fill = Color(0x33787880),
    secondaryFill = Color(0x29787880),
    tertiaryFill = Color(0x1F767680),
    accent = Color(0xFF0066DB),
    accentFill = Color(0xFF0066DB),
    onAccent = Color(0xFFFFFFFF),
    red = Color(0xFFD70015),
    redFill = Color(0xFFD70015),
    greenFill = Color(0xFF248A3D),
    orangeFill = Color(0xFFC93400),
    gray = Color(0xFF6C6C70),
    cs = Color(0xFF00788D),
    maths = Color(0xFF8944AB),
    physics = Color(0xFFC93400),
    glassTint = Color(0xA6FFFFFF),
    glassProminentTint = Color(0xD90066DB),
    glassRimTop = Color(0xD9FFFFFF),
    glassRimBottom = Color(0x14000000),
    shadow = Color(0x26000000),
    scrim = Color(0x33000000),
    segmentThumb = Color(0xFFFFFFFF),
    heat = listOf(Color(0xFFE3E3E8), Color(0xFFBFE3CB), Color(0xFF7CC795), Color(0xFF34A45A), Color(0xFF1C7A3A)),
)

val DarkColors = AppleColors(
    isDark = true,
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x66EBEBF5),
    background = Color(0xFF000000),
    cell = Color(0xFF1C1C1E),
    sheetBackground = Color(0xFF1C1C1E),
    sheetCell = Color(0xFF2C2C2E),
    separator = Color(0x99545458),
    fill = Color(0x5C787880),
    secondaryFill = Color(0x52787880),
    tertiaryFill = Color(0x3D767680),
    accent = Color(0xFF409CFF),
    accentFill = Color(0xFF0A84FF),
    onAccent = Color(0xFFFFFFFF),
    red = Color(0xFFFF6961),
    redFill = Color(0xFFD70015),
    greenFill = Color(0xFF1E7B34),
    orangeFill = Color(0xFFB85600),
    gray = Color(0xFF98989D),
    cs = Color(0xFF40C8E0),
    maths = Color(0xFFCC73F8),
    physics = Color(0xFFFF9F0A),
    glassTint = Color(0x8C2C2C2E),
    glassProminentTint = Color(0xD90A84FF),
    glassRimTop = Color(0x2EFFFFFF),
    glassRimBottom = Color(0x33000000),
    shadow = Color(0x80000000),
    scrim = Color(0x7A000000),
    segmentThumb = Color(0xFF636366),
    heat = listOf(Color(0xFF2C2C2E), Color(0xFF16432A), Color(0xFF1C6B3A), Color(0xFF2A9D52), Color(0xFF34C759)),
)

fun AppleColors.subject(subject: Subject): Color = when (subject) {
    Subject.CS -> cs
    Subject.MATHS -> maths
    Subject.PHYSICS -> physics
}
