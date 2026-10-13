package xyz.aezdek.aria.phone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import xyz.aezdek.aria.core.Track
import xyz.aezdek.aria.core.Updater
import xyz.aezdek.aria.core.formatTime

// Colors in the spirit of Apple Music's and Spotify's dark themes, with Aria's pink.
private val Accent = Color(0xFFFF3B6B)
private val Background = Color(0xFF000000)
private val Card = Color(0xFF1C1C1E)
private val CardHigh = Color(0xFF2C2C2E)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextSecondary = Color(0xFF8E8E93)
private val Danger = Color(0xFFFF453A)
private val Hairline = Color(0x1FFFFFFF)

/** Artwork for screenshots, which can't load images from the network. */
val LocalArtworkPreview = staticCompositionLocalOf<((Track) -> Painter)?> { null }

@Composable
fun AriaPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, secondary = Accent, background = Background, surface = Background,
            onBackground = TextPrimary, onSurface = TextPrimary, onSurfaceVariant = TextSecondary,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Background)) { content() }
    }
}

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleShare(intent)
        setContent { AriaPhoneTheme { PhoneUi() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share" a YouTube link to Aria from any app to send that song to the watch. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/)([A-Za-z0-9_-]{11})""").find(text)?.groupValues?.get(1)
        if (id == null) {
            Toast.makeText(this, "That isn't a YouTube link", Toast.LENGTH_SHORT).show()
            return
        }
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        SendWorker.enqueue(this, Track(id, title.ifBlank { "YouTube $id" }, ""))
        Toast.makeText(this, "Sending to your watch", Toast.LENGTH_SHORT).show()
    }
}

/** Where a song is on its way to the watch. */
data class SendState(
    val label: String,
    val progress: Float?,
    val done: Boolean = false,
    val failed: Boolean = false,
    val title: String? = null,
)

@Composable
fun sendStates(): Map<String, SendState> {
    val context = LocalContext.current
    val infos by remember { WorkManager.getInstance(context).getWorkInfosByTagFlow(SendWorker.TAG) }.collectAsState(initial = emptyList())
    val changes by PhonePrefs.changes.collectAsState()
    val received = remember(changes) { PhonePrefs.received }
    return infos.mapNotNull { info ->
        val id = info.tags.firstOrNull { it.startsWith("id:") }?.removePrefix("id:") ?: return@mapNotNull null
        id to when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SendState("Waiting", null)
            WorkInfo.State.RUNNING -> SendState(
                info.progress.getString("stage") ?: "Starting",
                info.progress.getFloat("progress", 0f),
                title = info.progress.getString("title"),
            )
            WorkInfo.State.SUCCEEDED -> if (id in received) SendState("On your watch", 1f, done = true) else SendState("Sent · arrives when the watch is in range", null, done = true)
            WorkInfo.State.FAILED -> SendState(info.outputData.getString("error") ?: "Failed", null, failed = true)
            WorkInfo.State.CANCELLED -> SendState("Cancelled", null, failed = true)
        }
    }.toMap() + received.associateWith { SendState("On your watch", 1f, done = true) }.filterKeys { k -> infos.none { it.tags.contains("id:$k") } }
}

private data class Tab(val label: String, val icon: ImageVector)

private val Tabs = listOf(Tab("Search", Icons.Rounded.Search), Tab("Saved", Icons.Rounded.LibraryMusic), Tab("Settings", Icons.Rounded.Settings))

