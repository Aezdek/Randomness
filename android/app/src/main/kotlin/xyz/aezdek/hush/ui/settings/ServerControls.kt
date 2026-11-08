package xyz.aezdek.hush.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.ApiException
import xyz.aezdek.hush.protocol.AdminServerDto
import xyz.aezdek.hush.protocol.AdminStatus
import xyz.aezdek.hush.protocol.ControlStatusDto
import xyz.aezdek.hush.protocol.OkResponse
import xyz.aezdek.hush.protocol.ServerAction
import xyz.aezdek.hush.protocol.ServerActionRequest
import xyz.aezdek.hush.protocol.ServerLimits
import xyz.aezdek.hush.protocol.ServerSettingsDto
import xyz.aezdek.hush.protocol.ServerUnlockResponse
import xyz.aezdek.hush.protocol.UnlockRequest
import xyz.aezdek.hush.protocol.WebAdminRequest
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.messages.ToggleRow
import xyz.aezdek.hush.ui.theme.Aez

/*
 * The admin's server controls, as on the web's Server tab: install updates, restart, back up and check for updates
 * (run on the server by its control helper), the server's settings as a form, and the switch that turns the web
 * admin off. Changing anything needs the server password, which unlocks the controls for ten minutes.
 */

private val VERBS = mapOf(
    ServerAction.UPDATE to Triple("Installing the update", "Updated", "The update didn't go through"),
    ServerAction.RESTART to Triple("Restarting Hush", "Restarted", "Hush didn't restart cleanly"),
    ServerAction.BACKUP to Triple("Backing up", "Backed up", "The backup failed"),
    ServerAction.CHECK to Triple("Checking for updates", "Checked for updates", "Couldn't check for updates"),
)

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("command", text))
    Graph.toast("Command copied")
}

