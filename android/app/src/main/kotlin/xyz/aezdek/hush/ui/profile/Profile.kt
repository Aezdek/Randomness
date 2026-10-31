package xyz.aezdek.hush.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.media.MediaKind
import xyz.aezdek.hush.media.MediaPrep
import xyz.aezdek.hush.protocol.FollowState
import xyz.aezdek.hush.protocol.HighlightPayload
import xyz.aezdek.hush.protocol.HighlightStory
import xyz.aezdek.hush.protocol.IdList
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.ProfilePayload
import xyz.aezdek.hush.protocol.ReelPayload
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.UserDto
import xyz.aezdek.hush.ui.CreateKind
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.ActionSheet
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.ConfirmDialog
import xyz.aezdek.hush.ui.components.Empty
import xyz.aezdek.hush.ui.components.IconAction
import xyz.aezdek.hush.ui.components.MediaImage
import xyz.aezdek.hush.ui.components.RichText
import xyz.aezdek.hush.ui.components.SheetItem
import xyz.aezdek.hush.ui.components.TopBar
import xyz.aezdek.hush.ui.components.VerifiedBadge
import xyz.aezdek.hush.ui.components.compact
import xyz.aezdek.hush.ui.feed.post
import xyz.aezdek.hush.ui.feed.ringOf
import xyz.aezdek.hush.ui.messages.PersonRow
import xyz.aezdek.hush.ui.messages.ToggleRow
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand

private enum class Tab { POSTS, REELS, TAGGED, SAVED }

/** The picture a grid tile shows for a post or reel. */
fun coverOf(entity: Entity): MediaRef? {
    entity.payloadAs<ReelPayload>()?.let { return it.cover ?: it.video.poster }
    val first = entity.post()?.media?.firstOrNull() ?: return null
    return if (first.mime.startsWith("video/")) first.poster else first
}

@Composable
fun FollowButton(user: UserDto, modifier: Modifier = Modifier, small: Boolean = true) {
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            Graph.attempt { block() }
            busy = false
        }
    }
    when (user.followState) {
        FollowState.FOLLOWING -> AezButton("Following", { confirm = true }, modifier, style = ButtonStyle.SECONDARY, small = small, busy = busy)
        FollowState.REQUESTED -> AezButton("Requested", { run { Graph.client.composer.unfollow(user.id) } }, modifier, style = ButtonStyle.SECONDARY, small = small, busy = busy)
        else -> AezButton(if (user.followsMe == FollowState.FOLLOWING) "Follow back" else "Follow", { run { Graph.client.composer.follow(user.id) } },
            modifier, style = ButtonStyle.BRAND, small = small, busy = busy)
    }
    if (confirm) {
        ConfirmDialog("Unfollow @${user.username}?", if (user.isPrivate) "You'll need to ask again to see their posts." else "Their posts leave your feed.",
            "Unfollow", onConfirm = { run { Graph.client.composer.unfollow(user.id) } }, onDismiss = { confirm = false })
    }
}

