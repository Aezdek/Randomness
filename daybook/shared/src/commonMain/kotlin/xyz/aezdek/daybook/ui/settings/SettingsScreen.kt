package xyz.aezdek.daybook.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.domain.formatLastSync
import xyz.aezdek.daybook.sync.Appearance
import xyz.aezdek.daybook.sync.SyncConfig
import xyz.aezdek.daybook.sync.SyncException
import xyz.aezdek.daybook.sync.SyncStatus
import xyz.aezdek.daybook.sync.serverUrlProblem
import xyz.aezdek.daybook.ui.components.AppleScreen
import xyz.aezdek.daybook.ui.components.AppleText
import xyz.aezdek.daybook.ui.components.FieldRow
import xyz.aezdek.daybook.ui.components.GroupedSection
import xyz.aezdek.daybook.ui.components.ListRow
import xyz.aezdek.daybook.ui.components.ProminentButton
import xyz.aezdek.daybook.ui.components.SectionHeader
import xyz.aezdek.daybook.ui.components.SegmentedControl
import xyz.aezdek.daybook.ui.components.StatusLabel
import xyz.aezdek.daybook.ui.components.SymbolIcon
import xyz.aezdek.daybook.ui.components.readable
import xyz.aezdek.daybook.ui.today.look
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.Symbols
import kotlin.time.Clock

private sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val tasks: Long) : TestResult
    data class Failed(val message: String) : TestResult
}

