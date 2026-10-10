package xyz.aezdek.aezprint.feature.print.options

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.component.OptionSection

@Composable
internal fun PresetSection(state: OptionsPanelState, actions: OptionsPanelActions) {
    var saving by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }

    OptionSection(
        title = "Presets",
        icon = Icons.Rounded.Bookmarks,
        trailing = {
            if (state.presets.presets.isNotEmpty()) {
                IconButton(onClick = { managing = true }) { Icon(Icons.Rounded.Tune, contentDescription = "Manage presets") }
            }
        },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.presets.presets.forEach { preset ->
                val selected = preset.id == state.activePresetId
                FilterChip(
                    selected = selected,
                    onClick = { actions.onApplyPreset(preset.id) },
                    label = { Text(preset.name) },
                    leadingIcon = when {
                        selected -> ({ Icon(Icons.Rounded.Check, null) })
                        preset.id == state.presets.defaultPresetId -> ({ Icon(Icons.Rounded.Star, contentDescription = "Default") })
                        else -> null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            AssistChip(
                onClick = { saving = true },
                label = { Text("Save current") },
                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }

    if (saving) SavePresetDialog(onDismiss = { saving = false }, onSave = { name, default -> actions.onSavePreset(name, default); saving = false })
    if (managing) ManagePresetsDialog(state, actions, onDismiss = { managing = false })
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var makeDefault by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Bookmarks, null) },
        title = { Text("Save preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(makeDefault, role = Role.Checkbox, onValueChange = { makeDefault = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = makeDefault, onCheckedChange = null)
                    Text("Use for every new print", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, makeDefault) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ManagePresetsDialog(state: OptionsPanelState, actions: OptionsPanelActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presets") },
        text = {
            Column {
                state.presets.presets.forEach { preset ->
                    val isDefault = preset.id == state.presets.defaultPresetId
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = if (isDefault) ({ Text("Default for new prints") }) else null,
                        trailingContent = {
                            Row {
                                IconButton(onClick = { actions.onSetDefaultPreset(if (isDefault) null else preset.id) }) {
                                    Icon(
                                        if (isDefault) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        contentDescription = if (isDefault) "Stop using ${preset.name} as default" else "Use ${preset.name} as default",
                                        tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { actions.onDeletePreset(preset.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete ${preset.name}")
                                }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