@Composable
fun Profile(userId: String) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val isMe = userId == me.id
    val user = state.users[userId]
    val username = if (isMe) me.username else user?.username ?: ""
    val profile = index.profile(userId)
    val isPrivate = if (isMe) me.isPrivate else user?.isPrivate == true
    val canSee = isMe || !isPrivate || user?.followState == FollowState.FOLLOWING
    var tab by rememberSaveable { mutableStateOf(Tab.POSTS) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val counts by produceState<Pair<Int, Int>?>(null, userId, canSee, user?.followState, state.followRequests) {
        if (!canSee) return@produceState
        value = runCatching {
            val followers: IdList = Graph.client.api.get("/users/$userId/followers")
            val following: IdList = Graph.client.api.get("/users/$userId/following")
            followers.ids.size to following.ids.size
        }.getOrNull()
    }
    val posts = index.posts.filter { it.dto.authorId == userId && !it.dto.archived }
    val grid = when (tab) {
        Tab.POSTS -> posts
        Tab.REELS -> posts.filter { it.dto.kind == Kind.REEL }
        Tab.TAGGED -> index.posts.filter { userId in (it.post()?.tagged.orEmpty()) && !it.dto.archived }
        Tab.SAVED -> index.savedPostIds().let { saved -> index.posts.filter { it.id in saved } }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            username, back = nav.stack.size > 1,
            actions = {
                if (isMe) {
                    IconAction(Icons.Outlined.AddBox, "Create", { nav.go(Screen.Create(CreateKind.POST)) })
                    IconAction(Icons.Outlined.Menu, "Settings", { nav.go(Screen.Settings()) })
                } else if (user != null && !user.deleted) {
                    IconAction(Icons.Outlined.MoreHoriz, "Options", { menu = true })
                }
            },
        )
        if (user == null && !isMe) {
            Empty(Icons.Outlined.AccountBox, "Profile not found", "This account may have been deleted.")
            return@Column
        }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    if (!isMe && state.pins[userId]?.changedFrom != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33FFB020))
                                .clickable { nav.go(Screen.Verify(userId)) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = Color(0xFFFFB020))
                            Spacer(Modifier.width(10.dp))
                            Text("@$username's security code changed. Tap to verify it's really them.", fontSize = 13.sp, color = Aez.colors.text)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ring = ringOf(userId, index, state)
                        Avatar(userId, 88.dp, index, username, ring = ring) {
                            if (ring != xyz.aezdek.hush.ui.components.Ring.NONE) nav.go(Screen.Stories(listOf(userId)))
                        }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat(posts.size, if (posts.size == 1) "post" else "posts") {}
                            Stat(counts?.first, if (counts?.first == 1) "follower" else "followers") { if (canSee) nav.go(Screen.FollowList(userId, followers = true)) }
                            Stat(counts?.second, "following") { if (canSee) nav.go(Screen.FollowList(userId, followers = false)) }
                        }
                    }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile?.displayName?.takeIf { it.isNotBlank() } ?: username, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Aez.colors.text)
                            if (state.pins[userId]?.verified == true) {
                                Spacer(Modifier.width(4.dp))
                                VerifiedBadge()
                            }
                            profile?.pronouns?.takeIf { it.isNotBlank() }?.let { Text("  $it", fontSize = 13.sp, color = Aez.colors.text3) }
                        }
                        profile?.bio?.takeIf { it.isNotBlank() }?.let {
                            RichText(it, onTag = { tag -> nav.go(Screen.Hashtag(tag)) },
                                onMention = { n -> state.users.values.firstOrNull { u -> u.username == n }?.let { u -> nav.go(Screen.Profile(u.id)) } })
                        }
                        val context = LocalContext.current
                        profile?.link?.takeIf { it.isNotBlank() }?.let { link ->
                            Text(link.removePrefix("https://").removePrefix("http://"), color = Aez.colors.link, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                modifier = Modifier.clickable {
                                    val url = if (link.startsWith("http")) link else "https://$link"
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                })
                        }
                        if (!isMe && user?.followsMe == FollowState.FOLLOWING) {
                            Text("Follows you", fontSize = 12.sp, color = Aez.colors.text2, modifier = Modifier.padding(top = 6.dp)
                                .clip(RoundedCornerShape(8.dp)).background(Aez.colors.elev2).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isMe) {
                            AezButton("Edit profile", { nav.go(Screen.EditProfile) }, Modifier.weight(1f), style = ButtonStyle.SECONDARY, small = true)
                            AezButton("Security code", { nav.go(Screen.Verify(me.id)) }, Modifier.weight(1f), style = ButtonStyle.SECONDARY, small = true)
                        } else if (user != null && !user.deleted) {
                            if (user.blockedByMe) {
                                AezButton("Unblock", { Graph.launch { Graph.client.composer.unblock(userId) } }, Modifier.weight(1f), style = ButtonStyle.SECONDARY, small = true)
                            } else {
                                FollowButton(user, Modifier.weight(1f))
                                AezButton("Message", {
                                    Graph.launch {
                                        val id = Graph.client.composer.openDirect(userId)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { nav.go(Screen.Chat(id)) }
                                    }
                                }, Modifier.weight(1f), style = ButtonStyle.SECONDARY, small = true)
                            }
                        }
                    }
                    if (canSee) Highlights(userId, isMe, index, state)
                    if (canSee) {
                        Row(Modifier.fillMaxWidth()) {
                            val tabs = buildList {
                                add(Tab.POSTS to Icons.Outlined.GridOn)
                                add(Tab.REELS to Icons.Outlined.Movie)
                                if (isMe) add(Tab.SAVED to Icons.Outlined.BookmarkBorder)
                                add(Tab.TAGGED to Icons.Outlined.AccountBox)
                            }
                            for ((t, icon) in tabs) {
                                Column(Modifier.weight(1f).clickable { tab = t }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(icon, t.name, tint = if (tab == t) Aez.colors.text else Aez.colors.text3, modifier = Modifier.padding(12.dp).size(24.dp))
                                    Box(Modifier.fillMaxWidth().height(1.5.dp).background(if (tab == t) Aez.colors.text else Color.Transparent))
                                }
                            }
                        }
                    }
                }
            }
            if (!canSee) {
                item(span = { GridItemSpan(3) }) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp).border(2.dp, Aez.colors.text, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Lock, null, tint = Aez.colors.text, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("This account is private", style = MaterialTheme.typography.titleMedium)
                        Text("Follow to see their photos and videos.", color = Aez.colors.text2, fontSize = 14.sp)
                    }
                }
            } else if (grid.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    when (tab) {
                        Tab.SAVED -> Empty(Icons.Outlined.BookmarkBorder, "Save posts for later", "Only you can see what you've saved.")
                        Tab.TAGGED -> Empty(Icons.Outlined.AccountBox, "Photos of you", "When people tag you in posts, they'll appear here.")
                        Tab.REELS -> Empty(Icons.Outlined.Movie, "No reels yet")
                        Tab.POSTS -> Empty(Icons.Outlined.PhotoCamera, if (isMe) "Share your first photo" else "No posts yet",
                            if (isMe) "When you share photos and videos, they'll appear on your profile." else null)
                    }
                }
            } else {
                items(grid, key = { it.id }) { entity -> GridTile(entity, reels = tab == Tab.REELS) }
            }
        }
    }
    if (menu && user != null) {
        ActionSheet({ menu = false }) {
            SheetItem("Verify security code", Icons.Outlined.Shield) { menu = false; nav.go(Screen.Verify(userId)) }
            val mutedStories = userId in index.settings.mutedStories
            SheetItem(if (mutedStories) "Unmute stories" else "Mute stories", Icons.Outlined.VisibilityOff) {
                menu = false
                Graph.launch {
                    Graph.client.composer.saveSettings { s ->
                        s.copy(mutedStories = if (mutedStories) s.mutedStories - userId else (s.mutedStories + userId).distinct())
                    }
                }
            }
            if (user.followsMe == FollowState.FOLLOWING) SheetItem("Remove follower", Icons.Outlined.PersonRemove, danger = true) { menu = false; confirm = "remove" }
            SheetItem(if (user.blockedByMe) "Unblock" else "Block", Icons.Outlined.Block, danger = true) {
                menu = false
                if (user.blockedByMe) Graph.launch { Graph.client.composer.unblock(userId) } else confirm = "block"
            }
        }
    }
    when (confirm) {
        "block" -> ConfirmDialog("Block @$username?", "They won't be able to find your profile, posts or stories, or message you. They won't be told.", "Block",
            onConfirm = { Graph.launch { Graph.client.composer.block(userId) } }, onDismiss = { confirm = null })
        "remove" -> ConfirmDialog("Remove follower?", "@$username won't be told. Their app may still show what it already downloaded.", "Remove",
            onConfirm = { Graph.launch { Graph.client.composer.removeFollower(userId) } }, onDismiss = { confirm = null })
    }
}

