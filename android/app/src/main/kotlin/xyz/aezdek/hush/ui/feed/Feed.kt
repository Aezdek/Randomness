package xyz.aezdek.hush.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.client.hasUnseenStory
import xyz.aezdek.hush.client.storyRing
import xyz.aezdek.hush.media.VideoPlayer
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.LivePayload
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ReelPayload
import xyz.aezdek.hush.ui.CreateKind
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.Badge
import xyz.aezdek.hush.ui.components.Empty
import xyz.aezdek.hush.ui.components.Hairline
import xyz.aezdek.hush.ui.components.IconAction
import xyz.aezdek.hush.ui.components.MediaImage
import xyz.aezdek.hush.ui.components.RichText
import xyz.aezdek.hush.ui.components.Ring
import xyz.aezdek.hush.ui.components.VerifiedBadge
import xyz.aezdek.hush.ui.components.ago
import xyz.aezdek.hush.ui.components.boldPrefix
import xyz.aezdek.hush.ui.components.compact
import xyz.aezdek.hush.ui.messages.ShareSheet
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand
import xyz.aezdek.hush.ui.theme.Wordmark

/** Caption, media and flags of a post or a reel, whichever this is. */
class PostView(val caption: String, val media: List<MediaRef>, val location: String?, val hideLikes: Boolean, val commentsOff: Boolean, val tagged: List<String>)

fun Entity.post(): PostView? = when (val p = payload) {
    is PostPayload -> PostView(p.caption, p.media, p.location, p.hideLikes, p.commentsOff, p.taggedUserIds)
    is ReelPayload -> PostView(p.caption, listOfNotNull(p.video), null, p.hideLikes, p.commentsOff, p.taggedUserIds)
    else -> null
}

fun ringOf(userId: String, index: Index, state: AppState): Ring = when {
    index.lives.any { it.dto.authorId == userId } -> Ring.LIVE
    index.stories[userId].isNullOrEmpty() -> Ring.NONE
    !index.hasUnseenStory(userId, state) -> Ring.SEEN
    index.stories[userId].orEmpty().any { it.dto.audience == Audience.CLOSE_FRIENDS && !state.seenStories.containsKey(it.id) } -> Ring.CLOSE_FRIENDS
    else -> Ring.UNSEEN
}