@Composable
fun PhoneUi(startTab: Int = 0, states: Map<String, SendState>? = null, content: (@Composable (Int) -> Unit)? = null) {
    var tab by remember { mutableIntStateOf(startTab) }
    val sending = (states ?: sendStates()).values.firstOrNull { it.title != null && !it.done && !it.failed }
    Column(Modifier.fillMaxSize().background(Background)) {
        Box(Modifier.weight(1f).statusBarsPadding()) {
            if (content != null) content(tab) else when (tab) {
                0 -> SearchTab()
                1 -> SavedTab()
                else -> SettingsTab()
            }
        }
        sending?.let { TransferBar(it) }
        // Apple Music style tab bar: icons and labels, the selected one in the accent color.
        Column(Modifier.fillMaxWidth().background(Color(0xF20A0A0C))) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Hairline))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
                Tabs.forEachIndexed { i, t ->
                    val color = if (tab == i) Accent else TextSecondary
                    Column(
                        Modifier.weight(1f).clickable { tab = i }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, null, tint = color, modifier = Modifier.size(26.dp))
                        Text(t.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Like Apple Music's mini player: what's on its way to the watch right now. */
@Composable
private fun TransferBar(state: SendState) {
    Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(CardHigh)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Watch, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(state.title ?: "Sending", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.label, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
            }
            Text("${((state.progress ?: 0f) * 100).toInt()}%", color = TextSecondary, fontSize = 13.sp)
        }
        LinearProgressIndicator(
            progress = { state.progress ?: 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
            color = Accent,
            trackColor = Color.Transparent,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun LargeTitle(text: String) {
    Text(
        text, color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun Artwork(track: Track, size: Int, corner: Int = 6) {
    val shape = Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp)).background(CardHigh)
    val preview = LocalArtworkPreview.current
    if (preview != null) {
        Image(preview(track), contentDescription = null, contentScale = ContentScale.Crop, modifier = shape)
    } else {
        AsyncImage(
            model = PhonePrefs.server.takeIf { it.configured }?.artworkUrl(track, large = size > 80) ?: "https://i.ytimg.com/vi/${track.id}/hqdefault.jpg",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = shape,
        )
    }
}

/** A song row in the style of Apple Music and Spotify lists. Tap it to send the song to the watch. */
@Composable
fun TrackRow(track: Track, state: SendState?, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = state?.done != true, onClick = onSend).padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, 52)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.duration.takeIf { it > 0 }?.let { formatTime(it) }).joinToString(" · ")
            if (state != null && !state.done) Text(state.label, color = if (state.failed) Danger else Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(sub, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val progress = state?.progress
            when {
                state?.done == true -> Icon(Icons.Rounded.CheckCircle, "On your watch", tint = Accent, modifier = Modifier.size(24.dp))
                state?.failed == true -> Icon(Icons.Rounded.ErrorOutline, "Failed, tap to retry", tint = Danger, modifier = Modifier.size(24.dp))
                progress != null -> CircularProgressIndicator(
                    progress = { progress }, modifier = Modifier.size(24.dp), color = Accent, strokeWidth = 2.5.dp, trackColor = CardHigh,
                )
                else -> Icon(Icons.Rounded.Watch, "Send to watch", tint = TextSecondary, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

/** Spotify-style white search field. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Color(0xFF121212), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Songs, artists or a YouTube link", color = Color(0xFF6A6A6A), fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF121212), fontSize = 16.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = Color(0xFF121212), modifier = Modifier.size(22.dp).clickable { onChange("") })
    }
}

@Composable
fun SearchTab(initialQuery: String = "", initialResults: List<Track> = emptyList(), states: Map<String, SendState> = sendStates()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(initialResults) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val server = PhonePrefs.server
            runCatching {
                if (PhonePrefs.usePhone || !server.configured) {
                    runCatching { YtDlp.search(q) }.getOrElse { e -> if (server.configured) server.search(q) else throw e }
                } else server.search(q)
            }.onSuccess { results = it }.onFailure { error = it.message ?: "Search failed" }
            busy = false
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { LargeTitle("Search") }
        item { SearchField(query, { query = it }, ::search) }
        item { Spacer(Modifier.height(12.dp)) }
        if (busy) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
        error?.let { item { EmptyState(Icons.Rounded.ErrorOutline, "Couldn't search", it) } }
        if (!busy && results.isEmpty() && error == null) {
            item { EmptyState(Icons.Rounded.Watch, "Music for your wrist", "Search, then tap a song to send it to your watch. You can also share a YouTube link to Aria from any app.") }
        }
        if (results.isNotEmpty()) item { Text("Songs", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp)) }
        items(results, key = { it.id }) { track -> TrackRow(track, states[track.id]) { SendWorker.enqueue(context, track) } }
    }
}

/** Four covers in a square, like a Spotify playlist. */
@Composable
private fun Collage(tracks: List<Track>, size: Int) {
    val shown = tracks.take(4)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(CardHigh)) {
        if (shown.size < 4) {
            shown.firstOrNull()?.let { Artwork(it, size, 8) }
        } else {
            Column {
                for (row in 0..1) Row { for (col in 0..1) Artwork(shown[row * 2 + col], size / 2, 0) }
            }
        }
    }
}

@Composable
fun SavedTab(initialSaved: List<Track>? = null, states: Map<String, SendState> = sendStates()) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(initialSaved) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val server = PhonePrefs.server
    LaunchedEffect(reload) {
        if (server.configured && (initialSaved == null || reload > 0)) runCatching { server.config() }
            .onSuccess { saved = Track.listFromJson(it.optJSONArray("saved")); error = null }
            .onFailure { error = it.message }
    }
    val list = saved
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        when {
            !server.configured -> {
                item { LargeTitle("Saved") }
                item { EmptyState(Icons.Rounded.LibraryMusic, "Connect your server", "Saved songs come from the Aria website. Add your server in Settings.") }
            }
            list == null && error != null -> {
                item { LargeTitle("Saved") }
                item { EmptyState(Icons.Rounded.ErrorOutline, "Couldn't reach the server", error.orEmpty()) }
            }
            list == null -> {
                item { LargeTitle("Saved") }
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
            }
            else -> {
                // Spotify playlist header: cover collage on a gradient, title, count, and a round action button.
                item {
                    Column(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Accent.copy(alpha = 0.45f), Background)))
                            .padding(top = 28.dp, start = 20.dp, end = 20.dp, bottom = 8.dp),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Collage(list, 200) }
                        Spacer(Modifier.height(20.dp))
                        Text("Saved", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("${list.size} song${if (list.size == 1) "" else "s"} · from the Aria website", color = TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Refresh, "Refresh", tint = TextSecondary, modifier = Modifier.size(28.dp).clickable { reload++ })
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier.size(56.dp).clip(CircleShape).background(Accent).clickable(enabled = list.isNotEmpty()) {
                                    list.forEach { SendWorker.enqueue(context, it) }
                                    Toast.makeText(context, "Sending ${list.size} songs to your watch", Toast.LENGTH_SHORT).show()
                                },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Watch, "Send all to watch", tint = Color.White, modifier = Modifier.size(28.dp)) }
                        }
                    }
                }
                if (list.isEmpty()) item { EmptyState(Icons.Rounded.LibraryMusic, "Nothing saved yet", "Add songs on the Aria website.") }
                items(list, key = { it.id }) { track -> TrackRow(track, states[track.id]) { SendWorker.enqueue(context, track) } }
            }
        }
    }
}

