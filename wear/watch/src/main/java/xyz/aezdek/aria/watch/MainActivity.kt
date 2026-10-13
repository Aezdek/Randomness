package xyz.aezdek.aria.watch

import android.Manifest
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import androidx.compose.ui.graphics.vector.ImageVector
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.aria.core.Track
import xyz.aezdek.aria.core.Updater
import xyz.aezdek.aria.core.formatTime

/** The song a details screen shows. */
private object Selection {
    var track: Track? = null
}

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerConnection.connect(this)
        Downloads.syncNow(this)
        PhoneLink.importPending(this)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val openPlayer = intent?.getStringExtra("open") == "player"
        setContent { AriaWatch(openPlayer) }
    }
}

/** Artwork for screenshots, which can't load images from the network. */
val LocalArtworkPreview = staticCompositionLocalOf<((Track) -> Painter)?> { null }

@Composable
fun AriaWatchTheme(content: @Composable () -> Unit) {
    val changes by Prefs.changes.collectAsState()
    val accent = remember(changes) { Prefs.accentColor }
    MaterialTheme(colors = Colors(primary = accent, secondary = accent, onPrimary = Color.White), content = content)
}

@Composable
fun AriaWatch(openPlayer: Boolean) {
    AriaWatchTheme {
        val nav = rememberSwipeDismissableNavController()
        LaunchedEffect(openPlayer) { if (openPlayer) nav.navigate("player") }
        SwipeDismissableNavHost(navController = nav, startDestination = "home") {
            composable("home") { HomeScreen(onOpen = { nav.navigate(it) }) }
            composable("downloads") { DownloadsScreen(onPlayer = { nav.navigate("player") }) }
            composable("saved") { SavedScreen(onTrack = { Selection.track = it; nav.navigate("track") }) }
            composable("search") { SearchScreen(onTrack = { Selection.track = it; nav.navigate("track") }) }
            composable("track") { TrackScreen(Selection.track, onPlayer = { nav.navigate("player") }, onRemoved = { nav.popBackStack() }) }
            composable("player") { PlayerScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}

/** A scrolling list with the clock on top and a scroll indicator, as Wear OS screens should have. */
@Composable
private fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(state = state, modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun MenuChip(label: String, icon: ImageVector, secondary: String? = null, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        icon = { Icon(icon, contentDescription = null) },
        colors = ChipDefaults.secondaryChipColors(),
        onClick = onClick,
    )
}

@Composable
private fun Artwork(track: Track, size: Int, modifier: Modifier = Modifier) {
    val shaped = modifier.size(size.dp).clip(RoundedCornerShape((size / 6).dp)).background(Color(0xFF26262E))
    LocalArtworkPreview.current?.let { preview ->
        Image(preview(track), contentDescription = null, contentScale = ContentScale.Crop, modifier = shaped)
        return
    }
    val local = Library.artFile(track.id).takeIf { it.exists() }
    AsyncImage(
        model = local ?: Prefs.server.artworkUrl(track, large = size > 60),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size.dp).clip(RoundedCornerShape((size / 6).dp)).background(Color(0xFF26262E)),
    )
}

@Composable
private fun TrackChip(track: Track, secondary: String, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Artwork(track, 32) },
        colors = ChipDefaults.secondaryChipColors(),
        onClick = onClick,
    )
}

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val tracks by Library.tracks.collectAsState()
    val controller = PlayerConnection.controller
    val nowPlaying = Items.track(controller?.currentMediaItem)
    ListScreen {
        item { ListHeader { Text("Aria", fontWeight = FontWeight.Bold, fontSize = 18.sp) } }
        if (nowPlaying != null) item { MenuChip("Now Playing", Icons.Rounded.MusicNote, nowPlaying.title) { onOpen("player") } }
        item { MenuChip("Downloads", Icons.Rounded.LibraryMusic, "${tracks.size} song${if (tracks.size == 1) "" else "s"}") { onOpen("downloads") } }
        item { MenuChip("Saved", Icons.Rounded.Star, "From the Aria website") { onOpen("saved") } }
        item { MenuChip("Search", Icons.Rounded.Search) { onOpen("search") } }
        item { MenuChip("Settings", Icons.Rounded.Settings) { onOpen("settings") } }
    }
}

@Composable
fun DownloadsScreen(onPlayer: () -> Unit) {
    val tracks by Library.tracks.collectAsState()
    ListScreen {
        item { ListHeader { Text("Downloads") } }
        if (tracks.isEmpty()) {
            item { Text("Nothing downloaded yet. Download from Saved or Search, or send songs from the phone app.", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2, modifier = Modifier.padding(12.dp)) }
        } else {
            item {
                CompactChip(label = { Text("Shuffle all") }, icon = { Icon(Icons.Rounded.PlayArrow, null) }, onClick = {
                    PlayerConnection.play(tracks.shuffled(), 0)
                    onPlayer()
                })
            }
        }
        itemsIndexed(tracks) { i, track ->
            TrackChip(track, track.artist.ifBlank { "YouTube" }) {
                PlayerConnection.play(tracks, i)
                onPlayer()
            }
        }
    }
}