@Composable
fun Home() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val muted = index.settings.mutedPosts.toSet()
    val feed = index.posts.filter { e ->
        !e.dto.archived && e.dto.authorId !in muted && state.users[e.dto.authorId]?.blockedByMe != true &&
            (e.dto.authorId == me.id || state.users[e.dto.authorId]?.followState == "following")
    }
    val unreadChats = state.threads.values.count { index.unreadCount(it, me.id) > 0 }
    val activitySeen = Graph.prefs.lastActivitySeen
    val newActivity = state.followRequests.size + index.activity.count { it.dto.receivedAt > activitySeen }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Aez.colors.glass).statusBarsPadding().height(56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Hush", style = Wordmark.copy(color = Aez.colors.text, fontSize = 30.sp), modifier = Modifier.weight(1f))
            Box {
                IconAction(Icons.Outlined.FavoriteBorder, "Notifications", { nav.go(Screen.Activity) })
                if (newActivity > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(8.dp).background(Color(0xFFFF3040), CircleShape))
            }
            Box {
                IconAction(Icons.Outlined.ChatBubbleOutline, "Messages", { nav.go(Screen.Inbox) })
                Badge(unreadChats, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp))
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    runCatching { Graph.client.engine.sync() }
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
                item { StoriesTray(index, state) }
                item { Hairline() }
                if (feed.isEmpty() && state.initialSyncDone) {
                    item {
                        Empty(Icons.Outlined.PhotoLibrary, "Welcome to Hush", "Follow people to see their photos and videos here, or share your first post.")
                    }
                }
                items(feed, key = { it.id }) { entity -> PostCard(entity, index, state) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun StoriesTray(index: Index, state: AppState) {
    val nav = LocalNav.current
    val me = state.me ?: return
    val ring = index.storyRing(state)
    val lives = index.lives.filter { it.dto.authorId != me.id }
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
    ) {
        item {
            val mine = index.stories[me.id].orEmpty()
            TrayItem("Your story") {
                Box {
                    Avatar(me.id, 68.dp, index, me.username, ring = if (mine.isEmpty()) Ring.NONE else ringOf(me.id, index, state)) {
                        if (mine.isEmpty()) nav.go(Screen.Create(CreateKind.STORY)) else nav.go(Screen.Stories(listOf(me.id)))
                    }
                    if (mine.isEmpty()) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(22.dp).background(Aez.colors.bg, CircleShape).padding(2.dp).background(Color(0xFF3B82F6), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
        }
        items(lives, key = { "live:" + it.id }) { live ->
            val user = state.users[live.dto.authorId]
            TrayItem(user?.username ?: "") {
                Avatar(live.dto.authorId, 68.dp, index, user?.username, ring = Ring.LIVE) {
                    Graph.calls.joinLive(live.dto.authorId, live.payloadAs<LivePayload>()?.callId)
                }
            }
        }
        items(ring, key = { it }) { userId ->
            val user = state.users[userId]
            TrayItem(user?.username ?: "") {
                Avatar(userId, 68.dp, index, user?.username, ring = ringOf(userId, index, state)) {
                    nav.go(Screen.Stories(ring, ring.indexOf(userId)))
                }
            }
        }
    }
}

@Composable
private fun TrayItem(label: String, avatar: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        avatar()
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Aez.colors.text, maxLines = 1)
    }
}

@Composable
fun PostCard(entity: Entity, index: Index, state: AppState, detail: Boolean = false) {
    val nav = LocalNav.current
    val me = state.me ?: return
    val post = entity.post() ?: return
    val author = state.users[entity.dto.authorId]
    val authorName = if (entity.dto.authorId == me.id) me.username else author?.username ?: "someone"
    val liked = index.isLiked(entity.id, me.id)
    val likes = index.likesOf(entity.id)
    val comments = index.commentsOf(entity.id)
    val saved = entity.id in index.savedPostIds()
    val mine = entity.dto.authorId == me.id
    var menu by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var burst by remember { mutableStateOf(0) }
    val like = { Graph.launch { Graph.client.composer.toggleLike(entity.id) } }

    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(entity.dto.authorId, 34.dp, index, authorName, ring = ringOf(entity.dto.authorId, index, state)) {
                nav.go(Screen.Profile(entity.dto.authorId))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { nav.go(Screen.Profile(entity.dto.authorId)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(authorName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Aez.colors.text)
                    if (state.pins[entity.dto.authorId]?.verified == true) {
                        Spacer(Modifier.width(4.dp))
                        VerifiedBadge(13.dp)
                    }
                    Text("  •  ${ago(entity.dto.createdAt)}", fontSize = 13.sp, color = Aez.colors.text3)
                }
                post.location?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Aez.colors.text2) }
            }
            IconAction(Icons.Outlined.MoreHoriz, "More", { menu = true }, size = 22.dp)
        }
        Box {
            Carousel(post.media, onDoubleTap = {
                if (!liked) like()
                burst++
            })
            HeartBurst(burst, Modifier.align(Alignment.Center))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LikeButton(liked, onClick = like)
            if (!post.commentsOff) IconAction(Icons.Outlined.ChatBubbleOutline, "Comment", { nav.go(Screen.Post(entity.id)) })
            IconAction(Icons.AutoMirrored.Outlined.Send, "Share", { share = true }, size = 24.dp)
            Spacer(Modifier.weight(1f))
            IconAction(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Save", { Graph.launch { Graph.client.composer.toggleSaved(entity.id) } })
        }
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if ((!post.hideLikes || mine) && likes.isNotEmpty()) {
                val first = likes.first().dto.authorId.let { if (it == me.id) me.username else state.users[it]?.username }
                val text = when {
                    likes.size == 1 && first != null -> "Liked by $first"
                    first != null -> "Liked by $first and ${compact(likes.size - 1)} ${if (likes.size == 2) "other" else "others"}"
                    else -> "${compact(likes.size)} likes"
                }
                Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Aez.colors.text)
            }
            if (post.caption.isNotBlank()) {
                RichText(
                    post.caption, prefix = boldPrefix(authorName), maxLines = if (detail) Int.MAX_VALUE else 3,
                    onMention = { name -> state.users.values.firstOrNull { it.username == name }?.let { nav.go(Screen.Profile(it.id)) } },
                    onTag = { nav.go(Screen.Hashtag(it)) },
                )
            }
            if (!detail && comments.isNotEmpty() && !post.commentsOff) {
                Text(
                    if (comments.size == 1) "View 1 comment" else "View all ${comments.size} comments",
                    color = Aez.colors.text3, fontSize = 14.sp, modifier = Modifier.clickable { nav.go(Screen.Post(entity.id)) },
                )
            }
        }
    }
    if (menu) PostMenu(entity, mine, onDismiss = { menu = false })
    if (share) ShareSheet(entity, onDismiss = { share = false })
}

