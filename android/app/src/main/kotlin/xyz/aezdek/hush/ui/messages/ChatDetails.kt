package xyz.aezdek.hush.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.ChatCustom
import xyz.aezdek.hush.client.ChatTheme
import xyz.aezdek.hush.client.ChatThemes
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.ThreadDto
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.MediaImage
import xyz.aezdek.hush.ui.components.SectionLabel
import xyz.aezdek.hush.ui.theme.Aez
import java.text.DateFormat
import java.util.Date

private val REACTIONS = listOf("❤️", "😂", "😮", "😢", "😡", "👍", "🔥", "🎉", "🙏", "💯", "😍", "👏", "🥹", "✨", "💀", "🫶")

fun ChatTheme.brush(): Brush = Brush.linearGradient(colors.map { Color(it) })

private fun change(threadId: String, event: ChatEvent) = Graph.launch { Graph.client.composer.sendChatEvent(threadId, event) }

/** The top of a chat's details: who it is with, and Profile / Search / Mute. */
@Composable
internal fun ChatHero(thread: ThreadDto, index: Index, state: AppState, muted: Boolean, onProfile: (() -> Unit)?, onSearch: () -> Unit, onMute: () -> Unit) {
    val me = state.me ?: return
    val others = thread.members.map { it.userId }.filter { it != me.id }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(others.firstOrNull(), 88.dp, index, state.users[others.firstOrNull()]?.username)
        Spacer(Modifier.size(10.dp))
        Text(index.threadTitle(thread, state.users, me.id), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Aez.colors.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        Text(if (thread.isGroup) "${thread.members.size} members" else "@${state.users[others.firstOrNull()]?.username.orEmpty()}",
            fontSize = 13.sp, color = Aez.colors.text2)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onProfile != null) HeroButton(Icons.Outlined.Person, "Profile", onProfile)
            HeroButton(Icons.Outlined.Search, "Search", onSearch)
            HeroButton(if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications, if (muted) "Unmute" else "Mute", onMute)
        }
    }
}

@Composable
private fun HeroButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(84.dp).clip(RoundedCornerShape(14.dp)).background(Aez.colors.elev2).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Aez.colors.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
    }
}

/** Theme, Nicknames and Quick reaction rows. */
@Composable
internal fun CustomRows(custom: ChatCustom, onTheme: () -> Unit, onNicknames: () -> Unit, onReaction: () -> Unit) {
    val theme = ChatThemes.byId(custom.theme) ?: ChatThemes.all.first()
    DetailRow(Icons.Outlined.Palette, "Theme", onTheme) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(theme.brush()))
        Spacer(Modifier.width(8.dp))
        Text(theme.name, fontSize = 14.sp, color = Aez.colors.text2)
    }
    DetailRow(Icons.Outlined.Label, "Nicknames", onNicknames)
    DetailRow(Icons.Outlined.EmojiEmotions, "Quick reaction", onReaction) {
        Text(custom.quickReaction, fontSize = 20.sp)
    }
}

@Composable
private fun DetailRow(icon: ImageVector, title: String, onClick: () -> Unit, value: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Aez.colors.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 15.sp, color = Aez.colors.text, modifier = Modifier.weight(1f))
        value()
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Aez.colors.text3, modifier = Modifier.size(20.dp))
    }
}

/** Photos and videos shared in the chat, newest first (view-once ones stay private). */
internal fun sharedMedia(messages: List<Entity>): List<Pair<Entity, MediaRef>> =
    messages.filter { it.dto.audience != Audience.ONCE && it.payloadAs<MessagePayload>()?.type == MessageType.MEDIA }
        .flatMap { m -> m.payloadAs<MessagePayload>()!!.media.map { m to it } }
        .asReversed()

@Composable
internal fun MediaRow(items: List<Pair<Entity, MediaRef>>, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((message, ref) in items) {
            Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Aez.colors.elev2).clickable { onPick(message.id) }) {
                MediaImage(if (ref.mime.startsWith("video/")) ref.poster ?: ref else ref, Modifier.fillMaxWidth().aspectRatio(1f), maxEdge = 360)
            }
        }
        repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
internal fun ThemeSheet(threadId: String, current: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Text("Theme", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Aez.colors.text, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Everyone in the chat sees the new theme.", fontSize = 13.sp, color = Aez.colors.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        for (row in ChatThemes.all.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                for (theme in row) {
                    val on = theme.id == current
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                            onDismiss()
                            if (!on) change(threadId, ChatEvent(theme = theme.id))
                        }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(56.dp).then(if (on) Modifier.border(2.dp, Aez.colors.text, CircleShape).padding(4.dp) else Modifier)
                                .clip(CircleShape).background(theme.brush()),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Icon(Icons.Filled.Check, "Current", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(theme.name, fontSize = 12.sp, color = Aez.colors.text)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.navigationBarsPadding().size(16.dp))
    }
}