@Composable
private fun statusOf(track: Track, progress: Map<String, Float>): String {
    progress[track.id]?.let { return "Downloading ${(it * 100).toInt()}%" }
    return if (Library.has(track.id)) "Downloaded · ${track.artist}" else track.artist.ifBlank { "YouTube" }
}

@Composable
fun SavedScreen(onTrack: (Track) -> Unit) {
    val context = LocalContext.current
    val changes by Prefs.changes.collectAsState()
    val progress by Library.progress.collectAsState()
    Library.tracks.collectAsState().value // recompose when downloads finish
    val saved = remember(changes) { Prefs.saved }
    ListScreen {
        item { ListHeader { Text("Saved") } }
        if (!Prefs.server.configured) {
            item { Text("Set the Aria server in Settings, or send it from the phone app.", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2, modifier = Modifier.padding(12.dp)) }
            return@ListScreen
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(label = { Text("Sync") }, icon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { Downloads.syncNow(context) })
                CompactChip(label = { Text("Get all") }, icon = { Icon(Icons.Rounded.CloudDownload, null) }, onClick = {
                    saved.forEach { Downloads.download(context, it) }
                })
            }
        }
        if (saved.isEmpty()) {
            item { Text("Nothing saved. Add songs on the Aria website.", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2, modifier = Modifier.padding(12.dp)) }
        }
        itemsIndexed(saved) { _, track -> TrackChip(track, statusOf(track, progress)) { onTrack(track) } }
    }
}

@Composable
fun SearchScreen(onTrack: (Track) -> Unit, initialQuery: String = "", initialResults: List<Track> = emptyList()) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(initialResults) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress by Library.progress.collectAsState()
    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence("q")?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            query = text
            busy = true
            error = null
            scope.launch {
                runCatching { Prefs.server.search(text, 20) }
                    .onSuccess { results = it }
                    .onFailure { error = it.message ?: "Search failed" }
                busy = false
            }
        }
    }
    fun ask() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("q").setLabel("Song or artist").build()))
        input.launch(intent)
    }
    ListScreen {
        item { ListHeader { Text("Search") } }
        item { MenuChip(if (query.isEmpty()) "Search YouTube" else query, Icons.Rounded.Search, if (query.isEmpty()) "Speak or type" else "Tap to search again") { ask() } }
        if (!Prefs.server.configured) item { Text("Search needs the Aria server. Set it in Settings.", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) }
        if (busy) item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
        error?.let { item { Text(it, color = MaterialTheme.colors.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) } }
        itemsIndexed(results) { _, track -> TrackChip(track, statusOf(track, progress)) { onTrack(track) } }
    }
}

@Composable
fun TrackScreen(track: Track?, onPlayer: () -> Unit, onRemoved: () -> Unit) {
    track ?: return
    val context = LocalContext.current
    val progress by Library.progress.collectAsState()
    val downloaded = Library.tracks.collectAsState().value.any { it.id == track.id }
    ListScreen {
        item { Artwork(track, 72) }
        item { Text(track.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        item { Text(track.subtitle, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (downloaded) "Play" else "Stream") },
                icon = { Icon(Icons.Rounded.PlayArrow, null) },
                colors = ChipDefaults.primaryChipColors(),
                onClick = { PlayerConnection.play(listOf(track), 0); onPlayer() },
            )
        }
        item {
            val p = progress[track.id]
            when {
                p != null -> MenuChip("Downloading ${(p * 100).toInt()}%", Icons.Rounded.CloudDownload) {}
                downloaded -> MenuChip("Remove download", Icons.Rounded.Delete) { Library.remove(track.id); onRemoved() }
                else -> MenuChip("Download", Icons.Rounded.CloudDownload) { Downloads.download(context, track) }
            }
        }
    }
}

private fun hasHeadphones(context: Context): Boolean {
    val am = context.getSystemService(AudioManager::class.java)
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }
}

/** Opens Wear OS's Bluetooth picker, which closes again once earbuds connect. */
private fun connectHeadphones(context: Context) {
    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("EXTRA_CONNECTION_ONLY", true)
        putExtra("EXTRA_CLOSE_ON_CONNECT", true)
        putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
    })
}

