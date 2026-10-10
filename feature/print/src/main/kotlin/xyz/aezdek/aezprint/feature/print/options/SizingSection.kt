package xyz.aezdek.aezprint.feature.print.options

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.component.FieldErrorText
import xyz.aezdek.aezprint.designsystem.component.OptionSection
import xyz.aezdek.aezprint.designsystem.component.Stepper
import xyz.aezdek.aezprint.designsystem.component.SwitchRow
import xyz.aezdek.aezprint.designsystem.component.VisualChoice
import xyz.aezdek.aezprint.designsystem.component.VisualChoiceRow
import xyz.aezdek.aezprint.designsystem.glyph.BookletGlyph
import xyz.aezdek.aezprint.designsystem.glyph.BookletSubsetGlyph
import xyz.aezdek.aezprint.designsystem.glyph.LayoutGlyph
import xyz.aezdek.aezprint.designsystem.glyph.SizingGlyph
import xyz.aezdek.aezprint.domain.model.Binding
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.MultipleOptions
import xyz.aezdek.aezprint.domain.model.PageOrder
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.domain.validation.OptionField
import xyz.aezdek.aezprint.domain.validation.OptionsValidator
import kotlin.math.roundToInt

private typealias Edit = ((PrintOptions) -> PrintOptions) -> Unit