@Composable
internal fun ServerControls(status: AdminStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf<AdminServerDto?>(null) }
    var draft by remember { mutableStateOf<ServerSettingsDto?>(null) }
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    suspend fun load() {
        val loaded = runCatching { Graph.client.api.get<AdminServerDto>("/admin/server") }.getOrNull() ?: return
        server = loaded
        if (draft == null) draft = loaded.settings
        now = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) { load() }
    val job = server?.control
    val working = job != null && (job.state == "queued" || job.state == "running")
    // While a job runs (an update restarts the server, so some of these fail), keep looking.
    LaunchedEffect(working) {
        while (working) {
            delay(2500)
            load()
        }
    }

    val s = server
    val d = draft
    if (s == null || d == null) {
        Spinner(Modifier.padding(24.dp))
        return
    }
    val unlocked = (s.unlockedUntil ?: 0) > now
    val noAgent = !s.agent

    /** Runs [action] now if unlocked, else once the server password unlocks the controls. */
    fun guard(reason: String, action: () -> Unit) {
        if (unlocked) action() else {
            password = ""
            pending = reason to action
        }
    }

    fun act(action: String) = guard(VERBS[action]?.first ?: action) {
        scope.launch {
            Graph.attempt { Graph.client.api.post<ControlStatusDto, ServerActionRequest>("/admin/server/actions", ServerActionRequest(action)) }
                ?.let { started -> server = server?.copy(control = started) }
        }
    }

    Group("Server controls") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (unlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null,
                tint = if (unlocked) Aez.colors.success else Aez.colors.text2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (unlocked) "Unlocked" else "Locked", fontSize = 16.sp, color = Aez.colors.text)
                Text(
                    if (unlocked) "Locks again in ${(((s.unlockedUntil ?: now) - now) / 60_000).coerceAtLeast(1)} min" else "Changing anything asks for the server password",
                    fontSize = 13.sp, color = Aez.colors.text2,
                )
            }
            Text(
                if (unlocked) "Lock" else "Unlock", color = Aez.colors.link, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    if (unlocked) scope.launch {
                        Graph.attempt { Graph.client.api.post<OkResponse>("/admin/server/lock") }
                        server = server?.copy(unlockedUntil = null)
                    } else guard("Unlock the server controls") { Graph.toast("Unlocked for 10 minutes") }
                }.padding(8.dp),
            )
        }
        if (noAgent) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("One step on the server first: the buttons need Hush's helper there, which the next update installs.",
                    fontSize = 13.sp, color = Aez.colors.text2, lineHeight = 18.sp)
                CommandChip(context, "sudo /opt/hl/hush/update.sh")
            }
        }
    }

    Group("Server") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(if (s.updateAvailable && s.latestVersion != null) "Hush ${s.latestVersion} is ready to install" else "Hush is up to date",
                fontSize = 16.sp, color = Aez.colors.text)
            Text("Running ${s.version}" + (s.commit?.let { " (${it.take(7)})" } ?: ""), fontSize = 13.sp, color = Aez.colors.text2)
        }
        ActionRow(Icons.Outlined.Download, "Install update", enabled = !noAgent && !working) { act(ServerAction.UPDATE) }
        ActionRow(Icons.Outlined.Refresh, "Check for updates", enabled = !noAgent && !working) { act(ServerAction.CHECK) }
        ActionRow(Icons.Outlined.RestartAlt, "Restart", enabled = !noAgent && !working) { act(ServerAction.RESTART) }
        ActionRow(Icons.Outlined.Backup, "Back up now", enabled = !noAgent && !working) { act(ServerAction.BACKUP) }
        if (job != null) {
            val words = VERBS[job.action]
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    working -> Spinner(Modifier.size(20.dp))
                    job.state == "done" -> Icon(Icons.Outlined.CheckCircle, null, tint = Aez.colors.success, modifier = Modifier.size(20.dp))
                    else -> Icon(Icons.Outlined.ErrorOutline, null, tint = Aez.colors.danger, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            working -> "${words?.first ?: job.action}…"
                            job.state == "done" -> words?.second ?: job.action
                            else -> words?.third ?: job.action
                        },
                        fontSize = 15.sp, color = Aez.colors.text,
                    )
                    val note = if (job.state == "queued") "Waiting for the server to pick it up" else job.message
                    if (!note.isNullOrBlank()) Text(note, fontSize = 13.sp, color = Aez.colors.text2)
                }
                if (!s.log.isNullOrBlank()) {
                    Icon(Icons.Outlined.Terminal, "Output", tint = Aez.colors.text2,
                        modifier = Modifier.clip(CircleShape).clickable { showLog = !showLog }.padding(8.dp).size(20.dp))
                }
            }
            if (showLog && !s.log.isNullOrBlank()) {
                Text(
                    s.log!!, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Aez.colors.text2, lineHeight = 15.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp))
                        .background(Aez.colors.elev2).horizontalScroll(rememberScrollState()).padding(10.dp),
                )
            }
        }
    }

    Group("Settings") {
        StepperRow("Members", "Up to this many people (${status.users} now)", d.maxMembers,
            maxOf(ServerLimits.MIN_MEMBERS, status.users), ServerLimits.MAX_MEMBERS_LIMIT, "people") { draft = d.copy(maxMembers = it) }
        StepperRow("Invites last", "How long a new invite code works", d.inviteDays,
            ServerLimits.MIN_INVITE_DAYS, ServerLimits.MAX_INVITE_DAYS, if (d.inviteDays == 1) "day" else "days") { draft = d.copy(inviteDays = it) }
        StepperRow("Keep free", "Uploads pause below this much free disk", d.minFreeGb,
            ServerLimits.MIN_FREE_GB, ServerLimits.MAX_FREE_GB, "GB") { draft = d.copy(minFreeGb = it) }
        val changed = d != s.settings
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            if (changed) TextButton(onClick = { draft = s.settings }) { Text("Undo", color = Aez.colors.text2) }
            TextButton(enabled = changed, onClick = {
                guard("Save the settings") {
                    scope.launch {
                        Graph.attempt { Graph.client.api.put<ServerSettingsDto, ServerSettingsDto>("/admin/server/settings", d) }?.let { saved ->
                            server = server?.copy(settings = saved)
                            draft = saved
                            Graph.toast("Settings saved")
                            onChanged()
                        }
                    }
                }
            }) { Text("Save settings", color = if (changed) Aez.colors.link else Aez.colors.text3, fontWeight = FontWeight.SemiBold) }
        }
    }

    Group("Web admin") {
        ToggleRow(Icons.Outlined.AdminPanelSettings, "Admin in the app and browser", true) { confirmOff = true }
    }
    Text(
        "Turning it off closes this panel for every admin. Only the server can turn it back on.",
        fontSize = 13.sp, color = Aez.colors.text2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
    )

    pending?.let { (reason, action) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = Aez.colors.elev2,
            title = { Text("Server password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$reason: enter the server password. The controls then stay unlocked for 10 minutes.", color = Aez.colors.text2)
                    AezField(password, { password = it }, "Server password", password = true)
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        val unlockedUntil = try {
                            Graph.client.api.post<ServerUnlockResponse, UnlockRequest>("/admin/server/unlock", UnlockRequest(password)).unlockedUntil
                        } catch (e: ApiException) {
                            Graph.toast(e.message ?: "That isn't the server password", error = true)
                            null
                        } catch (e: Exception) {
                            Graph.toast(Graph.messageOf(e), error = true)
                            null
                        }
                        busy = false
                        if (unlockedUntil != null) {
                            server = server?.copy(unlockedUntil = unlockedUntil)
                            now = System.currentTimeMillis()
                            pending = null
                            password = ""
                            action()
                        }
                    }
                }) { Text("Unlock", fontWeight = FontWeight.SemiBold, color = Aez.colors.link) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel", color = Aez.colors.text) } },
        )
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            containerColor = Aez.colors.elev2,
            title = { Text("Turn off the web admin?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This panel closes for every admin, here and in the browser. Hush keeps working. To turn it back on, run this on the server:",
                        color = Aez.colors.text2)
                    CommandChip(context, "sudo /opt/hl/hush/control.sh web-admin on")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    guard("Turn off the web admin") {
                        scope.launch {
                            if (Graph.attempt { Graph.client.api.post<OkResponse, WebAdminRequest>("/admin/server/web-admin", WebAdminRequest(false)) } != null) {
                                Graph.toast("The web admin is off")
                                onChanged()
                            }
                        }
                    }
                }) { Text("Turn off", color = Aez.colors.danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel", color = Aez.colors.text) } },
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) Aez.colors.text else Aez.colors.text3, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 15.sp, color = if (enabled) Aez.colors.text else Aez.colors.text3)
    }
}