@Composable
fun SettingsScreen(app: Daybook, listState: LazyListState, chrome: PaddingValues, isWeb: Boolean = false) {
    val colors = Apple.colors
    val scope = rememberCoroutineScope()
    val saved by app.sync.config.collectAsState()
    val status by app.sync.status.collectAsState()
    val pending by app.tasks.pendingCount.collectAsState(initial = 0)
    val appearance by app.appearance.collectAsState()

    var url by remember(saved) { mutableStateOf(saved.serverUrl) }
    var token by remember(saved) { mutableStateOf(saved.token) }
    var accessId by remember(saved) { mutableStateOf(saved.accessClientId) }
    var accessSecret by remember(saved) { mutableStateOf(saved.accessClientSecret) }
    var test by remember { mutableStateOf<TestResult?>(null) }
    var attempted by remember { mutableStateOf(false) }

    val draft = SyncConfig(url.trim(), token.trim(), accessId.trim(), accessSecret.trim())
    val changed = draft != saved
    val urlProblem = if (url.isBlank() && !attempted) null else serverUrlProblem(url)
    val tokenProblem = if (token.isBlank() && attempted) "Enter the access token." else null
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(15_000)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }

    fun save() {
        attempted = true
        if (serverUrlProblem(url) != null || token.isBlank()) return
        app.sync.updateConfig(draft)
        test = null
    }

    fun runTest() {
        test = TestResult.Running
        scope.launch {
            test = app.sync.testConnection(draft).fold(
                onSuccess = { TestResult.Ok(it.tasks) },
                onFailure = { TestResult.Failed(it.message ?: "Couldn't connect.") },
            )
        }
    }

    AppleScreen(title = "Settings", listState = listState, chrome = chrome) {
        item(key = "server") {
            Column(Modifier.readable()) {
                GroupedSection(
                    header = "Sync Server",
                    footer = if (isWeb) {
                        "This page keeps a copy of your tasks in this browser and syncs it with the server it was loaded from. " +
                            "Enter the token of your Daybook server."
                    } else {
                        "Each device keeps its own copy of your tasks and works offline. The server passes changes between them. " +
                            "Use the address and token of your Daybook server."
                    },
                    rows = listOf(
                        {
                            FieldRow(
                                url, { url = it }, placeholder = "https://daybook.example.com", label = "Server",
                                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
                            )
                        },
                        { FieldRow(token, { token = it }, placeholder = "Required", label = "Token", secure = true, onImeAction = ::save) },
                    ),
                )
                val problem = urlProblem ?: tokenProblem
                if (problem != null) {
                    Row(
                        Modifier.padding(start = Apple.margin + 4.dp, end = Apple.margin, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SymbolIcon(Symbols.warning, null, tint = colors.red, size = 16.dp)
                        AppleText(problem, Apple.type.footnote, color = colors.red)
                    }
                }
            }
        }

        // In a browser, Cloudflare Access signs you in with a cookie, so no service token is needed.
        if (!isWeb) item(key = "access") {
            GroupedSection(
                modifier = Modifier.readable(),
                header = "Cloudflare Access",
                footer = "Only needed if the server sits behind Cloudflare Access. Create a service token, allow it in the application's policy, and enter it here.",
                rows = listOf(
                    { FieldRow(accessId, { accessId = it }, placeholder = "Optional", label = "Client ID", imeAction = ImeAction.Next) },
                    { FieldRow(accessSecret, { accessSecret = it }, placeholder = "Optional", label = "Secret", secure = true) },
                ),
            )
        }

        item(key = "save") {
            Column(Modifier.readable().padding(horizontal = Apple.margin).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProminentButton(if (saved.isConfigured) "Save and Sync" else "Connect", onClick = ::save, enabled = changed && url.isNotBlank())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    xyz.aezdek.daybook.ui.components.TextButton(
                        "Test Connection",
                        onClick = ::runTest,
                        enabled = serverUrlProblem(url) == null && token.isNotBlank() && test != TestResult.Running,
                        style = Apple.type.subheadSemibold,
                    )
                }
                when (val t = test) {
                    null -> Unit
                    TestResult.Running -> StatusLabel("Connecting…", Symbols.syncArrows, colors.secondaryLabel, Modifier.align(Alignment.CenterHorizontally))
                    is TestResult.Ok -> StatusLabel(
                        "Connected. The server holds ${t.tasks} ${if (t.tasks == 1L) "task" else "tasks"}.",
                        Symbols.cloudCheck,
                        colors.greenFill,
                        Modifier.align(Alignment.CenterHorizontally),
                    )
                    is TestResult.Failed -> StatusLabel(t.message, Symbols.warning, colors.red, Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }

        item(key = "status") {
            val look = status.look(colors, pending)
            val detail = when (val s = status) {
                SyncStatus.NotConfigured -> "Not connected to a server. Tasks are saved on this device only."
                is SyncStatus.Synced -> s.lastSync?.let { "Last synced ${formatLastSync(it, now)}." } ?: "Connected."
                is SyncStatus.Syncing -> "Syncing now…"
                is SyncStatus.Offline -> "Can't reach the server. Changes are kept and will sync when it's back."
                is SyncStatus.Failed -> s.message + if (s.kind == SyncException.Kind.Auth) " Check the token." else ""
            }
            GroupedSection(
                modifier = Modifier.readable(),
                header = "Status",
                footer = detail,
                rows = buildList {
                    add { ListRow(look.label, icon = look.symbol, iconTint = look.tint, value = if (pending > 0) "$pending waiting" else null) }
                    if (saved.isConfigured) {
                        add { ListRow("Sync Now", icon = Symbols.arrowClockwise, titleColor = colors.accent, onClick = { app.sync.requestSync() }) }
                        add {
                            ListRow("Disconnect", icon = Symbols.link, iconTint = colors.red, titleColor = colors.red, onClick = {
                                app.sync.updateConfig(SyncConfig())
                                attempted = false
                            })
                        }
                    }
                },
            )
        }

        item(key = "appearance") {
            Column(Modifier.readable()) {
                SectionHeader("Appearance")
                SegmentedControl(
                    options = listOf("System", "Light", "Dark"),
                    selected = Appearance.entries.indexOf(appearance),
                    onSelect = { app.setAppearance(Appearance.entries[it]) },
                    label = "Appearance",
                    modifier = Modifier.padding(horizontal = Apple.margin),
                )
            }
        }

        item(key = "about") {
            GroupedSection(
                modifier = Modifier.readable(),
                header = "About",
                footer = "Subjects follow Cambridge International A Level syllabuses 9618, 9709 and 9702. Text is set in Inter, under the SIL Open Font License." +
                    if (isWeb) " Dates use js-joda, © Philipp Thürwächter and Pattrick Hüper, under the BSD licence." else "",
                rows = listOf(
                    { ListRow("Daybook", icon = Symbols.info, value = "1.0") },
                    { ListRow("This Device", icon = Symbols.laptop, value = app.deviceId.take(8)) },
                ),
            )
        }
    }

    LaunchedEffect(saved) { attempted = false }
}
