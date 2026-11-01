package xyz.aezdek.hush.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.protocol.AccountStatus
import xyz.aezdek.hush.protocol.AdminChatDto
import xyz.aezdek.hush.protocol.AdminMemberDto
import xyz.aezdek.hush.protocol.AdminUserDto
import xyz.aezdek.hush.protocol.OkResponse
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.ConfirmDialog
import xyz.aezdek.hush.ui.components.SectionLabel
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.components.ago
import xyz.aezdek.hush.ui.theme.Aez
import java.text.DateFormat
import java.util.Date

private val KINDS = listOf("post" to "Posts", "reel" to "Reels", "story" to "Stories", "comment" to "Comments", "like" to "Likes", "message" to "Messages")

/**
 * A member as the server sees them: account, devices, chats, people and how much they post. Never content: messages,
 * posts and profiles are end-to-end encrypted, so neither the admin nor the server can read them.
 */
@Composable
internal fun MemberSheet(userId: String, users: List<AdminUserDto>, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    var info by remember { mutableStateOf<AdminMemberDto?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var hiding by remember { mutableStateOf<AdminChatDto?>(null) }
    LaunchedEffect(userId, reload) {
        info = Graph.attempt { Graph.client.api.get<AdminMemberDto>("/admin/users/$userId") } ?: info
    }
    fun name(id: String) = if (id == state.me?.id) "you" else "@" + (users.firstOrNull { it.id == id }?.username ?: "someone")
    fun people(ids: List<String>) = if (ids.isEmpty()) "Nobody" else ids.joinToString(", ") { name(it) }
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        val m = info
        if (m == null) {
            Spinner(Modifier.padding(40.dp).align(Alignment.CenterHorizontally))
            return@ModalBottomSheet
        }
        val u = m.user
        LazyColumn(Modifier.heightIn(max = 640.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.id, 56.dp, index, u.username)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "@${u.username}" + when (u.status) {
                                AccountStatus.BLOCKED -> " · blocked"
                                AccountStatus.DISABLED -> " · disabled"
                                else -> ""
                            },
                            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Aez.colors.text,
                        )
                        Text(
                            "Joined ${date.format(Date(u.createdAt))}" + (u.lastActiveAt?.let { " · active ${ago(it)} ago" } ?: "") +
                                (m.invitedBy?.let { " · invited by ${name(it)}" } ?: ""),
                            fontSize = 13.sp, color = Aez.colors.text2,
                        )
                    }
                }
                Text(
                    "Only what the server can see. Messages, posts and profiles are end-to-end encrypted, so nobody else can read them, the admin included.",
                    fontSize = 12.sp, color = Aez.colors.text2,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Aez.colors.elev2).padding(10.dp),
                )
            }
            item { SectionLabel("Activity") }
            item {
                val cells = KINDS.map { (kind, label) -> (m.counts[kind] ?: 0).toString() to label } + (Formatter.formatShortFileSize(context, u.storageBytes) to "Media")
                for (row in cells.chunked(4)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((value, label) in row) {
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Aez.colors.elev2).padding(10.dp)) {
                                Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Aez.colors.text)
                                Text(label, fontSize = 11.sp, color = Aez.colors.text2)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            item { SectionLabel("Chats · ${m.chats.size}") }
            items(m.chats, key = { it.id }) { chat ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Aez.colors.elev2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val others = chat.members.filter { it != u.id }
                        Text(if (chat.isGroup) "Group · ${chat.members.size} people" else "With ${people(others)}", fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                        Text(
                            (if (chat.isGroup) people(others) + " · " else "") + "${chat.messages} messages, ${chat.sentByThem} from them" +
                                (chat.lastMessageAt?.let { " · last ${ago(it)} ago" } ?: ""),
                            fontSize = 12.sp, color = Aez.colors.text2,
                        )
                    }
                    if (!u.isAdmin) {
                        Text("Remove", color = Aez.colors.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).padding(start = 8.dp).clickable { hiding = chat }.padding(6.dp))
                    }
                }
            }
            item { SectionLabel("Devices · ${m.devices.size}") }
            items(m.devices) { device ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Aez.colors.elev2).padding(12.dp)) {
                    Text(device.name, fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                    Text(
                        (if (device.platform == "android") "Android app" else "Web") + " · signed in ${date.format(Date(device.createdAt))} · last used ${ago(device.lastSeenAt)} ago",
                        fontSize = 12.sp, color = Aez.colors.text2,
                    )
                }
            }
            item { SectionLabel("People") }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Aez.colors.elev2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Followers · ${m.followers.size}: ${people(m.followers)}", fontSize = 13.sp, color = Aez.colors.text)
                    Text("Following · ${m.following.size}: ${people(m.following)}", fontSize = 13.sp, color = Aez.colors.text)
                    Text(
                        (if (m.isPrivate) "Private" else "Public") + " · activity status " + (if (m.showActivity) "on" else "off") +
                            " · ${m.pendingRequests} follow requests waiting · blocks ${m.blocking} · made ${m.invitesCreated} invites",
                        fontSize = 12.sp, color = Aez.colors.text2,
                    )
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().size(20.dp)) }
        }
    }
    hiding?.let { chat ->
        ConfirmDialog(
            "Remove from this chat?",
            "The chat disappears from @${info?.user?.username}'s messages and they get nothing new from it. The others keep it.",
            "Remove",
            onConfirm = {
                hiding = null
                Graph.launch {
                    Graph.client.api.delete<OkResponse>("/admin/threads/${chat.id}/members/$userId")
                    Graph.toast("Removed from the chat")
                    reload++
                    onChanged()
                }
            },
            onDismiss = { hiding = null },
        )
    }
}

