package xyz.aezdek.aezgram.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.aezdek.aezgram.Graph
import xyz.aezdek.aezgram.client.AppState
import xyz.aezdek.aezgram.client.Entity
import xyz.aezdek.aezgram.client.Index
import xyz.aezdek.aezgram.protocol.MessagePayload
import xyz.aezdek.aezgram.protocol.MessageType
import xyz.aezdek.aezgram.protocol.ThreadPayload
import xyz.aezdek.aezgram.protocol.UserDto
import xyz.aezdek.aezgram.ui.LocalNav
import xyz.aezdek.aezgram.ui.Screen
import xyz.aezdek.aezgram.ui.components.AezButton
import xyz.aezdek.aezgram.ui.components.AezField
import xyz.aezdek.aezgram.ui.components.Avatar
import xyz.aezdek.aezgram.ui.components.ButtonStyle
import xyz.aezdek.aezgram.ui.components.ConfirmDialog
import xyz.aezdek.aezgram.ui.components.SectionLabel
import xyz.aezdek.aezgram.ui.components.SheetItem
import xyz.aezdek.aezgram.ui.components.TopBar
import xyz.aezdek.aezgram.ui.theme.Aez

fun reachable(state: AppState): List<UserDto> =
    state.users.values.filter { !it.deleted && !it.blockedByMe && it.id != state.me?.id }.sortedBy { it.username }

/** A person in a list, with a selection tick. */
@Composable
fun PersonRow(user: UserDto, index: Index, selected: Boolean? = null, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(user.id, 46.dp, index, user.username)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.username, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Aez.colors.text)
            Text(subtitle ?: index.displayName(user), fontSize = 13.sp, color = Aez.colors.text2)
        }
        if (selected != null) {
            Box(
                Modifier.size(24.dp).then(if (selected) Modifier.background(Color(0xFF3B82F6), CircleShape) else Modifier.border(1.5.dp, Aez.colors.borderStrong, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** Pick people for a new chat; more than one makes a group (up to 10 people). */
@Composable
fun NewChat() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val people = reachable(state).filter { query.isBlank() || it.username.contains(query.lowercase()) || index.displayName(it).contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        TopBar("New message")
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            AezField(query, { query = it }, "Search")
            if (picked.size > 1) {
                Spacer(Modifier.size(8.dp))
                AezField(name, { name = it.take(60) }, "Group name (optional)")
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(people, key = { it.id }) { user ->
                PersonRow(user, index, selected = user.id in picked) {
                    picked = if (user.id in picked) picked - user.id else if (picked.size < 9) picked + user.id else picked
                }
            }
        }
        AezButton(
            if (picked.size > 1) "Create group" else "Chat", {
                busy = true
                scope.launch {
                    val id = Graph.attempt {
                        if (picked.size == 1) Graph.client.composer.openDirect(picked.single())
                        else Graph.client.composer.createThread(picked, isGroup = true, meta = ThreadPayload(name = name.ifBlank { null }))
                    }
                    busy = false
                    if (id != null) nav.replace(Screen.Chat(id))
                }
            },
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), enabled = picked.isNotEmpty(), busy = busy, style = ButtonStyle.BRAND,
        )
    }
}

/** Details of a chat: people, mute, vanish mode, name, add or remove members, leave. */
@Composable
fun ThreadInfo(threadId: String) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val thread = state.threads[threadId] ?: return
    val muted = threadId in index.settings.mutedThreads
    var vanish by remember { mutableStateOf(Graph.prefs.vanish(threadId)) }
    var adding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val meta = index.threadMeta[threadId]
    val isAdmin = thread.members.firstOrNull { it.userId == me.id }?.role == "admin" || thread.createdBy == me.id
    Column(Modifier.fillMaxSize()) {
        TopBar("Details")
        LazyColumn(Modifier.weight(1f)) {
            if (thread.isGroup) {
                item {
                    var name by remember(meta?.id) { mutableStateOf(meta?.payloadAs<ThreadPayload>()?.name.orEmpty()) }
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { AezField(name, { name = it.take(60) }, "Group name") }
                        Spacer(Modifier.width(8.dp))
                        AezButton("Save", {
                            Graph.launch {
                                val m = meta ?: return@launch
                                Graph.client.composer.edit(m, (m.payloadAs<ThreadPayload>() ?: ThreadPayload()).copy(name = name.ifBlank { null }))
                            }
                        }, small = true, style = ButtonStyle.SECONDARY)
                    }
                }
            }
            item {
                ToggleRow(Icons.Outlined.NotificationsOff, "Mute messages", muted) {
                    Graph.launch {
                        Graph.client.composer.saveSettings { s ->
                            s.copy(mutedThreads = if (muted) s.mutedThreads - threadId else (s.mutedThreads + threadId).distinct())
                        }
                    }
                }
                ToggleRow(Icons.Outlined.AutoDelete, "Vanish mode", vanish, "Seen messages are deleted from the server when you leave the chat") {
                    vanish = !vanish
                    Graph.prefs.setVanish(threadId, vanish)
                }
            }
            item { SectionLabel("People") }
            items(thread.members, key = { it.userId }) { member ->
                val user = state.users[member.userId]
                if (member.userId == me.id) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me.id, 46.dp, index, me.username)
                        Spacer(Modifier.width(12.dp))
                        Text("${me.username} (you)", fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                    }
                } else if (user != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            PersonRow(user, index, subtitle = if (state.pins[user.id]?.verified == true) "Verified" else "Not verified") { nav.go(Screen.Profile(user.id)) }
                        }
                        SheetIcon(Icons.Outlined.Shield) { nav.go(Screen.Verify(user.id)) }
                        if (thread.isGroup && isAdmin) SheetIcon(Icons.Outlined.PersonRemove) { Graph.launch { Graph.client.composer.leaveThread(threadId, user.id) } }
                    }
                }
            }
            if (thread.isGroup && thread.members.size < 10) {
                item { SheetItem("Add people", Icons.Outlined.PersonAdd) { adding = true } }
            }
            if (thread.isGroup) {
                item { SheetItem("Leave chat", Icons.AutoMirrored.Outlined.ExitToApp, danger = true) { leaving = true } }
            }
        }
    }
    if (adding) {
        var picked by remember { mutableStateOf(listOf<String>()) }
        ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = Aez.colors.elev1) {
            val candidates = reachable(state).filter { u -> thread.members.none { it.userId == u.id } }
            Text("Add people", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            Text("They'll see messages from now on, not earlier ones.", color = Aez.colors.text2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(candidates, key = { it.id }) { user ->
                    PersonRow(user, index, selected = user.id in picked) {
                        picked = if (user.id in picked) picked - user.id else picked + user.id
                    }
                }
            }
            AezButton("Add", {
                adding = false
                Graph.launch { Graph.client.composer.addMembers(threadId, picked) }
            }, Modifier.fillMaxWidth().padding(16.dp), enabled = picked.isNotEmpty())
        }
    }
    if (leaving) {
        ConfirmDialog("Leave this chat?", "You won't get new messages. Others will start a new key epoch so you can't read what comes next.", "Leave",
            onConfirm = {
                Graph.launch {
                    Graph.client.composer.leaveThread(threadId)
                }
                nav.go(Screen.Inbox)
            }, onDismiss = { leaving = false })
    }
}

