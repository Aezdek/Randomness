package xyz.aezdek.hush.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.NotePayload
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.ago
import xyz.aezdek.hush.ui.CreateKind
import xyz.aezdek.hush.ui.theme.Aez

/**
 * A note opened from the notes row: someone else's can be liked and replied to (the reply goes to them as a message,
 * with the note quoted, as on Instagram); your own shows who liked it, with New note and Delete.
 */
@Composable
internal fun NoteSheet(authorId: String, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    // An expired or deleted note just isn't shown.
    val entity = index.notes[authorId] ?: return
    val note = entity.payloadAs<NotePayload>() ?: return
    val mine = authorId == me.id
    val likes = index.children[entity.id].orEmpty().filter { it.dto.kind == Kind.LIKE }
    val liked = likes.any { it.dto.authorId == me.id }
    var reply by remember(entity.id) { mutableStateOf("") }
    val username = state.users[authorId]?.username ?: me.username

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp)).background(Aez.colors.elev3).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text((note.emoji?.plus(" ") ?: "") + note.text, fontSize = 16.sp, color = Aez.colors.text, textAlign = TextAlign.Center)
            }
            Avatar(authorId, 84.dp, index, username)
            Text(if (mine) "Your note" else username, fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
            Text("${ago(entity.dto.receivedAt)} ago · disappears after 24 hours", fontSize = 12.sp, color = Aez.colors.text2)
            if (mine) {
                Text(
                    if (likes.isEmpty()) "No likes yet"
                    else "Liked by " + likes.joinToString(", ") { if (it.dto.authorId == me.id) "you" else state.users[it.dto.authorId]?.username ?: "someone" },
                    fontSize = 13.sp, color = Aez.colors.text2, textAlign = TextAlign.Center,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AezButton("New note", {
                        onDismiss()
                        nav.go(Screen.Create(CreateKind.NOTE))
                    }, style = ButtonStyle.SECONDARY)
                    AezButton("Delete", {
                        onDismiss()
                        Graph.launch { Graph.client.composer.delete(entity.id) }
                    }, style = ButtonStyle.DANGER)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (liked) "Unlike note" else "Like note",
                        tint = if (liked) Color(0xFFFF3040) else Aez.colors.text,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { Graph.launch { Graph.client.composer.toggleLike(entity.id) } }.padding(10.dp),
                    )
                    Box(Modifier.weight(1f)) { AezField(reply, { reply = it.take(500) }, "Reply to $username…") }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "Send reply", tint = if (reply.isBlank()) Aez.colors.text3 else Aez.colors.text,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(enabled = reply.isNotBlank()) {
                            val text = reply.trim()
                            onDismiss()
                            Graph.launch {
                                val threadId = Graph.client.composer.openDirect(authorId)
                                Graph.client.composer.sendMessage(threadId, MessagePayload(MessageType.NOTE_REPLY, text = text, quote = note.text, sharedObjectId = entity.id))
                                Graph.toast("Reply sent")
                            }
                        }.padding(10.dp),
                    )
                }
                Text("View profile", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Aez.colors.text2,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDismiss(); nav.go(Screen.Profile(authorId)) }.padding(8.dp))
            }
        }
    }
}