@Composable
internal fun ReactionSheet(threadId: String, current: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Text("Quick reaction", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Aez.colors.text, modifier = Modifier.padding(horizontal = 20.dp))
        Text("The emoji the button next to the message box sends. Everyone in the chat gets the same one.", fontSize = 13.sp,
            color = Aez.colors.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        for (row in REACTIONS.chunked(6)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                for (emoji in row) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (emoji == current) Aez.colors.elev3 else Color.Transparent)
                            .clickable {
                                onDismiss()
                                if (emoji != current) change(threadId, ChatEvent(quickReaction = emoji))
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 28.sp) }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.navigationBarsPadding().size(16.dp))
    }
}

@Composable
internal fun NicknamesSheet(thread: ThreadDto, custom: ChatCustom, index: Index, state: AppState, onDismiss: () -> Unit) {
    val me = state.me ?: return
    var editing by remember { mutableStateOf<String?>(null) }
    fun username(id: String) = if (id == me.id) me.username else state.users[id]?.username ?: "someone"
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Text("Nicknames", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Aez.colors.text, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Nicknames are only used in this chat, and everyone in it sees them.", fontSize = 13.sp, color = Aez.colors.text2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        for (member in thread.members) {
            val nickname = custom.nicknames[member.userId]
            Row(Modifier.fillMaxWidth().clickable { editing = member.userId }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(member.userId, 40.dp, index, username(member.userId))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(nickname ?: username(member.userId), fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                    Text(if (nickname != null) "@${username(member.userId)}" else "Set nickname", fontSize = 13.sp, color = Aez.colors.text2)
                }
                if (nickname != null) {
                    Text("Remove", color = Aez.colors.text2, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        change(thread.id, ChatEvent(nicknameFor = member.userId, nickname = null))
                    }.padding(8.dp))
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().size(16.dp))
    }
    editing?.let { userId ->
        var value by remember(userId) { mutableStateOf(custom.nicknames[userId].orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Aez.colors.elev2,
            title = { Text("Nickname for @${username(userId)}") },
            text = { AezField(value, { value = it.take(ChatCustom.MAX_NICKNAME) }, username(userId)) },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    change(thread.id, ChatEvent(nicknameFor = userId, nickname = value.trim().ifEmpty { null }))
                }) { Text("Save", color = Aez.colors.text, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = Aez.colors.text2) } },
        )
    }
}

/** Searches the chat on this phone, over the decrypted messages; the server never sees the words. */
@Composable
internal fun SearchSheet(thread: ThreadDto, messages: List<Entity>, index: Index, state: AppState, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val me = state.me ?: return
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val results = if (q.length < 2) emptyList() else messages.filter { m ->
        val p = m.payloadAs<MessagePayload>()
        p != null && p.type != MessageType.SYSTEM && p.text?.lowercase()?.contains(q) == true
    }.asReversed().take(100)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Box(Modifier.padding(horizontal = 16.dp)) { AezField(query, { query = it }, "Search in chat") }
        if (q.length >= 2 && results.isEmpty()) {
            Text("No messages with “${query.trim()}”.", color = Aez.colors.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(results, key = { it.id }) { m ->
                val text = m.payloadAs<MessagePayload>()?.text.orEmpty()
                val at = text.lowercase().indexOf(q)
                val start = (at - 30).coerceAtLeast(0)
                Row(Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.dto.authorId, 36.dp, index, state.users[m.dto.authorId]?.username)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (m.dto.authorId == me.id) "You" else index.nameIn(thread.id, m.dto.authorId, state.users), fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = Aez.colors.text)
                        Text(
                            buildAnnotatedString {
                                if (start > 0) append("…")
                                append(text.substring(start, at))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Aez.colors.text)) { append(text.substring(at, at + q.length)) }
                                append(text.substring(at + q.length, (at + q.length + 60).coerceAtMost(text.length)))
                            },
                            fontSize = 13.sp, color = Aez.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(m.dto.receivedAt)), fontSize = 12.sp, color = Aez.colors.text3)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().size(16.dp))
    }
}

@Composable
internal fun MediaSection(media: List<Pair<Entity, MediaRef>>, onPick: (String) -> Unit, onAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionLabel("Photos and videos") }
        if (media.size > 6) Text("See all", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Aez.colors.text,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAll).padding(8.dp))
    }
    for (row in media.take(6).chunked(3)) MediaRow(row, onPick)
}

@Composable
internal fun AllMediaSheet(media: List<Pair<Entity, MediaRef>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Text("Photos and videos", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Aez.colors.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(media.chunked(3)) { row -> MediaRow(row, onPick) }
        }
        Spacer(Modifier.navigationBarsPadding().size(16.dp))
    }
}
