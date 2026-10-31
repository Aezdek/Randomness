package xyz.aezdek.hush.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.protocol.AdminStatus
import xyz.aezdek.hush.protocol.AdminUserDto
import xyz.aezdek.hush.protocol.InviteDto
import xyz.aezdek.hush.protocol.OkResponse
import xyz.aezdek.hush.ui.components.ActionSheet
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.SheetItem
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.components.ago
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand

private const val DAY = 86_400_000L
private val WARNING = Color(0xFFFFB547)

/** The hairline between rows of a group. */
@Composable
private fun RowDivider(start: Dp = 59.dp) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(1.dp).background(Aez.colors.border))
}

/** The admin panel: how the server is doing, who's on it, invites, backups and the commands that run it. */
@Composable
internal fun AdminPanel(
    // What to show before the first load answers (screenshots pass these; the app starts empty).
    initialStatus: AdminStatus? = null,
    initialUsers: List<AdminUserDto> = emptyList(),
    initialInvites: List<InviteDto> = emptyList(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    var status by remember { mutableStateOf(initialStatus) }
    var users by remember { mutableStateOf(initialUsers) }
    var invites by remember { mutableStateOf(initialInvites) }
    var managing by remember { mutableStateOf<AdminUserDto?>(null) }
    var removing by remember { mutableStateOf<AdminUserDto?>(null) }
    suspend fun load() {
        status = Graph.attempt { Graph.client.api.get<AdminStatus>("/admin/status") } ?: status
        users = Graph.attempt { Graph.client.api.get<List<AdminUserDto>>("/admin/users") } ?: users
        invites = Graph.attempt { Graph.client.api.get<List<InviteDto>>("/admin/invites") } ?: invites
    }
    LaunchedEffect(Unit) { load() }

    val s = status
    if (s == null) {
        Spinner(Modifier.padding(40.dp))
        return
    }
    val now = System.currentTimeMillis()
    val lastBackup = s.lastBackupAt
    val backupOk = lastBackup != null && now - lastBackup < 2 * DAY && s.lastBackupOk != false

    // One column of its own, so the settings page's spacing doesn't pull group headers away from their rows.
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.updateAvailable && s.latestVersion != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint(Brand.violet)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.SystemUpdate, null, tint = Brand.pink, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Hush ${s.latestVersion} is ready. Run update.sh on the server to install it.", color = Aez.colors.text, fontSize = 15.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Members", "${s.users} of ${s.maxUsers}", "${s.maxUsers - s.users} places left", Modifier.weight(1f))
                StatTile("Stored", Formatter.formatShortFileSize(context, s.dataBytes), "encrypted", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Free disk", Formatter.formatShortFileSize(context, s.diskFreeBytes),
                    if (s.uploadsBlocked) "Uploads paused" else "on the server", Modifier.weight(1f), warn = s.uploadsBlocked,
                )
                StatTile(
                    "Last backup", s.lastBackupAt?.let { "${ago(it)} ago" } ?: "Never",
                    if (backupOk) "Encrypted, off-site" else "Not set up yet", Modifier.weight(1f), warn = !backupOk, ok = backupOk,
                )
            }
        }

        Group("Members") {
            users.forEachIndexed { i, user ->
                if (i > 0) RowDivider(start = 66.dp)
                val name = index.profile(user.id)?.displayName?.takeIf { it.isNotBlank() } ?: user.username
                val canManage = user.id != state.me?.id && !user.isAdmin
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = canManage) { managing = user }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(user.id, 38.dp, index, user.username)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Aez.colors.text)
                            if (user.isAdmin) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Admin", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Aez.colors.text2,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Aez.colors.elev3).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            "@${user.username} · ${user.posts} posts · ${Formatter.formatShortFileSize(context, user.storageBytes)} · " +
                                if (user.sessions == 1) "1 device" else "${user.sessions} devices",
                            fontSize = 13.sp, color = Aez.colors.text2, maxLines = 1,
                        )
                    }
                    Text(user.lastActiveAt?.let { ago(it) } ?: "—", fontSize = 13.sp, color = Aez.colors.text2)
                }
            }
        }

        Group("Invites") {
            NavRow(Icons.Outlined.PersonAdd, "New invite", chevron = false) {
                if (s.users >= s.maxUsers) {
                    Graph.toast("Hush is full (${s.maxUsers} members)", error = true)
                } else {
                    scope.launch {
                        Graph.attempt { Graph.client.api.post<InviteDto>("/admin/invites") }?.let { shareInvite(context, it.code) }
                        load()
                    }
                }
            }
            invites.sortedByDescending { it.createdAt }.forEach { invite ->
                RowDivider(start = 16.dp)
                val open = invite.usedBy == null && invite.expiresAt > now
                val usedBy = invite.usedBy?.let { id -> users.firstOrNull { it.id == id }?.username }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (open) Aez.colors.success else Aez.colors.elev3))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(invite.code, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = if (open) Aez.colors.text else Aez.colors.text3)
                        Text(
                            when {
                                invite.usedBy != null -> "Used" + (usedBy?.let { " by @$it" } ?: "")
                                invite.expiresAt < now -> "Expired"
                                else -> "Expires in ${((invite.expiresAt - now) / DAY).coerceAtLeast(0) + 1} days"
                            },
                            fontSize = 13.sp, color = Aez.colors.text2,
                        )
                    }
                    if (open) {
                        Icon(
                            Icons.Outlined.Share, "Share", tint = Aez.colors.text,
                            modifier = Modifier.clip(CircleShape).clickable { shareInvite(context, invite.code) }.padding(8.dp).size(20.dp),
                        )
                        Text(
                            "Revoke", color = Aez.colors.danger, fontSize = 15.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                scope.launch {
                                    if (Graph.attempt { Graph.client.api.delete<OkResponse>("/admin/invites/${invite.code}") } != null) Graph.toast("Invite withdrawn")
                                    load()
                                }
                            }.padding(8.dp),
                        )
                    }
                }
            }
        }

        Group("Backups") {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(if (backupOk) Aez.colors.success else WARNING),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Backup, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            backupOk -> "Backed up"
                            s.lastBackupAt != null -> "Backup is overdue"
                            else -> "Backups are off"
                        },
                        fontSize = 16.sp, color = Aez.colors.text,
                    )
                    Text(
                        s.backupMessage ?: if (s.lastBackupAt == null) "Set up the rclone remote, then run install.sh again." else "Every night at 03:30, kept 30 days.",
                        fontSize = 13.sp, color = Aez.colors.text2,
                    )
                }
            }
            RowDivider()
            CommandRow(context, "Back up now", "sudo systemctl start hush-backup")
        }

        Group("Server") {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Version", fontSize = 16.sp, color = Aez.colors.text, modifier = Modifier.weight(1f))
                Text(s.version + (s.commit?.let { " · ${it.take(7)}" } ?: ""), fontSize = 15.sp, color = Aez.colors.text2)
            }
            RowDivider(start = 16.dp)
            CommandRow(context, "Update", "sudo /opt/hl/hush/update.sh")
            RowDivider(start = 16.dp)
            CommandRow(context, "Go back a version", "sudo /opt/hl/hush/update.sh --rollback")
        }
        Text(
            "Run these on Resolute over SSH. Nothing changes until you run update.sh.", fontSize = 13.sp, color = Aez.colors.text2,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
        )

    }
    managing?.let { user ->
        ActionSheet({ managing = null }) {
            Text("@${user.username}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Aez.colors.text, modifier = Modifier.padding(12.dp))
            SheetItem("Sign out on all devices", Icons.AutoMirrored.Outlined.Logout) {
                managing = null
                scope.launch {
                    if (Graph.attempt { Graph.client.api.post<OkResponse>("/admin/users/${user.id}/sign-out") } != null) {
                        Graph.toast("@${user.username} is signed out everywhere")
                    }
                    load()
                }
            }
            SheetItem("Remove from Hush…", Icons.Outlined.PersonRemove, danger = true) {
                managing = null
                removing = user
            }
        }
    }

    removing?.let { user ->
        var typed by remember(user.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Aez.colors.elev2,
            title = { Text("Remove @${user.username}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This deletes their account and everything they posted, for everyone. It stays in the encrypted backups for 30 days.",
                        color = Aez.colors.text2)
                    AezField(typed, { typed = it }, "Type ${user.username} to confirm")
                }
            },
            confirmButton = {
                TextButton(enabled = typed.trim() == user.username, onClick = {
                    removing = null
                    scope.launch {
                        if (Graph.attempt { Graph.client.api.delete<OkResponse>("/admin/users/${user.id}") } != null) Graph.toast("@${user.username} was removed")
                        load()
                    }
                }) { Text("Remove", color = if (typed.trim() == user.username) Aez.colors.danger else Aez.colors.text3, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = Aez.colors.text) } },
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier, warn: Boolean = false, ok: Boolean = false) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Aez.colors.elev1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 13.sp, color = Aez.colors.text2)
        Text(
            value, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, maxLines = 1,
            color = when {
                warn -> WARNING
                ok -> Aez.colors.success
                else -> Aez.colors.text
            },
        )
        Text(sub, fontSize = 12.sp, color = Aez.colors.text2, maxLines = 1)
    }
}

@Composable
private fun CommandRow(context: Context, label: String, command: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, command))
            Graph.toast("Command copied")
        }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = Aez.colors.text)
            Text(command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Aez.colors.text2, maxLines = 1)
        }
        Icon(Icons.Outlined.ContentCopy, "Copy", tint = Aez.colors.text2, modifier = Modifier.size(20.dp))
    }
}
