package xyz.aezdek.daybook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.ContinuousShape
import xyz.aezdek.daybook.ui.theme.Radii

/** Where a row sits in an inset grouped section, which decides its corners and separator. */
enum class CellPosition {
    Only, First, Middle, Last;

    val isLast: Boolean get() = this == Only || this == Last

    companion object {
        fun of(index: Int, count: Int): CellPosition = when {
            count == 1 -> Only
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

fun CellPosition.shape(radius: Dp = Radii.section): Shape = when (this) {
    CellPosition.Only -> ContinuousShape(radius)
    CellPosition.First -> ContinuousShape(radius, radius, 0.dp, 0.dp)
    CellPosition.Middle -> ContinuousShape(0.dp)
    CellPosition.Last -> ContinuousShape(0.dp, 0.dp, radius, radius)
}

/** A hairline along the bottom edge, inset from the leading side so it lines up with the text. */
fun Modifier.bottomSeparator(show: Boolean, color: Color, inset: Dp): Modifier =
    if (!show) this else drawBehind {
        val y = size.height - 0.5f
        val start = inset.toPx()
        val ltr = layoutDirection == LayoutDirection.Ltr
        drawLine(
            color,
            Offset(if (ltr) start else 0f, y),
            Offset(if (ltr) size.width else size.width - start, y),
            strokeWidth = 1f,
        )
    }

/** The cell surface for one row of an inset grouped list. Use inside a LazyColumn item. */
@Composable
fun Cell(
    position: CellPosition,
    modifier: Modifier = Modifier,
    background: Color = Apple.colors.cell,
    separatorInset: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .padding(horizontal = Apple.margin)
            .fillMaxWidth()
            .clip(position.shape())
            .background(background)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .bottomSeparator(!position.isLast, Apple.colors.separator, separatorInset),
    ) { content() }
}

/** A non-lazy inset grouped section, for short forms and settings. */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    background: Color = Apple.colors.cell,
    rows: List<@Composable () -> Unit>,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) SectionHeader(header)
        Column(
            Modifier
                .padding(horizontal = Apple.margin)
                .fillMaxWidth()
                .clip(ContinuousShape(Radii.section))
                .background(background),
        ) {
            rows.forEachIndexed { index, row ->
                Box(Modifier.fillMaxWidth().bottomSeparator(index < rows.lastIndex, Apple.colors.separator, 16.dp)) { row() }
            }
        }
        if (footer != null) SectionFooter(footer)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Apple.margin + 4.dp, end = Apple.margin, top = 20.dp, bottom = 6.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        AppleText(title, Apple.type.headline, Modifier.weight(1f).semantics { heading() })
        trailing?.invoke(this)
    }
}

@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    AppleText(
        text,
        Apple.type.footnote,
        modifier.padding(start = Apple.margin + 4.dp, end = Apple.margin + 4.dp, top = 8.dp),
        color = Apple.colors.secondaryLabel,
    )
}

/** A settings-style row: optional icon, title, trailing value or control. At least 44 tall. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Apple.colors.accent,
    titleColor: Color = Apple.colors.label,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) SymbolIcon(icon, contentDescription = null, tint = iconTint, size = 22.dp)
        AppleText(title, Apple.type.body, Modifier.weight(1f), color = titleColor)
        if (value != null) AppleText(value, Apple.type.body, color = Apple.colors.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        trailing?.invoke(this)
    }
}