@Composable
private fun StepperRow(title: String, hint: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Aez.colors.text)
            Text(hint, fontSize = 12.sp, color = Aez.colors.text2)
        }
        StepButton("−", value > min) { onChange((value - 1).coerceIn(min, max)) }
        Text("$value", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Aez.colors.text, modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", value < max) { onChange((value + 1).coerceIn(min, max)) }
        Text(unit, fontSize = 13.sp, color = Aez.colors.text2, modifier = Modifier.padding(start = 8.dp).widthIn(min = 44.dp))
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Aez.colors.elev2).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, color = if (enabled) Aez.colors.text else Aez.colors.text3) }
}

@Composable
private fun CommandChip(context: Context, command: String) {
    Text(
        command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Aez.colors.text,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Aez.colors.elev2)
            .clickable { copy(context, command) }.padding(10.dp),
    )
}

/** What admins see while the web admin is off. */
@Composable
internal fun WebAdminOffNote() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("The web admin is off", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Aez.colors.text)
        Text("Hush keeps working. To manage it here again, turn the web admin back on from the server (tap to copy):",
            fontSize = 14.sp, color = Aez.colors.text2, lineHeight = 19.sp)
        CommandChip(context, "sudo /opt/hl/hush/control.sh web-admin on")
        Text("Invites while it's off:", fontSize = 13.sp, color = Aez.colors.text2)
        CommandChip(context, "sudo /opt/hl/hush/control.sh invite")
    }
}
