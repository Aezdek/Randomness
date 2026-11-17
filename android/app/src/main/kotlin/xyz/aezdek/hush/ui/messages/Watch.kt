package xyz.aezdek.hush.ui.messages

import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.client.Jellyfin
import xyz.aezdek.hush.client.JellyfinItem
import xyz.aezdek.hush.client.Watching
import xyz.aezdek.hush.client.sendWatchState
import xyz.aezdek.hush.client.startWatching
import xyz.aezdek.hush.client.watching
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.WatchParty
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.theme.Aez

// Movie night: a film from your own Jellyfin, played on each phone straight from it and kept in step through the
// chat. Hush's server takes no part: it never sees the film, the Jellyfin address or anyone's sign-in there.

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Settings → Movie night: this phone's sign-in to Jellyfin. */
@Composable
internal fun JellyfinCard() {
    val scope = rememberCoroutineScope()
    val session by Graph.prefs.jellyfin.collectAsState()
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    xyz.aezdek.hush.ui.settings.Card(
        "Jellyfin",
        note = "Watch films from your own Jellyfin together in a chat. Each person signs in to Jellyfin on their own phone; " +
            "Hush's server never sees the film, the address or this sign-in.",
    ) {
        val current = session
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Aez.colors.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Signed in to ${Jellyfin.baseOf(current.base)?.host ?: "Jellyfin"}", fontSize = 15.sp, color = Aez.colors.text, modifier = Modifier.weight(1f))
                AezButton("Sign out", { Graph.prefs.setJellyfin(null) }, style = ButtonStyle.SECONDARY, small = true)
            }
            return@Card
        }
        AezField(address, { address = it.take(200) }, "Jellyfin address (jellyfin.example.com)")
        AezField(name, { name = it.take(100) }, "Jellyfin username")
        AezField(password, { password = it.take(200) }, "Jellyfin password", password = true)
        AezButton("Sign in to Jellyfin", {
            busy = true
            scope.launch {
                Graph.attempt {
                    Graph.prefs.setJellyfin(Jellyfin(Graph.http).signIn(address, name, password))
                    password = ""
                    Graph.toast("Signed in to Jellyfin")
                }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = address.isNotBlank() && name.isNotBlank(), busy = busy)
    }
}

/** Picking something to watch: this phone's Jellyfin library, searched as you type. */
@Composable
internal fun WatchSheet(threadId: String, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val session by Graph.prefs.jellyfin.collectAsState()
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    val current = session
    LaunchedEffect(current, query) {
        if (current == null) return@LaunchedEffect
        delay(350)
        items = Graph.attempt { Jellyfin(Graph.http).items(current, query) } ?: emptyList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Movie night", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
            if (current == null) {
                Text("Sign in to your Jellyfin first. Everyone watching needs to do the same on their own phone.", fontSize = 14.sp, color = Aez.colors.text2)
                AezButton("Open Settings", {
                    onDismiss()
                    nav.go(Screen.Settings("movie"))
                }, Modifier.fillMaxWidth())
            } else {
                AezField(query, { query = it.take(80) }, "Search films and episodes")
                val found = items
                when {
                    found == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
                    found.isEmpty() -> Text("Nothing found.", fontSize = 14.sp, color = Aez.colors.text2)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(found, key = { it.id }) { item ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                    Graph.launch { Graph.client.composer.startWatching(threadId, item.id, item.title, item.runtimeMs) }
                                    onDismiss()
                                }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Movie, null, tint = Aez.colors.text2, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(item.title, fontSize = 15.sp, color = Aez.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (item.runtimeMs > 0) Text(clock(item.runtimeMs), fontSize = 12.sp, color = Aez.colors.text3)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A movie night in the conversation: what it is, where it is, and a way in. */
@Composable
internal fun WatchCard(message: Entity, party: WatchParty?, index: Index, modifier: Modifier) {
    if (!Watching.valid(party)) {
        Text("A movie night that couldn't be shown", fontSize = 13.sp, color = Aez.colors.text3, modifier = modifier.padding(8.dp))
        return
    }
    var open by remember { mutableStateOf(false) }
    val now = remember(index, message.id) { index.watching(message) }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.widthIn(max = 300.dp).clip(shape).background(Aez.colors.bubbleOther).border(1.dp, Aez.colors.border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Movie, null, tint = Aez.colors.text2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Movie night", fontSize = 12.sp, color = Aez.colors.text2)
        }
        Text(Watching.title(party!!.title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Aez.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text((if (now.playing) "Playing" else "Paused") + " at ${clock(now.positionMs)}", fontSize = 13.sp, color = Aez.colors.text2)
        AezButton("Join", { open = true }, Modifier.fillMaxWidth(), small = true)
    }
    if (open) WatchPlayer(message, party!!) { open = false }
}

/**
 * The film, full screen. Play, pause and jumps made here go to the chat, and the chat's newest one is followed here,
 * so everyone watching is at the same place give or take a couple of seconds.
 */
@OptIn(UnstableApi::class)
@Composable
private fun WatchPlayer(message: Entity, party: WatchParty, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by Graph.prefs.jellyfin.collectAsState()
    val index by Graph.client.index.collectAsState()
    val url = session?.let { Jellyfin.streamUrl(it, party.itemId) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (url == null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sign in to your Jellyfin to watch: Settings → Movie night.", color = Color.White, fontSize = 15.sp)
                    AezButton("Close", onClose, style = ButtonStyle.SECONDARY)
                }
                return@Box
            }
            // What this phone last did or was told, so its own echo isn't sent round again.
            var following by remember { mutableStateOf(false) }
            var sending by remember { mutableStateOf<Job?>(null) }
            val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() } }
            fun tell() {
                if (following) return
                sending?.cancel()
                sending = scope.launch {
                    delay(400)
                    Graph.attempt { Graph.client.composer.sendWatchState(message, player.playWhenReady, player.currentPosition) }
                }
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = tell()
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) tell()
                    }
                    override fun onPlayerError(error: PlaybackException) = Graph.toast("Couldn't play this from Jellyfin", error = true)
                }
                player.addListener(listener)
                val window = (context as? android.app.Activity)?.window
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    player.removeListener(listener)
                    player.release()
                }
            }
            // The chat's newest play, pause or jump: followed when it changes.
            val states = index.messages[message.dto.threadId].orEmpty().count { m ->
                m.payloadAs<MessagePayload>()?.let { it.type == MessageType.WATCH_STATE && it.replyTo == message.id } == true
            }
            LaunchedEffect(player, states) {
                val target = index.watching(message)
                following = true
                if (kotlin.math.abs(player.currentPosition - target.positionMs) > Watching.DRIFT_MS) player.seekTo(target.positionMs)
                player.playWhenReady = target.playing
                delay(900)
                following = false
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = true
                        this.player = player
                    }
                },
            )
            Icon(
                Icons.Outlined.Close, "Close", tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).size(44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClose).padding(10.dp),
            )
        }
    }
}