@Composable
private fun SheetIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Aez.colors.text2, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector?, title: String, checked: Boolean, subtitle: String? = null, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = Aez.colors.text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Aez.colors.text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Aez.colors.text2)
        }
        Switch(
            checked = checked, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF3B82F6), checkedThumbColor = Color.White),
        )
    }
}

/** Send a post or reel to chats (as an end-to-end encrypted message that points at it). */
@Composable
fun ShareSheet(entity: Entity, onDismiss: () -> Unit) {
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    var picked by remember { mutableStateOf(listOf<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Text("Share", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        val threads = sortedThreads(state, index)
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(threads, key = { it.id }) { thread ->
                val other = thread.members.firstOrNull { it.userId != me.id }?.userId
                Row(
                    Modifier.fillMaxWidth().clickable { picked = if (thread.id in picked) picked - thread.id else picked + thread.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(other, 46.dp, index, state.users[other]?.username)
                    Spacer(Modifier.width(12.dp))
                    Text(index.threadTitle(thread, state.users, me.id), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                    val selected = thread.id in picked
                    Box(
                        Modifier.size(24.dp).then(if (selected) Modifier.background(Color(0xFF3B82F6), CircleShape) else Modifier.border(1.5.dp, Aez.colors.borderStrong, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            AezButton("Send", {
                onDismiss()
                val targets = picked
                Graph.launch {
                    for (threadId in targets) {
                        Graph.client.composer.sendMessage(threadId,
                            MessagePayload(MessageType.SHARE, sharedObjectId = entity.id, sharedAuthorId = entity.dto.authorId))
                    }
                    Graph.toast(if (targets.size == 1) "Sent" else "Sent to ${targets.size} chats")
                }
            }, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty())
        }
    }
}
