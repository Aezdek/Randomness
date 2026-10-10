@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.aezdek.aezprint.feature.settings

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import xyz.aezdek.aezprint.designsystem.component.OptionSection
import xyz.aezdek.aezprint.designsystem.component.SwitchRow
import xyz.aezdek.aezprint.domain.model.FakeServerConfig
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.ThemeMode
import kotlin.math.roundToLong

@Composable
fun SettingsRoute(onBack: () -> Unit, versionName: String, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    SettingsScreen(state, snackbar, versionName, viewModel, onBack)
}

@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    snackbar: SnackbarHostState,
    versionName: String,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editingCredentials by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scanQr: () -> Unit = {
        scope.launch {
            val scanner = GmsBarcodeScanning.getClient(
                context,
                GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
            val raw = runCatching { scanner.startScan().await().rawValue }.getOrNull()
            if (raw != null) viewModel.applySetupCode(raw) else snackbar.showSnackbar("Scan canceled")
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .widthIn(max = 720.dp),
        ) {
            ServerSection(state, viewModel, onEditCredentials = { editingCredentials = true }, onScan = scanQr)
            HorizontalDivider()
            SecuritySection(state, viewModel, context)
            HorizontalDivider()
            AppearanceSection(state, viewModel)
            HorizontalDivider()
            DeveloperSection(state, viewModel)
            Text(
                "AezPrint $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }

    if (editingCredentials) {
        CredentialsDialog(
            onDismiss = { editingCredentials = false },
            onSave = { id, secret, key ->
                viewModel.saveCredentials(id, secret, key)
                editingCredentials = false
            },
            onScan = {
                editingCredentials = false
                scanQr()
            },
        )
    }
}

@Composable
private fun ServerSection(state: SettingsUiState, viewModel: SettingsViewModel, onEditCredentials: () -> Unit, onScan: () -> Unit) {
    var url by rememberSaveable(state.settings.serverUrl) { mutableStateOf(state.settings.serverUrl) }
    val urlValid = url.trim().startsWith("https://", ignoreCase = true)
    OptionSection(title = "Print server", icon = Icons.Rounded.Dns) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            singleLine = true,
            isError = !urlValid,
            supportingText = { Text(if (urlValid) "Only HTTPS is allowed" else "Must start with https://") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            trailingIcon = {
                if (url.trim() != state.settings.serverUrl) {
                    TextButton(onClick = { viewModel.setServerUrl(url) }, enabled = urlValid) { Text("Save") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Card {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Access credentials", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.credentialsConfigured) "Saved, encrypted on this device · client ${state.clientIdHint}" else "Not set up yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onScan) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Scan QR")
                }
                OutlinedButton(onClick = onEditCredentials) { Text(if (state.credentialsConfigured) "Replace" else "Enter manually") }
                if (state.credentialsConfigured) TextButton(onClick = viewModel::clearCredentials) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::testConnection, enabled = state.connection != ConnectionTest.Running) { Text("Test connection") }
            when (val test = state.connection) {
                ConnectionTest.Running -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                is ConnectionTest.Passed -> ResultText(Icons.Rounded.CheckCircle, "Connected to ${test.printerName}", MaterialTheme.colorScheme.primary)
                is ConnectionTest.Failed -> ResultText(Icons.Rounded.ErrorOutline, test.message, MaterialTheme.colorScheme.error)
                ConnectionTest.Idle -> Unit
            }
        }
        if (state.settings.useFakeServer) {
            Text(
                "The fake server is on, so these settings aren't used yet. Turn it off under Developer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ResultText(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun SecuritySection(state: SettingsUiState, viewModel: SettingsViewModel, context: Context) {
    val available = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    OptionSection(title = "Security", icon = Icons.Rounded.Fingerprint) {
        SwitchRow(
            title = "Lock with biometrics",
            subtitle = if (available) "Ask for your fingerprint, face or screen lock when opening AezPrint" else "Set up a screen lock on this device first",
            checked = state.settings.biometricLock,
            onCheckedChange = viewModel::setBiometricLock,
            enabled = available,
        )
    }
}

@Composable
private fun AppearanceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    OptionSection(title = "Appearance", icon = Icons.Rounded.Palette) {
        val modes = ThemeMode.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.settings.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                    )
                }
            }
        }
        SwitchRow(
            title = "Dynamic color",
            subtitle = "Match your wallpaper",
            checked = state.settings.dynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )
    }
}

@Composable
private fun DeveloperSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    OptionSection(title = "Developer", icon = Icons.Rounded.Science) {
        SwitchRow(
            title = "Use fake print server",
            subtitle = "Simulates the ML-2165 and the v1 API on this phone",
            checked = state.settings.useFakeServer,
            onCheckedChange = viewModel::setUseFakeServer,
        )
        AnimatedVisibility(state.settings.useFakeServer) {
            FakeServerControls(state.fake, viewModel::updateFake)
        }
    }
}

@Composable
private fun FakeServerControls(fake: FakeServerConfig, update: ((FakeServerConfig) -> FakeServerConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Printer state", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrinterStatus.entries.filter { it != PrinterStatus.PRINTING }.forEach { status ->
                FilterChip(
                    selected = fake.printerStatus == status,
                    onClick = { update { it.copy(printerStatus = status) } },
                    label = {
                        Text(
                            when (status) {
                                PrinterStatus.IDLE -> "Ready"
                                PrinterStatus.OFFLINE -> "Offline"
                                PrinterStatus.OUT_OF_PAPER -> "Out of paper"
                                PrinterStatus.JAM -> "Jam"
                                PrinterStatus.ERROR -> "Error"
                                PrinterStatus.PRINTING -> "Printing"
                            },
                        )
                    },
                )
            }
        }
        LabeledSlider("Network latency", "${fake.latencyMs} ms", fake.latencyMs.toFloat(), 0f..3000f) { value ->
            update { it.copy(latencyMs = value.roundToLong()) }
        }
        LabeledSlider("Seconds per sheet", "%.1f s".format(fake.secondsPerSheet), fake.secondsPerSheet.toFloat(), 0.2f..5f) { value ->
            update { it.copy(secondsPerSheet = (value * 10).toInt() / 10.0) }
        }
        SwitchRow(
            title = "Drop uploads halfway",
            subtitle = "Exercises WorkManager retries",
            checked = fake.failUploads,
            onCheckedChange = { value -> update { it.copy(failUploads = value) } },
        )
        SwitchRow(
            title = "Reject poster options (422)",
            subtitle = "Shows how unsupported options are flagged",
            checked = fake.rejectPosterOptions,
            onCheckedChange = { value -> update { it.copy(rejectPosterOptions = value) } },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun CredentialsDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit, onScan: () -> Unit) {
    // Plain remember on purpose: rememberSaveable would copy the secrets into the saved-state bundle.
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val transformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text("Server credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the Cloudflare Access service token and the API key. They're encrypted with a key that never leaves this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("CF-Access-Client-Id") }, singleLine = true)
                OutlinedTextField(
                    secret,
                    { secret = it.trim() },
                    label = { Text("CF-Access-Client-Secret") },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    apiKey,
                    { apiKey = it.trim() },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide secrets" else "Show secrets")
                        }
                    },
                )
                TextButton(onClick = onScan) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Scan a setup QR instead")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(clientId, secret, apiKey) }, enabled = clientId.isNotBlank() && secret.isNotBlank() && apiKey.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