@Composable
internal fun SizingSection(state: OptionsPanelState, onChange: Edit) {
    val options = state.options
    val errors = state.errors
    OptionSection(title = "Page sizing & handling", icon = Icons.Rounded.AspectRatio) {
        VisualChoiceRow(errorMessage = errors[OptionField.SIZING_MODE]) {
            SizingMode.entries.forEach { mode ->
                VisualChoice(
                    label = mode.label,
                    selected = options.sizing.mode == mode,
                    onClick = { onChange { OptionEdits.sizingMode(it, mode) } },
                    description = mode.description,
                ) { selected ->
                    when (mode) {
                        SizingMode.MULTIPLE -> LayoutGlyph(2, 2, selected)
                        SizingMode.BOOKLET -> BookletGlyph(Binding.LEFT, selected)
                        SizingMode.POSTER -> LayoutGlyph(2, 2, selected, border = false, order = PageOrder.HORIZONTAL)
                        else -> SizingGlyph(mode, selected, size = 48.dp)
                    }
                }
            }
        }
        AnimatedContent(
            targetState = options.sizing.mode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sizingDetails",
        ) { mode ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    SizingMode.CUSTOM -> PercentSlider(
                        label = "Custom scale",
                        value = options.sizing.scalePercent,
                        range = OptionsValidator.SCALE_RANGE,
                        error = errors[OptionField.SCALE],
                        onValue = { value -> onChange { it.copy(sizing = it.sizing.copy(scalePercent = value)) } },
                    )
                    SizingMode.MULTIPLE -> MultipleDetails(options, errors, onChange)
                    SizingMode.BOOKLET -> BookletDetails(options, errors, onChange)
                    SizingMode.POSTER -> PosterDetails(options, errors, onChange)
                    else -> Unit
                }
                if (mode in setOf(SizingMode.FIT, SizingMode.ACTUAL, SizingMode.SHRINK, SizingMode.CUSTOM)) {
                    SwitchRow(
                        title = "Choose paper by PDF page size",
                        subtitle = "Use the paper that matches each page",
                        checked = options.sizing.paperByPdfSize,
                        onCheckedChange = { value -> onChange { it.copy(sizing = it.sizing.copy(paperByPdfSize = value)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun MultipleDetails(options: PrintOptions, errors: Map<String, String>, onChange: Edit) {
    val multiple = options.multiple
    val isPreset = MultipleOptions.presets.contains(multiple.columns to multiple.rows)
    Text("Pages per sheet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VisualChoiceRow {
        MultipleOptions.presets.forEach { (columns, rows) ->
            VisualChoice(
                label = "${columns * rows}",
                selected = multiple.columns == columns && multiple.rows == rows,
                onClick = { onChange { OptionEdits.gridPreset(it, columns, rows) } },
                description = "$columns columns by $rows rows",
            ) { selected -> LayoutGlyph(columns, rows, selected, order = multiple.order, border = multiple.border) }
        }
        VisualChoice(
            label = "Custom",
            selected = !isPreset,
            onClick = { if (isPreset) onChange { OptionEdits.gridPreset(it, 3, 1) } },
        ) { selected -> LayoutGlyph(3, 1, selected) }
    }
    if (!isPreset) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Columns", style = MaterialTheme.typography.bodyLarge)
            Stepper(multiple.columns, { value -> onChange { it.copy(multiple = it.multiple.copy(columns = value)) } }, 1..MultipleOptions.MAX_GRID, "columns")
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Rows", style = MaterialTheme.typography.bodyLarge)
            Stepper(multiple.rows, { value -> onChange { it.copy(multiple = it.multiple.copy(rows = value)) } }, 1..MultipleOptions.MAX_GRID, "rows")
        }
    }
    (errors[OptionField.MULTIPLE_COLUMNS] ?: errors[OptionField.MULTIPLE_ROWS])?.let { FieldErrorText(it) }

    Text("Page order", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VisualChoiceRow(errorMessage = errors[OptionField.MULTIPLE_ORDER]) {
        PageOrder.entries.forEach { order ->
            VisualChoice(
                label = order.label,
                selected = multiple.order == order,
                onClick = { onChange { it.copy(multiple = it.multiple.copy(order = order)) } },
            ) { selected -> LayoutGlyph(2, 2, selected, order = order, border = false) }
        }
    }
    SwitchRow(
        title = "Print page border",
        checked = multiple.border,
        onCheckedChange = { value -> onChange { it.copy(multiple = it.multiple.copy(border = value)) } },
    )
}

@Composable
private fun BookletDetails(options: PrintOptions, errors: Map<String, String>, onChange: Edit) {
    Text("Booklet subset", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VisualChoiceRow(errorMessage = errors[OptionField.BOOKLET_SUBSET]) {
        BookletSubset.entries.forEach { subset ->
            VisualChoice(
                label = when (subset) {
                    BookletSubset.BOTH -> "Both sides"
                    BookletSubset.FRONT_ONLY -> "Front only"
                    BookletSubset.BACK_ONLY -> "Back only"
                },
                selected = options.booklet.subset == subset,
                onClick = { onChange { OptionEdits.bookletSubset(it, subset) } },
            ) { selected -> BookletSubsetGlyph(subset, selected) }
        }
    }
    Text("Binding", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VisualChoiceRow(errorMessage = errors[OptionField.BOOKLET_BINDING]) {
        Binding.entries.forEach { binding ->
            VisualChoice(
                label = if (binding == Binding.LEFT) "Left" else "Right",
                selected = options.booklet.binding == binding,
                onClick = { onChange { it.copy(booklet = it.booklet.copy(binding = binding)) } },
                description = if (binding == Binding.LEFT) "Spine on the left, reads left to right" else "Spine on the right, reads right to left",
            ) { selected -> BookletGlyph(binding, selected) }
        }
    }
}

@Composable
private fun PosterDetails(options: PrintOptions, errors: Map<String, String>, onChange: Edit) {
    PercentSlider(
        label = "Tile scale",
        value = options.poster.tileScalePercent,
        range = OptionsValidator.POSTER_SCALE_RANGE.first..400,
        error = errors[OptionField.POSTER_SCALE],
        onValue = { value -> onChange { it.copy(poster = it.poster.copy(tileScalePercent = value)) } },
    )
    LabeledSlider(
        label = "Overlap",
        valueText = "${options.poster.overlapMm} mm",
        value = options.poster.overlapMm,
        range = OptionsValidator.OVERLAP_RANGE_MM,
        error = errors[OptionField.POSTER_OVERLAP],
        onValue = { value -> onChange { it.copy(poster = it.poster.copy(overlapMm = value)) } },
    )
    SwitchRow(
        title = "Cut marks",
        checked = options.poster.cutMarks,
        onCheckedChange = { value -> onChange { it.copy(poster = it.poster.copy(cutMarks = value)) } },
    )
    errors[OptionField.POSTER_CUT_MARKS]?.let { FieldErrorText(it) }
    SwitchRow(
        title = "Labels",
        subtitle = "Print the page and tile position on each sheet",
        checked = options.poster.labels,
        onCheckedChange = { value -> onChange { it.copy(poster = it.poster.copy(labels = value)) } },
    )
    errors[OptionField.POSTER_LABELS]?.let { FieldErrorText(it) }
}

@Composable
private fun PercentSlider(label: String, value: Int, range: IntRange, error: String?, onValue: (Int) -> Unit) =
    LabeledSlider(label, "$value%", value, range, error, onValue)

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Int, range: IntRange, error: String?, onValue: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onValue(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueText
            },
        )
        error?.let { FieldErrorText(it) }
    }
}

private val SizingMode.label: String
    get() = when (this) {
        SizingMode.FIT -> "Fit"
        SizingMode.ACTUAL -> "Actual size"
        SizingMode.SHRINK -> "Shrink oversized"
        SizingMode.CUSTOM -> "Custom scale"
        SizingMode.MULTIPLE -> "Multiple"
        SizingMode.BOOKLET -> "Booklet"
        SizingMode.POSTER -> "Poster"
    }

private val SizingMode.description: String
    get() = when (this) {
        SizingMode.FIT -> "Scale each page to fill the printable area"
        SizingMode.ACTUAL -> "Print at 100%, cropping anything outside the paper"
        SizingMode.SHRINK -> "Only scale down pages that are larger than the paper"
        SizingMode.CUSTOM -> "Pick an exact scale"
        SizingMode.MULTIPLE -> "Several pages on each sheet"
        SizingMode.BOOKLET -> "Fold and staple into a booklet"
        SizingMode.POSTER -> "Tile one page across several sheets"
    }

private val PageOrder.label: String
    get() = when (this) {
        PageOrder.HORIZONTAL -> "Horizontal"
        PageOrder.HORIZONTAL_REVERSED -> "Horizontal reversed"
        PageOrder.VERTICAL -> "Vertical"
        PageOrder.VERTICAL_REVERSED -> "Vertical reversed"
    }
