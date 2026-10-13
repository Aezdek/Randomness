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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import xyz.aezdek.aria.core.Track
import xyz.aezdek.aria.core.Updater
import xyz.aezdek.aria.core.formatTime

private val Accent = Color(0xFFFF3B6B)

/** Artwork for screenshots, which can't load images from the network. */
val LocalArtworkPreview = staticCompositionLocalOf<((Track) -> Painter)?> { null }

@Composable
fun AriaPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Accent, background = Color(0xFF0B0B10), surface = Color(0xFF0B0B10)), content = content)
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
data class SendState(val label: String, val progress: Float?, val done: Boolean = false, val failed: Boolean = false)

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
            WorkInfo.State.RUNNING -> SendState(info.progress.getString("stage") ?: "Starting", info.progress.getFloat("progress", 0f))
            WorkInfo.State.SUCCEEDED -> if (id in received) SendState("On your watch", 1f, done = true) else SendState("Sent · the watch copies it when it's in range", null, done = true)
            WorkInfo.State.FAILED -> SendState(info.outputData.getString("error") ?: "Failed", null, failed = true)
            WorkInfo.State.CANCELLED -> SendState("Cancelled", null, failed = true)
        }
    }.toMap() + received.associateWith { SendState("On your watch", 1f, done = true) }.filterKeys { k -> infos.none { it.tags.contains("id:$k") } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneUi(startTab: Int = 0, content: (@Composable (Int) -> Unit)? = null) {
    var tab by remember { mutableIntStateOf(startTab) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Aria", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar {
                listOf("Search" to Icons.Rounded.Search, "Saved" to Icons.Rounded.Star, "Settings" to Icons.Rounded.Settings).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (content != null) content(tab) else when (tab) {
                0 -> SearchTab()
                1 -> SavedTab()
                else -> SettingsTab()
            }
        }
    }
}

@Composable
fun TrackRow(track: Track, state: SendState?, onSend: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(listOf(track.artist, track.duration.takeIf { it > 0 }?.let { formatTime(it) }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                state?.let { Text(it.label, color = if (it.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        },
        leadingContent = {
            val shape = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F1F2A))
            val preview = LocalArtworkPreview.current
            if (preview != null) {
                Image(preview(track), contentDescription = null, contentScale = ContentScale.Crop, modifier = shape)
            } else {
                AsyncImage(
                    model = PhonePrefs.server.takeIf { it.configured }?.artworkUrl(track, large = false) ?: "https://i.ytimg.com/vi/${track.id}/mqdefault.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = shape,
                )
            }
        },
        trailingContent = {
            when {
                state?.progress != null && !state.done -> CircularProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.size(28.dp))
                state?.done == true -> Icon(Icons.Rounded.CheckCircle, "Sent", tint = MaterialTheme.colorScheme.primary)
                else -> IconButton(onClick = onSend) {
                    Icon(if (state?.failed == true) Icons.Rounded.ErrorOutline else Icons.Rounded.Watch, "Send to watch")
                }
            }
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
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
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search YouTube") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            shape = RoundedCornerShape(14.dp),
        )
        if (busy) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error?.let { Hint(it) }
        if (!busy && results.isEmpty() && error == null) Hint("Search, then tap the watch button to send a song. You can also share a YouTube link to Aria from any app.")
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(results, key = { it.id }) { track -> TrackRow(track, states[track.id]) { SendWorker.enqueue(context, track) } }
        }
    }
}

@Composable
fun SavedTab(initialSaved: List<Track>? = null, states: Map<String, SendState> = sendStates()) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(initialSaved) }
    var error by remember { mutableStateOf<String?>(null) }
    val server = PhonePrefs.server
    LaunchedEffect(Unit) {
        if (server.configured && initialSaved == null) runCatching { server.config() }
            .onSuccess { saved = Track.listFromJson(it.optJSONArray("saved")) }
            .onFailure { error = it.message }
    }
    Column {
        when {
            !server.configured -> Hint("Saved songs come from the Aria website. Set the server in Settings.")
            error != null -> Hint("Couldn't reach the server: $error")
            saved == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            saved!!.isEmpty() -> Hint("Nothing saved yet. Add songs on the Aria website.")
            else -> {
                Button(onClick = { saved!!.forEach { SendWorker.enqueue(context, it) } }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(Icons.Rounded.Watch, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Send all ${saved!!.size} to watch")
                }
                Text(
                    "The watch app also downloads Saved songs by itself over Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(saved!!, key = { it.id }) { track -> TrackRow(track, states[track.id]) { SendWorker.enqueue(context, track) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    if (initialWatchStatus == null) LaunchedEffect(Unit) {
        watchStatus = WatchLink.findWatch(context)?.let { "Connected to ${it.displayName}" }
            ?: "No watch with Aria found. Install the watch app and keep the watch paired."
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Watch", style = MaterialTheme.typography.titleMedium) }
        item { Text(watchStatus, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        item { Text("Aria server", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Server") }, placeholder = { Text("192.168.1.20:8765") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(key, { key = it }, label = { Text("Key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    PhonePrefs.serverUrl = serverUrl
                    PhonePrefs.key = key
                    serverUrl = PhonePrefs.serverUrl
                    serverStatus = "Checking…"
                    scope.launch {
                        serverStatus = runCatching { PhonePrefs.server.ping() }
                            .fold({ "Connected · yt-dlp ${it.optString("ytdlp")}" }, { "Couldn't connect: ${it.message}" })
                    }
                }) { Text("Save & test") }
                FilledTonalButton(onClick = {
                    PhonePrefs.serverUrl = serverUrl
                    PhonePrefs.key = key
                    scope.launch {
                        val ok = runCatching { WatchLink.sendServer(context, PhonePrefs.serverUrl, PhonePrefs.key) }.isSuccess
                        Toast.makeText(context, if (ok) "Sent to the watch" else "Couldn't reach the watch", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Send to watch") }
            }
        }
        serverStatus?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

        item { Text("Download songs with", style = MaterialTheme.typography.titleMedium) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = usePhone, onClick = { usePhone = true; PhonePrefs.usePhone = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("This phone") }
                SegmentedButton(selected = !usePhone, onClick = { usePhone = false; PhonePrefs.usePhone = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Aria server") }
            }
        }
        item {
            Text(
                if (usePhone) "yt-dlp runs on this phone, so it works anywhere. If it fails, Aria tries the server."
                else "Your server downloads, with clean titles, album art and skipped non-music parts.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ytdlp ?: "Starting yt-dlp…", modifier = Modifier.weight(1f))
                FilledTonalButton(enabled = !updating && YtDlp.ready, onClick = {
                    updating = true
                    scope.launch {
                        val message = runCatching { YtDlp.update() }.getOrElse { "Update failed: ${it.message}" }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        updating = false
                    }
                }) { Text(if (updating) "Updating…" else "Update") }
            }
        }
        item { Text("Updates", style = MaterialTheme.typography.titleMedium) }
        item {
            val update by Updater.status.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Aria ${Updater.installedVersion(context).second}" + (update?.let { " · $it" } ?: "") +
                        "\nUpdates install automatically. Android may ask you to confirm.",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { Updater.checkNow(context, "aria-phone.apk") }) { Text("Check") }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