@Composable
private fun Stat(value: Int?, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value?.let { compact(it) } ?: "–", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Aez.colors.text)
        Text(label, fontSize = 13.sp, color = Aez.colors.text2)
    }
}

@Composable
fun GridTile(entity: Entity, reels: Boolean = false) {
    val nav = LocalNav.current
    Box(Modifier.fillMaxWidth().aspectRatio(if (reels) 9f / 16f else 1f).clickable {
        if (entity.dto.kind == Kind.REEL && reels) nav.go(Screen.Reels(entity.id)) else nav.go(Screen.Post(entity.id))
    }) {
        MediaImage(coverOf(entity), Modifier.fillMaxSize(), maxEdge = 480)
        val many = (entity.post()?.media?.size ?: 0) > 1
        val video = entity.dto.kind == Kind.REEL || entity.post()?.media?.firstOrNull()?.mime?.startsWith("video/") == true
        if (many || video) {
            Icon(if (many) Icons.Filled.Collections else Icons.Filled.Movie, null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
        }
    }
}

@Composable
private fun Highlights(userId: String, isMe: Boolean, index: Index, state: AppState) {
    val nav = LocalNav.current
    val list = index.highlights[userId].orEmpty()
    var creating by remember { mutableStateOf(false) }
    if (list.isEmpty() && !isMe) return
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (isMe) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable { creating = true }) {
                    Box(Modifier.size(64.dp).border(1.dp, Aez.colors.borderStrong, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, "New highlight", tint = Aez.colors.text, modifier = Modifier.size(28.dp))
                    }
                    Text("New", fontSize = 12.sp, color = Aez.colors.text, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        items(list, key = { it.id }) { highlight ->
            val payload = highlight.payloadAs<HighlightPayload>() ?: return@items
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable { nav.go(Screen.Highlight(highlight.id)) }) {
                Box(Modifier.size(64.dp).border(1.dp, Aez.colors.borderStrong, CircleShape).padding(3.dp).clip(CircleShape)) {
                    MediaImage(payload.cover, Modifier.fillMaxSize(), maxEdge = 240)
                }
                Text(payload.title, fontSize = 12.sp, color = Aez.colors.text, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (creating) NewHighlight(index, state) { creating = false }
}

/** Pick stories from your archive (they outlive the 24 hours through the highlight's keys). */
@Composable
private fun NewHighlight(index: Index, state: AppState, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    ActionSheet(onDone) {
        Text("New highlight", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
        AezField(title, { title = it.take(30) }, "Title")
        Spacer(Modifier.height(10.dp))
        if (index.myStoryArchive.isEmpty()) Text("Share a story first: highlights are made from your archive.", color = Aez.colors.text2, modifier = Modifier.padding(8.dp))
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 360.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(index.myStoryArchive, key = { it.id }) { story ->
                val p = story.payloadAs<StoryPayload>()
                val selected = story.id in picked
                Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(6.dp)).clickable {
                    picked = if (selected) picked - story.id else picked + story.id
                }) {
                    if (p?.media != null) MediaImage(p.media?.poster ?: p.media, Modifier.fillMaxSize(), maxEdge = 240)
                    else Box(Modifier.fillMaxSize().background(Brand.gradient), contentAlignment = Alignment.Center) {
                        Text(p?.text?.text.orEmpty(), color = Color.White, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 4)
                    }
                    if (selected) Box(Modifier.fillMaxSize().background(Aez.colors.accent.copy(alpha = 0.4f)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AezButton("Create", {
            val stories = picked.mapNotNull { id -> state.entities[id] }
            val cover = stories.firstNotNullOfOrNull { it.payloadAs<StoryPayload>()?.media?.let { m -> m.poster ?: m } }
            onDone()
            Graph.launch {
                Graph.client.composer.createHighlight(HighlightPayload(title.ifBlank { "Highlights" }, cover, stories.map { HighlightStory(it.id, B64.encode(it.key)) }))
            }
        }, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty())
    }
}

@Composable
fun EditProfile() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val current = index.profile(me.id) ?: ProfilePayload()
    var name by remember { mutableStateOf(current.displayName) }
    var pronouns by remember { mutableStateOf(current.pronouns) }
    var bio by remember { mutableStateOf(current.bio) }
    var link by remember { mutableStateOf(current.link) }
    var avatar by remember { mutableStateOf(current.avatar) }
    var uploading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            Graph.attempt {
                val side = MediaPrep.decode(context, uri, 1280)
                val edge = minOf(side.width, side.height).toFloat()
                val crop = android.graphics.RectF(
                    (side.width - edge) / 2f / side.width, (side.height - edge) / 2f / side.height,
                    (side.width + edge) / 2f / side.width, (side.height + edge) / 2f / side.height,
                )
                val prepared = MediaPrep.prepareBitmap(context, side, MediaKind.AVATAR, xyz.aezdek.hush.media.ImageEdit(crop = crop))
                side.recycle()
                avatar = Graph.client.media.upload(prepared)
            }
            uploading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Edit profile")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    if (avatar != null) MediaImage(avatar, Modifier.fillMaxSize(), maxEdge = 320) else Avatar(me.id, 96.dp, index, me.username)
                }
                Text(if (uploading) "Encrypting…" else "Change photo", color = Aez.colors.link, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp).clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            }
            AezField(name, { name = it.take(60) }, "Name")
            AezField(pronouns, { pronouns = it.take(30) }, "Pronouns")
            AezField(bio, { bio = it.take(150) }, "Bio", singleLine = false, minLines = 3, supporting = "${bio.length}/150")
            AezField(link, { link = it.take(200) }, "Link")
            ToggleRow(Icons.Outlined.Lock, "Private account", me.isPrivate,
                "Only followers you approve see your posts and stories. Switching re-seals your content.") {
                Graph.launch { Graph.client.updateMe(isPrivate = !me.isPrivate) }
            }
        }
        AezButton("Save", {
            saving = true
            scope.launch {
                Graph.attempt { Graph.client.composer.saveProfile(ProfilePayload(name.trim(), bio.trim(), link.trim(), pronouns.trim(), avatar)) }
                saving = false
                nav.back()
            }
        }, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), busy = saving, enabled = !uploading)
    }
}

@Composable
fun FollowList(userId: String, followers: Boolean) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val ids by produceState<List<String>?>(null, userId, followers) {
        value = runCatching { Graph.client.api.get<IdList>("/users/$userId/${if (followers) "followers" else "following"}").ids }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (followers) "Followers" else "Following")
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
            items(ids.orEmpty().mapNotNull { state.users[it] }, key = { it.id }) { user ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PersonRow(user, index) { nav.go(Screen.Profile(user.id)) } }
                    if (user.id != state.me?.id) FollowButton(user, Modifier.padding(end = 16.dp))
                }
            }
        }
    }
}
