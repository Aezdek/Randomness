package xyz.aezdek.daybook.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.Motion
import xyz.aezdek.daybook.ui.theme.Symbols
import kotlinx.coroutines.launch

/**
 * The round completion button from Reminders: a ring in the subject colour that fills with a checkmark.
 * Done is shown by the fill and the checkmark, not by colour alone. The hit area is 44 × 44.
 */
@Composable
fun CheckCircle(
    checked: Boolean,
    color: Color,
    label: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val haptics = LocalHapticFeedback.current
    val reduce = Apple.reduceMotion
    val scope = rememberCoroutineScope()
    val fill by animateFloatAsState(if (checked) 1f else 0f, Motion.snappy(reduce))
    val scale = remember { Animatable(1f) }
    Box(
        modifier = modifier
            .size(44.dp)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onValueChange = { nowChecked ->
                    haptics.performHapticFeedback(if (nowChecked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    if (nowChecked && !reduce) {
                        scope.launch {
                            scale.animateTo(1.18f, Motion.snappy(false))
                            scale.animateTo(1f, Motion.snappy(false))
                        }
                    }
                    onToggle(nowChecked)
                },
            )
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "Done" else "Not done"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(CircleShape)
                .background(color.copy(alpha = fill))
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (fill > 0.05f) {
                SymbolIcon(Symbols.checkmark, null, tint = Apple.colors.onAccent.copy(alpha = fill), size = size * 0.62f)
            }
        }
    }
}

/**
 * A segmented control: a recessed capsule track with a raised thumb that springs between segments.
 * Each segment is a radio button for assistive tech.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
) {
    val colors = Apple.colors
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.tertiaryFill)
            .padding(3.dp)
            .semantics { contentDescription = label }
            .selectableGroup(),
    ) {
        val segment = maxWidth / options.size
        val thumbOffset by animateDpAsState(segment * selected, Motion.standard(Apple.reduceMotion))
        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segment)
                .height(34.dp)
                .shadow(3.dp, RoundedCornerShape(50), ambientColor = colors.shadow, spotColor = colors.shadow)
                .clip(RoundedCornerShape(50))
                .background(colors.segmentThumb),
        )
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                val textColor by animateColorAsState(if (isSelected) colors.label else colors.secondaryLabel)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.RadioButton,
                            onClick = {
                                if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(index)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AppleText(option, if (isSelected) Apple.type.subheadSemibold else Apple.type.subhead, color = textColor, maxLines = 1)
                }
            }
        }
    }
}

/** A text field styled for an inset grouped row: placeholder, clear button, optional secure entry. */
@Composable
fun FieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    secure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    singleLine: Boolean = true,
    style: TextStyle = Apple.type.body,
) {
    val colors = Apple.colors
    var revealed by remember { mutableStateOf(false) }
    val stacked = label != null && androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.35f
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (label != null && !stacked) {
            AppleText(label, Apple.type.body, Modifier.width(104.dp), maxLines = 2)
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (label != null && stacked) AppleText(label, Apple.type.footnote, color = colors.secondaryLabel)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics { if (label != null) contentDescription = label },
                textStyle = style.copy(color = colors.label),
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (secure && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = capitalization,
                    autoCorrectEnabled = !secure && keyboardType == KeyboardType.Text,
                    keyboardType = if (secure) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) AppleText(placeholder, style, color = colors.tertiaryLabel, maxLines = 1)
                        inner()
                    }
                },
            )
        }
        if (secure && value.isNotEmpty()) {
            IconButton(
                if (revealed) Symbols.eyeSlash else Symbols.eye,
                label = if (revealed) "Hide" else "Show",
                onClick = { revealed = !revealed },
                tint = colors.secondaryLabel,
                size = 20.dp,
            )
        } else if (value.isNotEmpty()) {
            IconButton(Symbols.xmark, label = "Clear", onClick = { onValueChange("") }, tint = colors.tertiaryLabel, size = 14.dp)
        }
    }
}

/** A thin capsule bar that fills from the leading edge. */
@Composable
fun CapsuleProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.standard(Apple.reduceMotion))
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Apple.colors.tertiaryFill)) {
        if (animated > 0f) {
            Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
    }
}

/** A labelled pill showing state with text and an icon, never colour alone. */
@Composable
fun StatusLabel(text: String, symbol: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SymbolIcon(symbol, null, tint = tint, size = 18.dp)
        AppleText(text, Apple.type.footnote, color = Apple.colors.secondaryLabel, textAlign = TextAlign.Start)
    }
}
