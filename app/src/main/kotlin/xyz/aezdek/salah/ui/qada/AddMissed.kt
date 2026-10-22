package xyz.aezdek.salah.ui.qada

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.aezdek.salah.core.PeriodUnit
import xyz.aezdek.salah.core.Prayer
import xyz.aezdek.salah.core.daysIn
import xyz.aezdek.salah.ui.grouped
import xyz.aezdek.salah.ui.plural
import java.time.LocalDate

/**
 * Adds old missed prayers, either as a length of time (that many days of all five prayers) or as a count per prayer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMissedSheet(today: LocalDate, onDismiss: () -> Unit, onAdd: (Map<Prayer, Int>) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var perPrayer by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(PeriodUnit.YEARS) }
    var counts by rememberSaveable { mutableStateOf(List(Prayer.entries.size) { "" }) }

    val days = daysIn(amount.toIntOrNull() ?: 0, unit, today)
    val toAdd: Map<Prayer, Int> = if (perPrayer) {
        Prayer.entries.associateWith { counts[it.ordinal].toIntOrNull() ?: 0 }
    } else {
        Prayer.entries.associateWith { days }
    }
    val total = toAdd.values.sum()

    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add old missed prayers", style = MaterialTheme.typography.titleLarge)
            Text(
                "For prayers you missed before you started logging. You can change these later with Edit counts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !perPrayer,
                    onClick = { perPrayer = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("By time") }
                SegmentedButton(
                    selected = perPrayer,
                    onClick = { perPrayer = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("mode-per-prayer"),
                ) { Text("Per prayer") }
            }
            if (!perPrayer) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(4) },
                    label = { Text("How many ${unit.label.lowercase()}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("amount"),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PeriodUnit.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = unit == option,
                            onClick = { unit = option },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = PeriodUnit.entries.size),
                            icon = {},
                            modifier = Modifier.testTag("unit-${option.name}"),
                        ) { Text(option.label, maxLines = 1) }
                    }
                }
                Text(
                    if (days > 0) "That's ${plural(days, "day")}: ${days.grouped()} of each prayer, ${(days * Prayer.entries.size).grouped()} in all."
                    else "Enter roughly how long you didn't pray.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Prayer.entries.forEach { prayer ->
                    OutlinedTextField(
                        value = counts[prayer.ordinal],
                        onValueChange = { text ->
                            counts = counts.toMutableList().also { it[prayer.ordinal] = text.filter(Char::isDigit).take(6) }
                        },
                        label = { Text(prayer.title) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("count-${prayer.name}"),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { close(onDismiss) }) { Text("Cancel") }
                Button(onClick = { close { onAdd(toAdd) } }, enabled = total > 0, modifier = Modifier.testTag("add-confirm")) {
                    Text(if (total > 0) "Add ${total.grouped()}" else "Add")
                }
            }
        }
    }
}

/** Sets the old missed prayer counts directly, to fix a mistake. Prayers missed in the log aren't changed here. */
@Composable
fun EditOwedDialog(owed: Map<Prayer, Int>, onDismiss: () -> Unit, onSave: (Map<Prayer, Int>) -> Unit) {
    var values by rememberSaveable { mutableStateOf(Prayer.entries.map { (owed[it] ?: 0).toString() }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Old missed prayers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The prayers you added yourself. Prayers marked missed in your log are counted on their own.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Prayer.entries.forEach { prayer ->
                    OutlinedTextField(
                        value = values[prayer.ordinal],
                        onValueChange = { text ->
                            values = values.toMutableList().also { it[prayer.ordinal] = text.filter(Char::isDigit).take(6) }
                        },
                        label = { Text(prayer.title) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Prayer.entries.associateWith { values[it.ordinal].toIntOrNull() ?: 0 }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