/** iOS-style grouped section: a caption above a rounded card of rows. */
@Composable
private fun Section(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title.uppercase(), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card)) { content() }
        footer?.let { Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) }
    }
}

@Composable
private fun RowDivider() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Hairline))

@Composable
private fun SettingRow(label: String, value: String? = null, action: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 16.sp)
            value?.let { Text(it, color = TextSecondary, fontSize = 13.sp) }
        }
        action?.let { Text(it, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun FieldRow(label: String, value: String, placeholder: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.width(76.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = TextSecondary.copy(alpha = 0.6f), fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** iOS-style segmented control. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF111113)).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (i == selected) CardHigh else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
fun SettingsTab(initialWatchStatus: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(PhonePrefs.serverUrl) }
    var key by remember { mutableStateOf(PhonePrefs.key) }
    var usePhone by remember { mutableStateOf(PhonePrefs.usePhone) }
    var serverStatus by remember { mutableStateOf<String?>(null) }
    var watchStatus by remember { mutableStateOf(initialWatchStatus ?: "Looking for your watch…") }
    var updating by remember { mutableStateOf(false) }
    val ytdlp by YtDlp.status.collectAsState()
    val update by Updater.status.collectAsState()
    if (initialWatchStatus == null) LaunchedEffect(Unit) {
        watchStatus = WatchLink.findWatch(context)?.let { "Connected to ${it.displayName}" }
            ?: "No watch with Aria found. Install the watch app and keep the watch paired."
    }
    fun save() {
        PhonePrefs.serverUrl = serverUrl
        PhonePrefs.key = key
        serverUrl = PhonePrefs.serverUrl
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { LargeTitle("Settings") }
        item {
            Section("Watch") {
                SettingRow("Galaxy Watch", watchStatus)
                RowDivider()
                SettingRow("Send server to watch", "So you never type an address on the watch", action = "Send") {
                    save()
                    scope.launch {
                        val ok = runCatching { WatchLink.sendServer(context, PhonePrefs.serverUrl, PhonePrefs.key) }.isSuccess
                        Toast.makeText(context, if (ok) "Sent to the watch" else "Couldn't reach the watch", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        item {
            Section("Aria server", "The server on your homelab. Saved songs and the best song info come from it.") {
                FieldRow("Server", serverUrl, "192.168.1.20:8765") { serverUrl = it }
                RowDivider()
                FieldRow("Key", key, "Optional", secret = true) { key = it }
                RowDivider()
                SettingRow("Save and test", serverStatus, action = "Test") {
                    save()
                    serverStatus = "Checking…"
                    scope.launch {
                        serverStatus = runCatching { PhonePrefs.server.ping() }
                            .fold({ "Connected · yt-dlp ${it.optString("ytdlp")}" }, { "Couldn't connect: ${it.message}" })
                    }
                }
            }
        }
        item {
            Section(
                "Download songs with",
                if (usePhone) "yt-dlp runs on this phone, so it works anywhere. If it fails, Aria tries the server."
                else "Your server downloads, with clean titles, album art and skipped non-music parts.",
            ) {
                Segmented(listOf("This phone", "Aria server"), if (usePhone) 0 else 1) {
                    usePhone = it == 0
                    PhonePrefs.usePhone = usePhone
                }
                RowDivider()
                SettingRow(ytdlp ?: "Starting yt-dlp…", "YouTube changes often. Keep yt-dlp current.", action = if (updating) "Updating…" else "Update") {
                    if (!updating && YtDlp.ready) {
                        updating = true
                        scope.launch {
                            val message = runCatching { YtDlp.update() }.getOrElse { "Update failed: ${it.message}" }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            updating = false
                        }
                    }
                }
            }
        }
        item {
            Section("Updates", "Aria updates itself every day. Android may ask you to confirm.") {
                SettingRow("Aria ${Updater.installedVersion(context).second}", update ?: "Checks once a day", action = "Check") {
                    Updater.checkNow(context, "aria-phone.apk")
                }
            }
        }
    }
}
