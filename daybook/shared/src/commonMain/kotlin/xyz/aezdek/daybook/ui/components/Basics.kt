package xyz.aezdek.daybook.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.GlassVariant
import xyz.aezdek.daybook.ui.theme.Motion
import xyz.aezdek.daybook.ui.theme.glass

/** Text in one of the system styles. Wraps rather than truncating unless [maxLines] says otherwise. */
@Composable
fun AppleText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Apple.colors.label,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    decoration: TextDecoration? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign ?: TextAlign.Unspecified, textDecoration = decoration),
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

/** An icon at text size. [contentDescription] is required unless a visible label sits next to it. */
@Composable
fun SymbolIcon(
    symbol: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Apple.colors.label,
    size: Dp = 22.dp,
) {
    Image(
        painter = rememberVectorPainter(symbol),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** Dims to 40% while pressed, the way iOS text and icon buttons do. */
@Composable
private fun Modifier.pressDim(source: MutableInteractionSource, enabled: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) 0.4f else 1f, Motion.snappy(Apple.reduceMotion))
    return graphicsLayer { this.alpha = alpha }
}

/** Shrinks slightly while pressed: used on glass and filled buttons, which have a shape to push. */
@Composable
private fun Modifier.pressScale(source: MutableInteractionSource, enabled: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    val reduce = Apple.reduceMotion
    val scale by animateFloatAsState(if (pressed && enabled && !reduce) 0.94f else 1f, Motion.snappy(reduce))
    val alpha by animateFloatAsState(if (pressed && enabled && reduce) 0.6f else 1f, Motion.snappy(reduce))
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/** A plain text button in the accent colour, at least 44 × 44. */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TextStyle = Apple.type.body,
    color: Color = Apple.colors.accent,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pressDim(source, enabled),
        contentAlignment = Alignment.Center,
    ) {
        AppleText(text, style, color = if (enabled) color else Apple.colors.tertiaryLabel)
    }
}

/** An icon-only button with no chrome. The label is read by screen readers. */
@Composable
fun IconButton(
    symbol: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Apple.colors.accent,
    enabled: Boolean = true,
    size: Dp = 22.dp,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .pressDim(source, enabled),
        contentAlignment = Alignment.Center,
    ) {
        SymbolIcon(symbol, contentDescription = null, tint = if (enabled) tint else Apple.colors.tertiaryLabel, size = size)
    }
}

/** A 44 pt glass circle holding one icon, for the floating toolbar. */
@Composable
fun GlassIconButton(
    symbol: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: GlassVariant = GlassVariant.Regular,
    size: Dp = 44.dp,
) {
    val source = remember { MutableInteractionSource() }
    val tint = if (variant == GlassVariant.Prominent) Apple.colors.onAccent else Apple.colors.label
    Box(
        modifier = modifier
            .pressScale(source, true)
            .size(size)
            .glass(CircleShape, variant)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SymbolIcon(symbol, contentDescription = null, tint = tint, size = if (size > 48.dp) 26.dp else 20.dp)
    }
}

/** A glass capsule with an optional leading icon and a short label. */
@Composable
fun GlassCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: ImageVector? = null,
    symbolTint: Color? = null,
    variant: GlassVariant = GlassVariant.Regular,
    contentDescription: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val fg = if (variant == GlassVariant.Prominent) Apple.colors.onAccent else Apple.colors.label
    Row(
        modifier = modifier
            .pressScale(source, true)
            .height(44.dp)
            .glass(RoundedCornerShape(50), variant)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (symbol != null) SymbolIcon(symbol, contentDescription = null, tint = symbolTint ?: fg, size = 18.dp)
        AppleText(text, Apple.type.subheadSemibold, color = fg, maxLines = 1)
    }
}

/** The one filled button on a screen. */
@Composable
fun ProminentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val colors = Apple.colors
    Box(
        modifier = modifier
            .pressScale(source, enabled)
            .defaultMinSize(minHeight = 50.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (enabled) colors.accentFill else colors.fill)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppleText(text, Apple.type.headline, color = if (enabled) colors.onAccent else colors.tertiaryLabel, textAlign = TextAlign.Center)
    }
}

/** A tinted (not filled) button: the secondary action on a screen that already has a prominent one. */
@Composable
fun TintedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, symbol: ImageVector? = null) {
    val source = remember { MutableInteractionSource() }
    val colors = Apple.colors
    Row(
        modifier = modifier
            .pressScale(source, true)
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.accent.copy(alpha = if (colors.isDark) 0.22f else 0.12f))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (symbol != null) SymbolIcon(symbol, null, tint = colors.accent, size = 18.dp)
        AppleText(text, Apple.type.subheadSemibold, color = colors.accent)
    }
}

/** A row of controls with standard spacing. */
@Composable
fun ToolbarRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
