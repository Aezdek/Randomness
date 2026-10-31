package xyz.aezdek.hush.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.NotePayload
import xyz.aezdek.hush.protocol.ThreadDto
import xyz.aezdek.hush.ui.CreateKind
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.ActionSheet
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.IconAction
import xyz.aezdek.hush.ui.components.SheetItem
import xyz.aezdek.hush.ui.components.TopBar
import xyz.aezdek.hush.ui.components.ago
import xyz.aezdek.hush.ui.feed.ringOf
import xyz.aezdek.hush.ui.theme.Aez

fun sortedThreads(state: AppState, index: Index): List<ThreadDto> {
    val pinned = index.settings.pinnedThreads.toSet()
    return state.threads.values.sortedWith(
        compareByDescending<ThreadDto> { it.id in pinned }
            .thenByDescending { index.lastMessage(it.id)?.dto?.receivedAt ?: it.createdAt },
    )
}

/** What a chat row shows under the name. */
fun lastLine(entity: Entity?, me: String): String {
    entity ?: return "Say hi 👋"
    val p = entity.payloadAs<MessagePayload>() ?: return ""
    val mine = entity.dto.authorId == me
    val prefix = if (mine) "You: " else ""
    return when (p.type) {
        MessageType.TEXT -> prefix + p.text.orEmpty()
        MessageType.MEDIA -> prefix + if (p.media.any { it.mime.startsWith("video/") }) "Sent a video" else "Sent a photo"
        MessageType.VOICE -> prefix + "Voice message"
        MessageType.SHARE -> prefix + "Shared a post"
        MessageType.STORY_REPLY -> if (mine) "You replied to their story" else "Replied to your story"
        MessageType.STORY_REACTION -> if (mine) "You reacted to their story" else "Reacted to your story ${p.text.orEmpty()}"
        MessageType.CALL -> if (p.call?.missed == true) "Missed ${if (p.call?.video == true) "video " else ""}call" else "${if (p.call?.video == true) "Video" else "Audio"} call"
        MessageType.HEART -> prefix + "❤️"
        else -> prefix + (p.text ?: "")
    } + "  ·  " + ago(entity.dto.receivedAt)
}

@Composable
fun Inbox() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val threads = sortedThreads(state, index)
    var menuFor by remember { mutableStateOf<ThreadDto?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar(me.username, actions = { IconAction(Icons.Outlined.Edit, "New message", { nav.go(Screen.NewChat) }) })
        LazyColumn(Modifier.weight(1f)) {
            item { NotesRow(index, state) }
            item {
                Text("Messages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp))
            }
            items(threads, key = { it.id }) { thread ->
                ThreadRow(thread, index, state, onLongPress = { menuFor = thread }) { nav.go(Screen.Chat(thread.id)) }
            }
            if (threads.isEmpty()) {
                item {
                    Text("No chats yet. Tap ✎ to message someone.", color = Aez.colors.text3, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
    menuFor?.let { thread ->
        val pinned = thread.id in index.settings.pinnedThreads
        val muted = thread.id in index.settings.mutedThreads
        ActionSheet({ menuFor = null }) {
            SheetItem(if (pinned) "Unpin" else "Pin", Icons.Outlined.PushPin) {
                menuFor = null
                Graph.launch {
                    Graph.client.composer.saveSettings { s ->
                        s.copy(pinnedThreads = if (pinned) s.pinnedThreads - thread.id else (s.pinnedThreads + thread.id).distinct())
                    }
                }
            }
            SheetItem(if (muted) "Unmute" else "Mute messages", Icons.Outlined.NotificationsOff) {
                menuFor = null
                Graph.launch {
                    Graph.client.composer.saveSettings { s ->
                        s.copy(mutedThreads = if (muted) s.mutedThreads - thread.id else (s.mutedThreads + thread.id).distinct())
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesRow(index: Index, state: AppState) {
    val nav = LocalNav.current
    val me = state.me ?: return
    val people = listOf(me.id) + index.notes.keys.filter { it != me.id && state.users[it]?.blockedByMe != true }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(people, key = { it }) { userId ->
            val note = index.notes[userId]?.payloadAs<NotePayload>()
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(118.dp), contentAlignment = Alignment.BottomCenter) {
                    Avatar(userId, 68.dp, index, state.users[userId]?.username ?: me.username, ring = ringOf(userId, index, state)) {
                        if (userId == me.id) nav.go(Screen.Create(CreateKind.NOTE)) else nav.go(Screen.Profile(userId))
                    }
                    // A thought bubble above the avatar: two lines at most, with the little tail Instagram's notes have.
                    Column(Modifier.align(Alignment.TopCenter)) {
                        Box(
                            Modifier.widthIn(min = 56.dp, max = 92.dp).clip(RoundedCornerShape(16.dp)).background(Aez.colors.elev2)
                                .clickable { if (userId == me.id) nav.go(Screen.Create(CreateKind.NOTE)) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                note?.let { (it.emoji?.plus(" ") ?: "") + it.text } ?: if (userId == me.id) "Share a note" else "",
                                fontSize = 11.sp, lineHeight = 14.sp, color = if (note == null) Aez.colors.text3 else Aez.colors.text, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            )
                        }
                        Box(Modifier.padding(start = 16.dp, top = 2.dp).size(7.dp).clip(CircleShape).background(Aez.colors.elev2))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(if (userId == me.id) "Your note" else state.users[userId]?.username ?: "", fontSize = 11.sp, color = Aez.colors.text2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadDto, index: Index, state: AppState, onLongPress: () -> Unit, onClick: () -> Unit) {
    val me = state.me ?: return
    val unread = index.unreadCount(thread, me.id)
    val others = thread.members.map { it.userId }.filter { it != me.id }
    val first = others.firstOrNull()
    val online = others.any { state.presence[it] == true }
    val muted = thread.id in index.settings.mutedThreads
    val typing = state.typing[thread.id].orEmpty().any { (u, until) -> u != me.id && until > System.currentTimeMillis() }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongPress, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thread.isGroup && others.size > 1) {
            Box(Modifier.size(56.dp)) {
                Box(Modifier.align(Alignment.TopEnd)) { Avatar(others[0], 38.dp, index, state.users[others[0]]?.username) }
                Box(Modifier.align(Alignment.BottomStart).background(Aez.colors.bg, CircleShape).padding(2.dp)) {
                    Avatar(others[1], 38.dp, index, state.users[others[1]]?.username)
                }
            }
        } else {
            Avatar(first, 56.dp, index, state.users[first]?.username, online = online)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(index.threadTitle(thread, state.users, me.id), fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp,
                color = Aez.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (typing) "typing…" else lastLine(index.lastMessage(thread.id), me.id),
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (unread > 0) Aez.colors.text else Aez.colors.text3, fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (muted) Icon(Icons.Outlined.NotificationsOff, "Muted", tint = Aez.colors.text3, modifier = Modifier.size(16.dp))
        if (thread.id in index.settings.pinnedThreads) Icon(Icons.Outlined.PushPin, "Pinned", tint = Aez.colors.text3, modifier = Modifier.size(16.dp))
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(9.dp).background(Color(0xFF3B82F6), CircleShape))
        }
    }
}
