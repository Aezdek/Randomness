@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.aezdek.aezprint.feature.print.options

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.component.FieldErrorText
import xyz.aezdek.aezprint.designsystem.component.OptionSection
import xyz.aezdek.aezprint.designsystem.component.Stepper
import xyz.aezdek.aezprint.designsystem.component.SwitchRow
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PageSubset
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.validation.OptionField

@Composable
internal fun CopiesSection(options: PrintOptions, errors: Map<String, String>, onChange: ((PrintOptions) -> PrintOptions) -> Unit) {
    OptionSection(title = "Copies", icon = Icons.Rounded.ContentCopy, trailing = {
        Stepper(
            value = options.copies,
            onValueChange = { copies -> onChange { it.copy(copies = copies) } },
            range = PrintOptions.MIN_COPIES..PrintOptions.MAX_COPIES,
            label = "copies",
        )
    }) {
        errors[OptionField.COPIES]?.let { FieldErrorText(it) }
        AnimatedVisibility(options.copies > 1) {
            SwitchRow(
                title = "Collate",
                subtitle = if (options.collate) "1 2 3 · 1 2 3" else "1 1 · 2 2 · 3 3",
                checked = options.collate,
                onCheckedChange = { collate -> onChange { it.copy(collate = collate) } },
            )
        }
    }
}

@Composable
internal fun PagesSection(state: OptionsPanelState, actions: OptionsPanelActions) {
    val options = state.options
    val selection = options.pages
    var customText by rememberSaveable { mutableStateOf((selection as? PageSelection.Custom)?.expression ?: "") }
    LaunchedEffect(selection) {
        if (selection is PageSelection.Custom && selection.expression != customText) customText = selection.expression
    }

    OptionSection(title = "Pages to print", icon = Icons.AutoMirrored.Rounded.LibraryBooks) {
        val choices = listOf("All", "Current", "Custom")
        val selectedIndex = when (selection) {
            PageSelection.All -> 0
            is PageSelection.Current -> 1
            is PageSelection.Custom -> 2
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = {
                        when (index) {
                            0 -> actions.onPageSelection(PageSelection.All)
                            1 -> actions.onPageSelection(PageSelection.Current(state.currentPage))
                            else -> actions.onPageSelection(PageSelection.Custom(customText.ifBlank { "1-${state.pageCount}" }))
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                ) { Text(label) }
            }
        }
        when (selection) {
            is PageSelection.Current -> Text(
                "Page ${selection.page} of ${state.pageCount}. Tap a page in the preview to change it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is PageSelection.Custom -> OutlinedTextField(
                value = customText,
                onValueChange = { text ->
                    customText = text
                    actions.onPageSelection(PageSelection.Custom(text))
                },
                label = { Text("Pages") },
                placeholder = { Text("e.g. 1-3, 7, 10-12") },
                supportingText = { Text(state.errors[OptionField.PAGE_RANGE] ?: "${state.pageCount} pages in this document") },
                isError = state.errors.containsKey(OptionField.PAGE_RANGE),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("pageRangeField"),
            )
            PageSelection.All -> Unit
        }
        if (selection !is PageSelection.Custom) state.errors[OptionField.PAGE_RANGE]?.let { FieldErrorText(it) }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Odd or even pages", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val subsets = PageSubset.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                subsets.forEachIndexed { index, subset ->
                    SegmentedButton(
                        selected = options.subset == subset,
                        onClick = { actions.onChange { it.copy(subset = subset) } },
                        shape = SegmentedButtonDefaults.itemShape(index, subsets.size),
                        enabled = selection !is PageSelection.Current,
                    ) {
                        Text(
                            when (subset) {
                                PageSubset.ALL -> "All pages"
                                PageSubset.ODD -> "Odd only"
                                PageSubset.EVEN -> "Even only"
                            },
                        )
                    }
                }
            }
            state.errors[OptionField.SUBSET]?.let { FieldErrorText(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwitchRow(
                title = "Reverse pages",
                subtitle = "Print the last page first",
                checked = options.reverse,
                onCheckedChange = { reverse -> actions.onChange { it.copy(reverse = reverse) } },
                enabled = selection !is PageSelection.Current,
            )
        }
    }
}
