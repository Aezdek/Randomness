package xyz.aezdek.hush.ui.reels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.media.VideoPlayer
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.ReelPayload
import xyz.aezdek.hush.ui.CreateKind
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.Empty
import xyz.aezdek.hush.ui.components.IconAction
import xyz.aezdek.hush.ui.components.MediaImage
import xyz.aezdek.hush.ui.components.RichText
import xyz.aezdek.hush.ui.components.compact
import xyz.aezdek.hush.ui.feed.HeartBurst
import xyz.aezdek.hush.ui.messages.ShareSheet

/** Full-screen vertical reels: swipe up for the next, double-tap to like, tap to mute. */
@Composable
fun Reels(startId: String?) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val reels = index.posts.filter { it.dto.kind == Kind.REEL && !it.dto.archived && state.users[it.dto.authorId]?.blockedByMe != true }
    val start = reels.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { reels.size }
    var muted by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (reels.isEmpty()) {
            Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                Empty(Icons.Outlined.Movie, "No reels yet", "Full-screen videos from people you follow show up here.")
            }
        } else {
            VerticalPager(pager, Modifier.fillMaxSize(), key = { reels[it].id }) { page ->
                Reel(reels[page], index, state, active = pager.currentPage == page, muted = muted, onMute = { muted = !muted })
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reels", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconAction(Icons.Outlined.PhotoCamera, "New reel", { nav.go(Screen.Create(CreateKind.REEL)) }, tint = Color.White)
        }
    }
}

@Composable
private fun Reel(entity: Entity, index: Index, state: AppState, active: Boolean, muted: Boolean, onMute: () -> Unit) {
    val nav = LocalNav.current
    val me = state.me ?: return
    val reel = entity.payloadAs<ReelPayload>() ?: return
    val liked = index.isLiked(entity.id, me.id)
    val saved = entity.id in index.savedPostIds()
    val author = if (entity.dto.authorId == me.id) me.username else state.users[entity.dto.authorId]?.username ?: "someone"
    var burst by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var share by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().pointerInput(entity.id) {
        detectTapGestures(onTap = { onMute() }, onDoubleTap = {
            if (!liked) Graph.launch { Graph.client.composer.like(entity.id) }
            burst++
        })
    }) {
        MediaImage(reel.cover ?: reel.video.poster, Modifier.fillMaxSize())
        if (active) VideoPlayer(reel.video, Modifier.fillMaxSize(), playing = true, muted = muted, onProgress = { progress = it })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xAA000000))))
        HeartBurst(burst, Modifier.align(Alignment.Center))
        Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 16.dp).size(22.dp))
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Action(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (reel.hideLikes && entity.dto.authorId != me.id) null else compact(index.likesOf(entity.id).size),
                if (liked) Color(0xFFFF3040) else Color.White) { Graph.launch { Graph.client.composer.toggleLike(entity.id) } }
            if (!reel.commentsOff) Action(Icons.Outlined.ChatBubbleOutline, compact(index.commentsOf(entity.id).size)) { nav.go(Screen.Post(entity.id)) }
            Action(Icons.AutoMirrored.Outlined.Send, null) { share = true }
            Action(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, null) { Graph.launch { Graph.client.composer.toggleSaved(entity.id) } }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.78f).padding(start = 14.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { nav.go(Screen.Profile(entity.dto.authorId)) }) {
                Avatar(entity.dto.authorId, 34.dp, index, author)
                Spacer(Modifier.width(10.dp))
                Text(author, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (reel.caption.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                RichText(reel.caption, style = TextStyle(color = Color.White, fontSize = 14.sp), maxLines = 3, onTag = { nav.go(Screen.Hashtag(it)) })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("$author · Original audio", color = Color.White, fontSize = 12.sp)
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
            color = Color.White, trackColor = Color(0x33FFFFFF))
    }
    if (share) ShareSheet(entity) { share = false }
}

@Composable
private fun Action(icon: ImageVector, label: String?, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconAction(icon, label ?: "", onClick, tint = tint, size = 30.dp)
        if (label != null) Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
