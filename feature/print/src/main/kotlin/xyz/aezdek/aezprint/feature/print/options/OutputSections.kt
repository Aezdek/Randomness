@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.aezdek.aezprint.feature.print.options

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FlipToBack
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.component.FieldErrorText
import xyz.aezdek.aezprint.designsystem.component.OptionSection
import xyz.aezdek.aezprint.designsystem.component.SwitchRow
import xyz.aezdek.aezprint.designsystem.component.VisualChoice
import xyz.aezdek.aezprint.designsystem.component.VisualChoiceRow
import xyz.aezdek.aezprint.designsystem.glyph.DuplexGlyph
import xyz.aezdek.aezprint.designsystem.glyph.OrientationGlyph
import xyz.aezdek.aezprint.domain.model.ContentMode
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.DuplexSupport
import xyz.aezdek.aezprint.domain.model.Orientation
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.domain.validation.OptionField

@Composable
internal fun OrientationSection(options: PrintOptions, errors: Map<String, String>, onChange: ((PrintOptions) -> PrintOptions) -> Unit) {
    OptionSection(title = "Orientation", icon = Icons.Rounded.ScreenRotation) {
        VisualChoiceRow(errorMessage = errors[OptionField.ORIENTATION]) {
            Orientation.entries.forEach { orientation ->
                VisualChoice(
                    label = when (orientation) {
                        Orientation.AUTO -> "Auto"
                        Orientation.PORTRAIT -> "Portrait"
                        Orientation.LANDSCAPE -> "Landscape"
                    },
                    selected = options.orientation == orientation,
                    onClick = { onChange { it.copy(orientation = orientation) } },
                    description = if (orientation == Orientation.AUTO) "Match each page" else null,
                ) { selected -> OrientationGlyph(orientation, selected, size = 48.dp) }
            }
        }
    }
}

@Composable
internal fun PaperSection(state: OptionsPanelState, onChange: ((PrintOptions) -> PrintOptions) -> Unit) {
    val media = state.printer?.media.orEmpty()
    val byPdf = state.options.sizing.paperByPdfSize && state.options.sizing.mode !in setOf(SizingMode.MULTIPLE, SizingMode.BOOKLET, SizingMode.POSTER)
    OptionSection(title = "Paper size", icon = Icons.Rounded.Description) {
        when {
            media.isEmpty() -> Text(
                "Paper sizes appear once the printer responds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            byPdf -> Text(
                "Chosen from each page's size",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                media.forEach { size ->
                    FilterChip(
                        selected = state.options.media == size.id,
                        onClick = { onChange { it.copy(media = size.id) } },
                        label = {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(size.displayName, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "%.0f × %.0f mm".format(size.widthMm, size.heightMm),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }
        state.errors[OptionField.MEDIA]?.let { FieldErrorText(it) }
    }
}

@Composable
internal fun DuplexSection(state: OptionsPanelState, onChange: ((PrintOptions) -> PrintOptions) -> Unit) {
    val options = state.options
    val support = state.printer?.capabilities?.duplex
    val booklet = options.sizing.mode == SizingMode.BOOKLET
    OptionSection(title = "Two-sided", icon = Icons.Rounded.FlipToBack) {
        if (booklet) {
            Text(
                "Booklets choose the flip edge automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VisualChoiceRow(errorMessage = state.errors[OptionField.DUPLEX]) {
            Duplex.entries.forEach { duplex ->
                VisualChoice(
                    label = when (duplex) {
                        Duplex.OFF -> "One-sided"
                        Duplex.LONG_EDGE -> "Flip on long edge"
                        Duplex.SHORT_EDGE -> "Flip on short edge"
                    },
                    selected = options.duplex == duplex,
                    onClick = { onChange { it.copy(duplex = duplex) } },
                    enabled = !booklet && (duplex == Duplex.OFF || support != DuplexSupport.NONE),
                ) { selected -> DuplexGlyph(duplex, selected) }
            }
        }
        if (support == DuplexSupport.MANUAL && options.isTwoSided) {
            Text(
                "This printer can't flip paper itself. Halfway through, AezPrint will show you how to turn the stack over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ContentSection(options: PrintOptions, errors: Map<String, String>, onChange: ((PrintOptions) -> PrintOptions) -> Unit) {
    OptionSection(title = "Comments & forms", icon = Icons.Rounded.Layers) {
        Column(Modifier.selectableGroup()) {
            ContentMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = options.content == mode, role = Role.RadioButton, onClick = { onChange { it.copy(content = mode) } }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = options.content == mode, onClick = null)
                    Text(
                        when (mode) {
                            ContentMode.DOCUMENT -> "Document"
                            ContentMode.DOCUMENT_AND_MARKUPS -> "Document and markups"
                            ContentMode.DOCUMENT_AND_STAMPS -> "Document and stamps"
                            ContentMode.FORM_FIELDS_ONLY -> "Form fields only"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        errors[OptionField.CONTENT]?.let { FieldErrorText(it) }
    }
}

@Composable
internal fun QualitySection(state: OptionsPanelState, onChange: ((PrintOptions) -> PrintOptions) -> Unit) {
    val options = state.options
    val resolutions = state.printer?.capabilities?.resolutions?.takeIf { it.isNotEmpty() } ?: listOf(600, 1200)
    OptionSection(title = "Quality", icon = Icons.Rounded.Tune) {
        SwitchRow(
            title = "Print as image",
            subtitle = "Helps with PDFs that print incorrectly",
            checked = options.printAsImage,
            onCheckedChange = { value -> onChange { it.copy(printAsImage = value) } },
        )
        state.errors[OptionField.PRINT_AS_IMAGE]?.let { FieldErrorText(it) }
        if (options.printAsImage) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                resolutions.forEachIndexed { index, dpi ->
                    SegmentedButton(
                        selected = options.dpi == dpi,
                        onClick = { onChange { it.copy(dpi = dpi) } },
                        shape = SegmentedButtonDefaults.itemShape(index, resolutions.size),
                    ) { Text("$dpi dpi") }
                }
            }
            state.errors[OptionField.DPI]?.let { FieldErrorText(it) }
        }
        SwitchRow(
            title = "Save toner",
            subtitle = "Lighter output for drafts",
            checked = options.tonerSave,
            onCheckedChange = { value -> onChange { it.copy(tonerSave = value) } },
        )
        state.errors[OptionField.TONER_SAVE]?.let { FieldErrorText(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Rounded.Contrast, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(
                title = "Print in grayscale",
                subtitle = "Always on: this printer is black and white",
                checked = true,
                onCheckedChange = {},
                enabled = false,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
