package xyz.aezdek.hush.calls

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.theme.AezTheme
import xyz.aezdek.hush.ui.theme.Brand

/** Incoming, ringing and active calls, over the lock screen; shrinks to picture-in-picture when you leave it. */
class CallActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent?.action == ACTION_ANSWER) answer()
        setContent {
            val theme by Graph.prefs.theme.collectAsState()
            AezTheme(theme) {
                val call by Graph.calls.state.collectAsState()
                LaunchedEffect(call.status) { if (call.status == CallStatus.IDLE) finish() }
                Permissions()
                CallScreen(call, onAnswer = ::answer)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_ANSWER) answer()
    }

    private fun answer() {
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        Graph.calls.accept()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Graph.calls.state.value.status == CallStatus.ACTIVE) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }
        }
    }

    @Composable
    private fun Permissions() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
        LaunchedEffect(Unit) { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    }

    companion object {
        const val ACTION_ANSWER = "xyz.aezdek.hush.ANSWER"
    }
}

@Composable
fun CallScreen(call: CallUi, onAnswer: () -> Unit) {
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val calls = Graph.calls
    Box(Modifier.fillMaxSize().background(Color(0xFF050507))) {
        val remote = call.peers.values.filter { it.state == PeerState.CONNECTED || it.state == PeerState.CONNECTING }
        when {
            call.status == CallStatus.INCOMING -> {
                val from = call.from
                CallerCard(from, index, state.users[from]?.username, if (call.video) "Incoming video call" else "Incoming audio call")
                Row(
                    Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 56.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundButton(Icons.Filled.CallEnd, "Decline", Color(0xFFFF3B4E)) { calls.decline() }
                    RoundButton(Icons.Filled.Call, "Accept", Color(0xFF22C55E), onClick = onAnswer)
                }
            }
            call.live != null -> LiveView(call, index)
            remote.isEmpty() || call.status == CallStatus.OUTGOING -> {
                if (call.local != null && !call.cameraOff) VideoView(call.local, mirror = true, Modifier.fillMaxSize())
                val other = call.peers.keys.firstOrNull()
                CallerCard(other, index, state.users[other]?.username, if (call.status == CallStatus.OUTGOING) "Ringing…" else "Connecting…")
            }
            else -> {
                Grid(remote, index, state.users.mapValues { it.value.username })
                if (call.local != null && !call.cameraOff) {
                    VideoView(
                        call.local, mirror = true,
                        Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp).width(110.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)),
                    )
                }
                Timer(call.startedAt, Modifier.align(Alignment.TopStart).systemBarsPadding().padding(16.dp))
            }
        }
        if (call.status == CallStatus.ACTIVE || call.status == CallStatus.OUTGOING) Controls(call, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun CallerCard(userId: String?, index: Index, username: String?, subtitle: String) {
    Column(Modifier.fillMaxWidth().systemBarsPadding().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(userId, 120.dp, index, username)
        Spacer(Modifier.height(18.dp))
        val name = index.profile(userId)?.displayName?.takeIf { it.isNotBlank() } ?: "@${username ?: "someone"}"
        Text(name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Text("🔒 End-to-end encrypted", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}

@Composable
private fun Grid(peers: List<PeerUi>, index: Index, names: Map<String, String>) {
    val columns = when {
        peers.size <= 1 -> 1
        peers.size <= 4 -> 2
        else -> 3
    }
    val rows = peers.chunked(columns)
    Column(Modifier.fillMaxSize()) {
        for (row in rows) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (peer in row) {
                    Box(Modifier.weight(1f).fillMaxSize().padding(if (peers.size > 1) 2.dp else 0.dp).clip(RoundedCornerShape(if (peers.size > 1) 14.dp else 0.dp))) {
                        if (peer.video != null) {
                            VideoView(peer.video, mirror = false, Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize().background(Color(0xFF15151A)), contentAlignment = Alignment.Center) {
                                Avatar(peer.userId, 88.dp, index, names[peer.userId])
                            }
                        }
                        if (peers.size > 1) {
                            Text(
                                "@${names[peer.userId] ?: ""}", color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0x80000000), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LiveView(call: CallUi, index: Index) {
    val live = call.live ?: return
    val host = call.peers[live.hostId]
    if (live.host) {
        if (call.local != null) VideoView(call.local, mirror = true, Modifier.fillMaxSize())
    } else if (host?.video != null) {
        VideoView(host.video, mirror = false, Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(live.hostId, 96.dp, index) }
    }
    val users = Graph.client.state.collectAsState().value.users
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.background(Brand.live, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            Spacer(Modifier.width(8.dp))
            if (live.host) {
                val watching = call.peers.values.count { it.state == PeerState.CONNECTED || it.state == PeerState.CONNECTING }
                Text("👁 $watching", color = Color.White, fontSize = 13.sp)
            } else {
                Text("@${users[live.hostId]?.username ?: ""}", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Text("❤️ ${live.hearts}", color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        LazyColumn(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 14.dp), reverseLayout = true) {
            items(live.comments.reversed(), key = { it.id }) { comment ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(comment.from, 26.dp, index, users[comment.from]?.username)
                    Spacer(Modifier.width(8.dp))
                    Text("${users[comment.from]?.username ?: ""}  ", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(comment.text, color = Color.White, fontSize = 13.sp)
                }
            }
        }
        var draft by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 110.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Color(0x55000000)).padding(horizontal = 16.dp, vertical = 11.dp)) {
                if (draft.isEmpty()) Text("Comment…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                BasicTextField(draft, { draft = it }, textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            if (draft.isNotBlank()) {
                Text("Send", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { Graph.calls.sendLive(draft.trim()); draft = "" }.padding(8.dp))
            } else {
                Icon(Icons.Filled.Favorite, "Heart", tint = Brand.pink, modifier = Modifier.size(30.dp).clickable { Graph.calls.sendLive(null) })
            }
        }
    }
}

@Composable
private fun Controls(call: CallUi, modifier: Modifier) {
    val calls = Graph.calls
    Row(
        modifier.systemBarsPadding().padding(bottom = 24.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xCC111116)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (call.live?.host != false) {
            RoundButton(if (call.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, "Mute", if (call.muted) Color.White else Color(0x33FFFFFF), small = true,
                tint = if (call.muted) Color.Black else Color.White) { calls.toggleMute() }
            RoundButton(if (call.cameraOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, "Camera",
                if (call.cameraOff) Color.White else Color(0x33FFFFFF), small = true, tint = if (call.cameraOff) Color.Black else Color.White) { calls.toggleCamera() }
            RoundButton(Icons.Outlined.Cameraswitch, "Flip", Color(0x33FFFFFF), small = true) { calls.flip() }
        }
        RoundButton(Icons.AutoMirrored.Outlined.VolumeUp, "Speaker", if (call.speaker) Color.White else Color(0x33FFFFFF), small = true,
            tint = if (call.speaker) Color.Black else Color.White) { calls.toggleSpeaker() }
        RoundButton(Icons.Filled.CallEnd, "End", Color(0xFFFF3B4E), small = true) { calls.hangup() }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, background: Color, small: Boolean = false, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (small) 56.dp else 72.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(if (small) 26.dp else 32.dp))
        }
        if (!small) {
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Timer(startedAt: Long?, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((now - (startedAt ?: now)) / 1000).coerceAtLeast(0)
    Text(
        "%d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.background(Color(0x66000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A WebRTC video track in Compose. */
@Composable
fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(Graph.calls.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                renderer = this
            }
        },
        onRelease = { it.release() },
    )
    DisposableEffect(track, renderer) {
        val sink = renderer
        if (sink != null) runCatching { track.addSink(sink) }
        onDispose { if (sink != null) runCatching { track.removeSink(sink) } }
    }
}