@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val controller = PlayerConnection.controller
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }
    tick.let { } // read so the screen refreshes twice a second

    val track = Items.track(controller?.currentMediaItem)
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var volume by remember { mutableFloatStateOf(0f) }
    var volumeShownAt by remember { mutableStateOf(0L) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    PlayerContent(
        track = track,
        playing = controller?.isPlaying == true,
        buffering = controller?.playbackState == Player.STATE_BUFFERING,
        positionMs = controller?.currentPosition ?: 0,
        durationMs = controller?.duration?.takeIf { it > 0 } ?: ((track?.duration ?: 0.0) * 1000).toLong(),
        volume = volume.takeIf { System.currentTimeMillis() - volumeShownAt < 1500 },
        hasHeadphones = hasHeadphones(context),
        onPrevious = { controller?.seekToPrevious() },
        onToggle = { controller?.run { if (isPlaying) pause() else play() } },
        onNext = { controller?.seekToNext() },
        onEarbuds = { connectHeadphones(context) },
        modifier = Modifier
            // The bezel (or crown) changes the volume.
            .onRotaryScrollEvent { e ->
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (e.verticalScrollPixels > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeShownAt = System.currentTimeMillis()
                true
            }
            .focusRequester(focus)
            .focusable(),
    )
}

/** The player's look, separate from the live player so screenshots can show it. [volume] is shown while set. */
@Composable
fun PlayerContent(
    track: Track?,
    playing: Boolean,
    buffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float?,
    hasHeadphones: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onEarbuds: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(timeText = { TimeText() }) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (track != null) {
                Box(Modifier.fillMaxSize().alpha(0.35f)) { Artwork(track, 240, Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            CircularProgressIndicator(
                progress = volume ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                startAngle = 0f,
                endAngle = 360f,
                strokeWidth = 4.dp,
                indicatorColor = if (volume != null) Color.White else MaterialTheme.colors.primary,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                Text(track?.title ?: "Nothing playing", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track?.artist.orEmpty(), style = MaterialTheme.typography.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (volume != null) "Volume ${(volume * 100).toInt()}%" else "${formatTime(positionMs / 1000.0)} / ${formatTime(durationMs / 1000.0)}",
                    style = MaterialTheme.typography.caption3, color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPrevious, colors = ButtonDefaults.iconButtonColors(), modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous")
                    }
                    Button(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                        if (buffering) CircularProgressIndicator(modifier = Modifier.size(28.dp), indicatorColor = Color.White)
                        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(30.dp))
                    }
                    Button(onClick = onNext, colors = ButtonDefaults.iconButtonColors(), modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next")
                    }
                }
                if (!hasHeadphones) {
                    Spacer(Modifier.height(4.dp))
                    CompactChip(label = { Text("Earbuds", fontSize = 11.sp) }, icon = { Icon(Icons.Rounded.Headphones, null) }, onClick = onEarbuds)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val changes by Prefs.changes.collectAsState()
    val tracks by Library.tracks.collectAsState()
    var editing by remember { mutableStateOf("") }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence("v")?.toString() ?: return@rememberLauncherForActivityResult
        if (editing == "server") Prefs.serverUrl = text else Prefs.key = text
        Downloads.syncNow(context)
    }
    fun edit(what: String, label: String) {
        editing = what
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("v").setLabel(label).build()))
        input.launch(intent)
    }
    changes.let { }
    ListScreen {
        item { ListHeader { Text("Settings") } }
        item { MenuChip("Server", Icons.Rounded.Settings, Prefs.serverUrl.ifBlank { "Not set · send it from the phone" }) { edit("server", "Server address") } }
        item { MenuChip("Key", Icons.Rounded.Settings, if (Prefs.key.isBlank()) "None" else "Set") { edit("key", "Access key") } }
        item { Toggle("Wi-Fi only downloads", Prefs.wifiOnly) { Prefs.wifiOnly = it } }
        item { Toggle("Download Saved songs", Prefs.autoDownloadSaved) { Prefs.autoDownloadSaved = it; if (it) Downloads.syncNow(context) } }
        item { Toggle("Skip non-music parts", Prefs.skipNonMusic) { Prefs.skipNonMusic = it } }
        item { MenuChip("Sync now", Icons.Rounded.Refresh) { Downloads.syncNow(context) } }
        item {
            val mb = Library.bytesUsed() / (1024 * 1024)
            MenuChip("Delete downloads", Icons.Rounded.Delete, "${tracks.size} songs · $mb MB") { Library.removeAll() }
        }
        item {
            val update by Updater.status.collectAsState()
            MenuChip("Check for updates", Icons.Rounded.Refresh, update ?: "Version ${Updater.installedVersion(context).second}") {
                Updater.checkNow(context, "aria-watch.apk")
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ToggleChip(
        modifier = Modifier.fillMaxWidth(),
        checked = checked,
        onCheckedChange = onChange,
        label = { Text(label, maxLines = 2) },
        toggleControl = { Switch(checked = checked) },
    )
}
