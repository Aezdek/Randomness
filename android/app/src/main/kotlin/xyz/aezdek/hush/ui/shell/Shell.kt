package xyz.aezdek.hush.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.ui.CreateKind
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.Hairline
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand

/** The signed-in app: the current screen, and the glass tab bar on the main screens. */
@Composable
fun Shell() {
    val nav = LocalNav.current
    val holder = rememberSaveableStateHolder()
    var creating by remember { mutableStateOf(false) }
    val screen = nav.current
    val me = Graph.client.state.collectAsState().value.me
    val showBar = screen.tab || screen == Screen.Activity || (screen is Screen.Profile && screen.userId == me?.id && nav.stack.size == 1)
    Box(Modifier.fillMaxSize().background(Aez.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = nav.stack.size to screen,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "screen",
                ) { (depth, target) ->
                    holder.SaveableStateProvider("$depth:$target") { Route(target, onCreate = { creating = true }) }
                }
            }
            if (showBar) BottomBar(screen, onCreate = { creating = true })
        }
    }
    if (creating) CreateSheet(onDismiss = { creating = false })
}

@Composable
private fun Route(screen: Screen, onCreate: () -> Unit) {
    Routes.render(screen, onCreate)
}

/** Screen registry: every feature file registers its composable here. */
object Routes {
    @Composable
    fun render(screen: Screen, onCreate: () -> Unit) {
        when (screen) {
            Screen.Home -> xyz.aezdek.hush.ui.feed.Home()
            is Screen.Post -> xyz.aezdek.hush.ui.feed.PostDetail(screen.id)
            Screen.Inbox -> xyz.aezdek.hush.ui.messages.Inbox()
            is Screen.Chat -> xyz.aezdek.hush.ui.messages.Chat(screen.threadId, screen.focus)
            Screen.NewChat -> xyz.aezdek.hush.ui.messages.NewChat()
            is Screen.ThreadInfo -> xyz.aezdek.hush.ui.messages.ThreadInfo(screen.threadId)
            is Screen.Explore -> xyz.aezdek.hush.ui.explore.Explore()
            is Screen.Hashtag -> xyz.aezdek.hush.ui.explore.Hashtag(screen.tag)
            Screen.Activity -> xyz.aezdek.hush.ui.explore.Activity()
            is Screen.Reels -> xyz.aezdek.hush.ui.reels.Reels(screen.startId)
            is Screen.Profile -> xyz.aezdek.hush.ui.profile.Profile(screen.userId)
            Screen.EditProfile -> xyz.aezdek.hush.ui.profile.EditProfile()
            is Screen.FollowList -> xyz.aezdek.hush.ui.profile.FollowList(screen.userId, screen.followers)
            is Screen.Stories -> xyz.aezdek.hush.ui.stories.StoryViewer(screen.userIds, screen.start)
            is Screen.Highlight -> xyz.aezdek.hush.ui.stories.HighlightViewer(screen.highlightId)
            is Screen.Create -> when (screen.kind) {
                CreateKind.POST -> xyz.aezdek.hush.ui.create.CreatePost()
                CreateKind.STORY -> xyz.aezdek.hush.ui.create.CreateStory()
                CreateKind.REEL -> xyz.aezdek.hush.ui.create.CreateReel()
                CreateKind.NOTE -> xyz.aezdek.hush.ui.create.CreateNote()
            }
            is Screen.Settings -> xyz.aezdek.hush.ui.settings.Settings(screen.section)
            is Screen.Verify -> xyz.aezdek.hush.ui.settings.Verify(screen.userId)
            Screen.Readiness -> xyz.aezdek.hush.ui.settings.Readiness()
        }
    }
}

@Composable
private fun BottomBar(screen: Screen, onCreate: () -> Unit) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me
    Column(Modifier.fillMaxWidth().background(Aez.colors.glass).navigationBarsPadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            Tab(if (screen == Screen.Home) Icons.Filled.Home else Icons.Outlined.Home, "Home") { nav.go(Screen.Home) }
            Tab(if (screen is Screen.Explore) Icons.Filled.Search else Icons.Outlined.Search, "Search") { nav.go(Screen.Explore()) }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onCreate), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 28.dp).clip(RoundedCornerShape(10.dp)).background(Brand.gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "Create", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
                }
            }
            Tab(if (screen is Screen.Reels) Icons.Filled.Movie else Icons.Outlined.Movie, "Reels") { nav.go(Screen.Reels()) }
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable { me?.let { nav.root(Screen.Profile(it.id)) } },
                contentAlignment = Alignment.Center,
            ) {
                val selected = screen is Screen.Profile && screen.userId == me?.id
                Box(
                    Modifier.size(30.dp).then(if (selected) Modifier.background(Aez.colors.text, CircleShape).padding(2.dp) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(me?.id, if (selected) 26.dp else 28.dp, index, me?.username)
                }
            }
        }
    }
}

@Composable
private fun Tab(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Aez.colors.text, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun CreateSheet(onDismiss: () -> Unit) {
    val nav = LocalNav.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Create", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            Option(Icons.Outlined.GridView, "Post", "Photos and videos on your profile") { onDismiss(); nav.go(Screen.Create(CreateKind.POST)) }
            Option(Icons.Outlined.AutoStories, "Story", "Disappears from the tray after 24 hours") { onDismiss(); nav.go(Screen.Create(CreateKind.STORY)) }
            Option(Icons.Outlined.Movie, "Reel", "A full-screen vertical video") { onDismiss(); nav.go(Screen.Create(CreateKind.REEL)) }
            Option(Icons.AutoMirrored.Outlined.StickyNote2, "Note", "A short thought above your chats") { onDismiss(); nav.go(Screen.Create(CreateKind.NOTE)) }
            Option(Icons.Outlined.Videocam, "Live", "Go live to your followers", live = true) { onDismiss(); Graph.calls.startLive() }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, subtitle: String, live: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(if (live) Brand.live.copy(alpha = 0.15f) else Aez.colors.elev2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (live) Brand.live else Aez.colors.text, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Aez.colors.text)
            Text(subtitle, fontSize = 13.sp, color = Aez.colors.text2)
        }
    }
}
