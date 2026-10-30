package xyz.aezdek.hush.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SpeakerNotesOff
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.protocol.CommentPayload
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ReelPayload
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.ActionSheet
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.ConfirmDialog
import xyz.aezdek.hush.ui.components.Empty
import xyz.aezdek.hush.ui.components.Hairline
import xyz.aezdek.hush.ui.components.RichText
import xyz.aezdek.hush.ui.components.SheetItem
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.components.TopBar
import xyz.aezdek.hush.ui.components.ago
import xyz.aezdek.hush.ui.components.boldPrefix
import xyz.aezdek.hush.ui.theme.Aez

/** One post with all its comments and a composer. Fetches the post if this device hasn't synced it. */
@Composable
fun PostDetail(id: String) {
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val entity = state.entities[id]
    var missing by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, entity == null) {
        if (entity == null) {
            val dto = runCatching { Graph.client.api.get<ObjectDto>("/objects/$id") }.getOrNull()
            if (dto == null) missing = true else Graph.client.engine.ingest(listOf(dto))
            kotlinx.coroutines.delay(1500)
            if (Graph.client.state.value.entities[id] == null) missing = true
        }
    }
    var replyTo by remember { mutableStateOf<Entity?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Post")
        when {
            entity == null && missing -> Empty(Icons.Outlined.HeartBroken, "Post unavailable", "It may have been deleted, or it's not shared with you.")
            entity == null -> Spinner()
            else -> {
                val post = entity.post()
                val comments = index.commentsOf(entity.id)
                LazyColumn(Modifier.weight(1f)) {
                    item { PostCard(entity, index, state, detail = true) }
                    item { Hairline() }
                    items(comments, key = { it.id }) { comment ->
                        CommentRow(comment, index, state, depth = 0) { replyTo = it }
                        for (reply in index.commentsOf(comment.id)) CommentRow(reply, index, state, depth = 1) { replyTo = comment }
                    }
                    if (comments.isEmpty() && post?.commentsOff != true) {
                        item { Text("No comments yet. Start the conversation.", color = Aez.colors.text3, modifier = Modifier.padding(20.dp)) }
                    }
                }
                if (post?.commentsOff == true) {
                    Text("Comments are turned off.", color = Aez.colors.text3, modifier = Modifier.navigationBarsPadding().padding(16.dp))
                } else {
                    CommentComposer(replyTo, state, onCancelReply = { replyTo = null }) { text ->
                        val parent = replyTo?.id ?: entity.id
                        replyTo = null
                        Graph.launch { Graph.client.composer.comment(parent, text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Entity, index: Index, state: AppState, depth: Int, onReply: (Entity) -> Unit) {
    val nav = LocalNav.current
    val me = state.me ?: return
    val name = if (comment.dto.authorId == me.id) me.username else state.users[comment.dto.authorId]?.username ?: "someone"
    val liked = index.isLiked(comment.id, me.id)
    val likes = index.likesOf(comment.id).size
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { if (comment.dto.authorId == me.id) menu = true }.padding(start = 14.dp + (depth * 44).dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Avatar(comment.dto.authorId, if (depth == 0) 34.dp else 28.dp, index, name) { nav.go(Screen.Profile(comment.dto.authorId)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RichText(
                comment.payloadAs<CommentPayload>()?.text.orEmpty(), prefix = boldPrefix(name),
                onMention = { n -> state.users.values.firstOrNull { it.username == n }?.let { nav.go(Screen.Profile(it.id)) } },
                onTag = { nav.go(Screen.Hashtag(it)) },
            )
            Row(Modifier.padding(top = 4.dp)) {
                Text(ago(comment.dto.createdAt), fontSize = 12.sp, color = Aez.colors.text3)
                if (likes > 0) Text(if (likes == 1) "   1 like" else "   $likes likes", fontSize = 12.sp, color = Aez.colors.text3, fontWeight = FontWeight.SemiBold)
                Text("   Reply", fontSize = 12.sp, color = Aez.colors.text3, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onReply(comment) })
            }
        }
        Box(Modifier.size(36.dp).clickable { Graph.launch { Graph.client.composer.toggleLike(comment.id) } }, contentAlignment = Alignment.Center) {
            Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Like comment",
                tint = if (liked) Color(0xFFFF3040) else Aez.colors.text3, modifier = Modifier.size(15.dp))
        }
    }
    if (menu) {
        ActionSheet({ menu = false }) {
            SheetItem("Delete comment", Icons.Outlined.Delete, danger = true) {
                menu = false
                Graph.launch { Graph.client.composer.delete(comment.id) }
            }
        }
    }
}

@Composable
fun CommentComposer(replyTo: Entity?, state: AppState, onCancelReply: () -> Unit, onSend: (String) -> Unit) {
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Aez.colors.bg).imePadding().navigationBarsPadding()) {
        Hairline()
        if (replyTo != null) {
            Row(Modifier.fillMaxWidth().background(Aez.colors.elev1).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Replying to ${state.users[replyTo.dto.authorId]?.username ?: me.username}", color = Aez.colors.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("✕", color = Aez.colors.text2, modifier = Modifier.clickable(onClick = onCancelReply))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(me.id, 34.dp, index, me.username)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Aez.colors.elev1).padding(horizontal = 16.dp, vertical = 11.dp)) {
                if (text.isEmpty()) Text("Add a comment…", color = Aez.colors.text3, fontSize = 14.sp)
                BasicTextField(text, { text = it.take(2200) }, textStyle = TextStyle(color = Aez.colors.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Aez.colors.text), modifier = Modifier.fillMaxWidth(), maxLines = 5)
            }
            if (text.isNotBlank()) {
                Text("Post", color = Aez.colors.link, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    onSend(text.trim())
                    text = ""
                }.padding(10.dp))
            }
        }
    }
}

/** The "…" menu on a post: your own posts can be edited, archived and deleted; others' muted. */
@Composable
fun PostMenu(entity: Entity, mine: Boolean, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val composer = Graph.client.composer
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    ActionSheet(onDismiss) {
        if (mine) {
            SheetItem("Edit caption", Icons.Outlined.Edit) { editing = true }
            val post = entity.payloadAs<PostPayload>()
            val reel = entity.payloadAs<ReelPayload>()
            val hidden = post?.hideLikes ?: reel?.hideLikes ?: false
            val off = post?.commentsOff ?: reel?.commentsOff ?: false
            SheetItem(if (hidden) "Show like count" else "Hide like count", Icons.Outlined.VisibilityOff) {
                onDismiss()
                Graph.launch { composer.edit(entity, post?.copy(hideLikes = !hidden) ?: reel!!.copy(hideLikes = !hidden)) }
            }
            SheetItem(if (off) "Turn on commenting" else "Turn off commenting", Icons.Outlined.SpeakerNotesOff) {
                onDismiss()
                Graph.launch { composer.edit(entity, post?.copy(commentsOff = !off) ?: reel!!.copy(commentsOff = !off)) }
            }
            SheetItem(if (entity.dto.archived) "Show on profile" else "Archive", if (entity.dto.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive) {
                onDismiss()
                Graph.launch { composer.setArchived(entity.id, !entity.dto.archived) }
            }
            SheetItem("Delete", Icons.Outlined.Delete, danger = true) { confirmDelete = true }
        } else {
            SheetItem("Mute posts from this account", Icons.AutoMirrored.Outlined.VolumeOff) {
                onDismiss()
                Graph.launch { composer.saveSettings { it.copy(mutedPosts = (it.mutedPosts + entity.dto.authorId).distinct()) } }
            }
            SheetItem("Unfollow", Icons.Outlined.PersonRemove, danger = true) {
                onDismiss()
                Graph.launch { composer.unfollow(entity.dto.authorId) }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete post?", "It's removed from the server for everyone. Copies already on other people's phones disappear when they sync.", "Delete",
            onConfirm = {
                onDismiss()
                Graph.launch { composer.delete(entity.id) }
                if (nav.current is Screen.Post) nav.back()
            },
            onDismiss = { confirmDelete = false })
    }
    if (editing) {
        val current = entity.payloadAs<PostPayload>()?.caption ?: entity.payloadAs<ReelPayload>()?.caption ?: ""
        var caption by remember { mutableStateOf(current) }
        ActionSheet({ editing = false }) {
            AezField(caption, { caption = it.take(2200) }, "Caption", singleLine = false, minLines = 3)
            Spacer(Modifier.size(12.dp))
            AezButton("Save", {
                editing = false
                onDismiss()
                val post = entity.payloadAs<PostPayload>()
                val reel = entity.payloadAs<ReelPayload>()
                Graph.launch { composer.edit(entity, post?.copy(caption = caption) ?: reel!!.copy(caption = caption)) }
            }, Modifier.fillMaxWidth())
        }
    }
}
