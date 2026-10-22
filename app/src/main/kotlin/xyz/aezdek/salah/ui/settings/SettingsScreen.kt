package xyz.aezdek.salah.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.aezdek.salah.BuildConfig
import xyz.aezdek.salah.core.Backup
import xyz.aezdek.salah.core.Hijri
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.core.Settings
import xyz.aezdek.salah.core.ThemeMode
import xyz.aezdek.salah.core.qada
import xyz.aezdek.salah.core.withSettings
import xyz.aezdek.salah.ui.ScreenTitle
import xyz.aezdek.salah.ui.formatTime
import xyz.aezdek.salah.ui.label
import xyz.aezdek.salah.ui.plural
import java.io.IOException
import java.time.LocalDate

@Composable
fun SettingsScreen(
    data: SalahData,
    today: LocalDate,
    onEdit: ((SalahData) -> SalahData) -> Unit,
    backupText: suspend () -> String,
    restore: suspend (SalahData) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = data.settings
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<SalahData?>(null) }

    fun change(transform: (Settings) -> Settings) = onEdit { it.withSettings(transform) }
    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) change { it.copy(reminderEnabled = true) } else message("Allow notifications for Salah in Android settings to get the reminder.")
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val result = try {
                    val text = backupText()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException("The file couldn't be opened.")
                        stream.use { it.write(text.encodeToByteArray()) }
                    }
                    "Backup saved."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Couldn't save the backup: ${e.message}"
                }
                snackbar.showSnackbar(result)
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("The file couldn't be opened.")
                        stream.use { it.readBytes().decodeToString() }
                    }
                    pendingRestore = Backup.decode(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    snackbar.showSnackbar(e.message ?: "This isn't a Salah backup file.")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Couldn't read the file: ${e.message}")
                }
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        SectionTitle("Reminder")
        Item(
            headline = "Daily reminder",
            supporting = "A notification if today's prayers aren't all logged",
            trailing = { Switch(checked = settings.reminderEnabled, onCheckedChange = null) },
            modifier = Modifier
                .toggleable(value = settings.reminderEnabled, role = Role.Switch) { on ->
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    when {
                        !on -> change { it.copy(reminderEnabled = false) }
                        needsPermission -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> change { it.copy(reminderEnabled = true) }
                    }
                }
                .testTag("reminder"),
        )
        Item(
            headline = "Time",
            supporting = formatTime(settings.reminderMinute, is24Hour),
            modifier = Modifier.clickable(enabled = settings.reminderEnabled) { pickingTime = true },
            enabled = settings.reminderEnabled,
        )

        SectionTitle("Hijri date")
        Item(
            headline = Hijri.format(today, settings.hijriOffset) ?: "Not available on this phone",
            supporting = "Move it a day or two if the new moon was sighted on a different day where you are",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { change { it.copy(hijriOffset = it.hijriOffset - 1) } }, enabled = settings.hijriOffset > -2) {
                        Icon(Icons.Filled.Remove, contentDescription = "A day earlier")
                    }
                    Text(
                        if (settings.hijriOffset > 0) "+${settings.hijriOffset}" else "${settings.hijriOffset}",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    IconButton(onClick = { change { it.copy(hijriOffset = it.hijriOffset + 1) } }, enabled = settings.hijriOffset < 2) {
                        Icon(Icons.Filled.Add, contentDescription = "A day later")
                    }
                }
            },
        )

        SectionTitle("Appearance")
        ThemeChooser(settings.theme, onChoose = { mode -> change { it.copy(theme = mode) } })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Item(
                headline = "Wallpaper colors",
                supporting = "Use colors from your wallpaper instead of green and gold",
                trailing = { Switch(checked = settings.wallpaperColors, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = settings.wallpaperColors, role = Role.Switch) { on ->
                    change { it.copy(wallpaperColors = on) }
                },
            )
        }

        SectionTitle("Backup")
        Item(
            headline = "Export backup",
            supporting = "Save your log and qada counts to a file",
            leading = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
            modifier = Modifier.clickable { exporter.launch("salah-backup-$today.json") },
        )
        Item(
            headline = "Restore backup",
            supporting = "Replace everything here with a backup file",
            leading = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
            modifier = Modifier.clickable { importer.launch(arrayOf("*/*")) },
        )
        Text(
            "Android's own backup also saves your log when backup is turned on in your phone's settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionTitle("About")
        Item(headline = "Salah", supporting = "Version ${BuildConfig.VERSION_NAME}")
        Spacer(Modifier.height(16.dp))
    }

    if (pickingTime) {
        ReminderTimeDialog(
            minuteOfDay = settings.reminderMinute,
            is24Hour = is24Hour,
            onDismiss = { pickingTime = false },
            onSet = { minute ->
                pickingTime = false
                change { it.copy(reminderMinute = minute) }
            },
        )
    }

    pendingRestore?.let { incoming ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "It has ${plural(incoming.log.size, "logged day")} and ${plural(incoming.qada(today).remaining, "qada prayer")} " +
                        "left to make up. Everything in Salah now will be replaced.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        restore(incoming)
                        snackbar.showSnackbar("Backup restored.")
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(
    headline: String,
    supporting: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        if (trailing != null) trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChooser(selected: ThemeMode, onChoose: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onChoose(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
            ) { Text(mode.label) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(minuteOfDay: Int, is24Hour: Boolean, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minuteOfDay / 60, initialMinute = minuteOfDay % 60, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder time") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onSet(state.hour * 60 + state.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