/** Photos and videos side by side; the first item's shape (clamped to 4:5 … 1.91:1) sets the height. */
@Composable
fun Carousel(media: List<MediaRef>, onDoubleTap: () -> Unit, modifier: Modifier = Modifier) {
    if (media.isEmpty()) return
    val first = media.first()
    val ratio = if (first.width > 0 && first.height > 0) (first.width.toFloat() / first.height).coerceIn(0.8f, 1.91f) else 1f
    val pager = rememberPagerState { media.size }
    Box(modifier.fillMaxWidth().aspectRatio(ratio)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val ref = media[page]
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }) {
                if (ref.mime.startsWith("video/")) {
                    MediaImage(ref.poster, Modifier.fillMaxSize())
                    VideoPlayer(ref, Modifier.fillMaxSize(), playing = pager.currentPage == page, muted = true)
                    Icon(Icons.Outlined.Videocam, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp))
                } else {
                    MediaImage(ref, Modifier.fillMaxSize())
                }
            }
        }
        if (media.size > 1) {
            Text(
                "${pager.currentPage + 1}/${media.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(media.size) { i ->
                    val active = i == pager.currentPage
                    Box(Modifier.size(if (active) 7.dp else 6.dp).background(Color.White.copy(alpha = if (active) 1f else 0.5f), CircleShape))
                }
            }
        }
    }
}

@Composable
fun LikeButton(liked: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (liked) 1f else 0.94f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium), label = "like")
    IconAction(
        if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (liked) "Unlike" else "Like", onClick,
        modifier = Modifier.scale(scale), tint = if (liked) Color(0xFFFF3040) else Aez.colors.text,
    )
}

/** The big heart that pops on a double tap. */
@Composable
fun HeartBurst(trigger: Int, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        scale.snapTo(0.2f)
        scale.animateTo(1.15f, spring(dampingRatio = 0.45f, stiffness = 400f))
        scale.animateTo(1f, tween(120))
        alpha.animateTo(0f, tween(280, delayMillis = 350))
    }
    Icon(
        Icons.Filled.Favorite, null, tint = Color.White,
        modifier = modifier.size(110.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value },
    )
}

/** Brand-gradient pill used for "Follow" style calls to action in feed contexts. */
@Composable
fun GradientDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(Brand.gradient))
}

fun isVideoPost(entity: Entity) = entity.dto.kind == Kind.REEL || entity.post()?.media?.firstOrNull()?.mime?.startsWith("video/") == true
