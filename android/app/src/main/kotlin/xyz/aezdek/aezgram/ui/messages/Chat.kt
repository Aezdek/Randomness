package xyz.aezdek.aezgram.ui.messages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.aezgram.Graph
import xyz.aezdek.aezgram.client.AppState
import xyz.aezdek.aezgram.client.Entity
import xyz.aezdek.aezgram.client.Index
import xyz.aezdek.aezgram.media.EncryptedDataSource
import xyz.aezdek.aezgram.media.MediaKind
import xyz.aezdek.aezgram.media.MediaPrep
import xyz.aezdek.aezgram.media.VideoPlayer
import xyz.aezdek.aezgram.media.VoiceRecorder
import xyz.aezdek.aezgram.protocol.Audience
import xyz.aezdek.aezgram.protocol.MediaRef
import xyz.aezdek.aezgram.protocol.MessagePayload
import xyz.aezdek.aezgram.protocol.MessageType
import xyz.aezdek.aezgram.protocol.ReactionPayload
import xyz.aezdek.aezgram.protocol.StoryPayload
import xyz.aezdek.aezgram.service.ConnectionService
import xyz.aezdek.aezgram.ui.LocalNav
import xyz.aezdek.aezgram.ui.Screen
import xyz.aezdek.aezgram.ui.components.ActionSheet
import xyz.aezdek.aezgram.ui.components.Avatar
import xyz.aezdek.aezgram.ui.components.IconAction
import xyz.aezdek.aezgram.ui.components.MediaImage
import xyz.aezdek.aezgram.ui.components.RichText
import xyz.aezdek.aezgram.ui.components.SheetItem
import xyz.aezdek.aezgram.ui.components.Spinner
import xyz.aezdek.aezgram.ui.components.TopBar
import xyz.aezdek.aezgram.ui.components.ago
import xyz.aezdek.aezgram.ui.feed.post
import xyz.aezdek.aezgram.ui.theme.Aez
import xyz.aezdek.aezgram.ui.theme.Brand
import java.text.DateFormat
import java.util.Date

private val EMOJI = Regex("^(\\p{So}|\\p{Cn}|[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u200D\\uFE0F\\u2600-\\u27BF]){1,8}$")
private val QUICK = listOf("❤️", "😂", "😮", "😢", "😡", "👍")

@Composable
fun Chat(threadId: String) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val thread = state.threads[threadId]
    if (thread == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Chat")
            Spinner()
        }
        return
    }
    val messages = index.messages[threadId].orEmpty()
    val others = thread.members.map { it.userId }.filter { it != me.id }
    var vanish by remember(threadId) { mutableStateOf(Graph.prefs.vanish(threadId)) }
    var replyTo by remember { mutableStateOf<Entity?>(null) }
    var editing by remember { mutableStateOf<Entity?>(null) }
    var menu by remember { mutableStateOf<Entity?>(null) }
    var sending by remember { mutableStateOf<String?>(null) }
    var once by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(threadId) {
        ConnectionService.visibleThread = threadId
        Graph.notifier.cancelChat(threadId)
        onDispose { if (ConnectionService.visibleThread == threadId) ConnectionService.visibleThread = null }
    }
    val lastId = messages.lastOrNull()?.id
    LaunchedEffect(lastId) {
        val mine = thread.members.firstOrNull { it.userId == me.id }
        val last = messages.lastOrNull()
        if (mine != null && last != null && last.dto.receivedAt > mine.lastReadAt) runCatching { Graph.client.composer.markRead(threadId) }
    }

    fun send(payload: MessagePayload, audience: String = if (vanish) Audience.VANISH else Audience.THREAD) {
        Graph.launch { Graph.client.composer.sendMessage(threadId, payload, audience = audience) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val viewOnce = once
        once = false
        scope.launch {
            sending = "Encrypting…"
            Graph.attempt {
                val refs = uris.mapIndexed { i, uri ->
                    sending = "Sending ${i + 1} of ${uris.size}…"
                    val prepared = if (MediaPrep.isVideo(context, uri)) MediaPrep.prepareVideo(context, uri, MediaKind.MESSAGE)
                    else MediaPrep.prepareImage(context, uri, MediaKind.MESSAGE)
                    Graph.client.media.upload(prepared)
                }
                Graph.client.composer.sendMessage(threadId, MessagePayload(MessageType.MEDIA, media = refs),
                    audience = if (viewOnce) Audience.ONCE else if (vanish) Audience.VANISH else Audience.THREAD)
            }
            sending = null
        }
    }

    val title = index.threadTitle(thread, state.users, me.id)
    val typing = state.typing[threadId].orEmpty().filter { (u, until) -> u != me.id && until > System.currentTimeMillis() }.keys
    val online = others.any { state.presence[it] == true }
    val lastActive = others.mapNotNull { state.users[it]?.lastActiveAt }.maxOrNull()
    val subtitle = when {
        typing.isNotEmpty() -> "typing…"
        online -> "Active now"
        lastActive != null -> ago(lastActive).let { if (it == "now") "Active now" else "Active $it ago" }
        thread.isGroup -> "${thread.members.size} members"
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Aez.colors.bg)) {
        TopBar(
            title, subtitle = subtitle,
            leading = {
                Avatar(others.firstOrNull(), 36.dp, index, state.users[others.firstOrNull()]?.username, online = online) {
                    if (!thread.isGroup) others.firstOrNull()?.let { nav.go(Screen.Profile(it)) }
                }
            },
            actions = {
                IconAction(Icons.Outlined.Call, "Audio call", { Graph.calls.start(threadId, video = false) }, size = 24.dp)
                IconAction(Icons.Outlined.Videocam, "Video call", { Graph.calls.start(threadId, video = true) }, size = 26.dp)
                IconAction(Icons.Outlined.Info, "Details", { nav.go(Screen.ThreadInfo(threadId)) }, size = 24.dp)
            },
        )
        if (vanish) {
            Text("Vanish mode: seen messages disappear when you leave the chat", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Brand.messageGradient).padding(6.dp))
        }
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
        val reversed = messages.asReversed()
        val theirRead = thread.members.filter { it.userId != me.id }.minOfOrNull { it.lastReadAt } ?: 0L
        val lastMine = messages.lastOrNull { it.dto.authorId == me.id }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true) {
            if (typing.isNotEmpty()) item { TypingBubble(typing.first(), index, state) }
            itemsIndexed(reversed, key = { _, m -> m.id }) { reversedIndex, message ->
                val i = messages.lastIndex - reversedIndex
                val prev = messages.getOrNull(i - 1)
                val next = messages.getOrNull(i + 1)
                val newDay = prev == null || !sameDay(prev.dto.receivedAt, message.dto.receivedAt)
                Column {
                    if (newDay) {
                        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(message.dto.receivedAt)), fontSize = 12.sp, color = Aez.colors.text3,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                    }
                    Bubble(
                        message, index, state, isGroup = thread.isGroup,
                        firstOfGroup = prev?.dto?.authorId != message.dto.authorId || newDay,
                        lastOfGroup = next?.dto?.authorId != message.dto.authorId,
                        onLongPress = { menu = message },
                    )
                    if (message === lastMine && theirRead >= message.dto.receivedAt) {
                        Text("Seen", fontSize = 11.sp, color = Aez.colors.text3, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 2.dp))
                    }
                }
            }
            if (messages.isEmpty()) item { ChatIntro(others.firstOrNull(), title, index, state) }
        }
        sending?.let {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(it, fontSize = 12.sp, color = Aez.colors.text2)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = Brand.pink, trackColor = Aez.colors.elev2)
            }
        }
        Composer(
            threadId = threadId,
            replyTo = replyTo, editing = editing, vanish = vanish, once = once,
            onCancel = { replyTo = null; editing = null },
            onToggleOnce = { once = !once },
            onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onSend = { text ->
                val edit = editing
                val reply = replyTo
                editing = null
                replyTo = null
                if (edit != null) {
                    val p = edit.payloadAs<MessagePayload>() ?: return@Composer
                    Graph.launch { Graph.client.composer.edit(edit, p.copy(text = text, edited = true)) }
                } else {
                    send(MessagePayload(MessageType.TEXT, text = text, replyTo = reply?.id))
                }
            },
            onHeart = { send(MessagePayload(MessageType.HEART)) },
            onVoice = { media ->
                scope.launch {
                    sending = "Sending voice message…"
                    Graph.attempt {
                        val ref = Graph.client.media.upload(media)
                        Graph.client.composer.sendMessage(threadId, MessagePayload(MessageType.VOICE, media = listOf(ref)),
                            audience = if (vanish) Audience.VANISH else Audience.THREAD)
                    }
                    sending = null
                }
            },
        )
    }
    menu?.let { message ->
        val mine = message.dto.authorId == me.id
        val p = message.payloadAs<MessagePayload>()
        ActionSheet({ menu = null }) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (emoji in QUICK) {
                    Text(emoji, fontSize = 30.sp, modifier = Modifier.clip(CircleShape).clickable {
                        menu = null
                        Graph.launch { Graph.client.composer.react(message, emoji) }
                    }.padding(6.dp))
                }
            }
            SheetItem("Reply", Icons.AutoMirrored.Outlined.Reply) { menu = null; replyTo = message }
            if (p?.type == MessageType.TEXT) {
                SheetItem("Copy", Icons.Outlined.ContentCopy) {
                    menu = null
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", p.text.orEmpty()))
                }
                if (mine) SheetItem("Edit", Icons.Outlined.Edit) { menu = null; editing = message }
            }
            if (mine) {
                SheetItem("Unsend", Icons.Outlined.Delete, danger = true) {
                    menu = null
                    Graph.launch { Graph.client.composer.delete(message.id) }
                }
            }
        }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val day = 86_400_000L
    val offset = java.util.TimeZone.getDefault().getOffset(a)
    return (a + offset) / day == (b + offset) / day
}

@Composable
private fun ChatIntro(userId: String?, title: String, index: Index, state: AppState) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(userId, 96.dp, index, state.users[userId]?.username)
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
        if (userId != null) {
            Text("View profile", color = Aez.colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Aez.colors.elev2).clickable { nav.go(Screen.Profile(userId)) }
                    .padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("🔒 Messages and calls are end-to-end encrypted. Not even the server can read them.", color = Aez.colors.text3, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
    }
}

@Composable
private fun TypingBubble(userId: String, index: Index, state: AppState) {
    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Avatar(userId, 28.dp, index, state.users[userId]?.username)
        Spacer(Modifier.width(8.dp))
        Text("•••", color = Aez.colors.text2, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Aez.colors.bubbleOther).padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

@Composable
private fun Bubble(
    message: Entity, index: Index, state: AppState, isGroup: Boolean, firstOfGroup: Boolean, lastOfGroup: Boolean, onLongPress: () -> Unit,
) {
    val nav = LocalNav.current
    val me = state.me ?: return
    val p = message.payloadAs<MessagePayload>() ?: return
    val mine = message.dto.authorId == me.id
    val reactions = index.reactionsOf(message.id)
    val once = message.dto.audience == Audience.ONCE
    var opened by remember { mutableStateOf(false) }
    val big = p.type == MessageType.HEART || (p.type == MessageType.TEXT && EMOJI.matches(p.text.orEmpty().trim()))
    val shape = RoundedCornerShape(
        topStart = if (!mine && !firstOfGroup) 6.dp else 20.dp, topEnd = if (mine && !firstOfGroup) 6.dp else 20.dp,
        bottomStart = if (!mine && !lastOfGroup) 6.dp else 20.dp, bottomEnd = if (mine && !lastOfGroup) 6.dp else 20.dp,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (isGroup && !mine && firstOfGroup) {
            Text(state.users[message.dto.authorId]?.username ?: "", fontSize = 11.sp, color = Aez.colors.text3, modifier = Modifier.padding(start = 46.dp, top = 6.dp))
        }
        p.replyTo?.let { id ->
            val quoted = state.entities[id]?.payloadAs<MessagePayload>()
            Text(
                quoted?.text ?: "Original message", fontSize = 12.sp, color = Aez.colors.text2, maxLines = 2,
                modifier = Modifier.padding(start = if (mine) 0.dp else 38.dp, top = 4.dp).widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Aez.colors.elev1).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (!mine) {
                if (lastOfGroup) Avatar(message.dto.authorId, 28.dp, index, state.users[message.dto.authorId]?.username) { nav.go(Screen.Profile(message.dto.authorId)) }
                else Spacer(Modifier.width(28.dp))
                Spacer(Modifier.width(8.dp))
            }
            val clickModifier = Modifier.combinedClickable(onLongClick = onLongPress, onDoubleClick = {
                Graph.launch { Graph.client.composer.react(message, "❤️") }
            }, onClick = {})
            when {
                big -> Text(if (p.type == MessageType.HEART) "❤️" else p.text.orEmpty(), fontSize = 44.sp, modifier = clickModifier.padding(4.dp))
                p.type == MessageType.MEDIA && once && !mine && !opened -> {
                    Row(
                        Modifier.clip(shape).background(Aez.colors.bubbleOther).combinedClickable(onLongClick = onLongPress) {
                            opened = true
                            Graph.launch { Graph.client.composer.recordView(message.id) }
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Visibility, null, tint = Aez.colors.text, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("View once · tap to open", color = Aez.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                p.type == MessageType.MEDIA -> Column(clickModifier.widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (ref in p.media) MediaBubble(ref)
                    p.text?.takeIf { it.isNotBlank() }?.let { Text(it, modifier = Modifier.background(Aez.colors.bubbleOther).padding(10.dp), color = Aez.colors.text) }
                }
                p.type == MessageType.VOICE && p.media.isNotEmpty() -> Box(clickModifier.clip(shape).background(if (mine) Brand.violet else Aez.colors.bubbleOther)) {
                    VoiceBubble(p.media.first(), if (mine) Color.White else Aez.colors.text)
                }
                p.type == MessageType.SHARE -> SharedPost(p, state, clickModifier)
                p.type == MessageType.CALL -> CallBubble(p, mine, clickModifier.clip(shape).background(Aez.colors.bubbleOther)) {
                    message.dto.threadId?.let { Graph.calls.start(it, p.call?.video ?: true) }
                }
                else -> {
                    val story = p.storyId?.let { state.entities[it] }
                    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        if (p.type == MessageType.STORY_REPLY || p.type == MessageType.STORY_REACTION) {
                            Text(if (mine) "You replied to their story" else "Replied to your story", fontSize = 11.sp, color = Aez.colors.text3)
                            story?.payloadAs<StoryPayload>()?.let { s ->
                                Box(Modifier.padding(vertical = 4.dp).width(90.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp))) {
                                    MediaImage(s.media?.poster ?: s.media, Modifier.fillMaxSize(), maxEdge = 360)
                                }
                            }
                        }
                        Box(
                            clickModifier.widthIn(max = 280.dp).clip(shape)
                                .then(if (mine) Modifier.background(Brand.messageGradient) else Modifier.background(Aez.colors.bubbleOther))
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            RichText(p.text.orEmpty(), style = TextStyle(color = if (mine) Color.White else Aez.colors.text, fontSize = 15.sp, lineHeight = 20.sp))
                        }
                    }
                }
            }
        }
        if (p.edited) Text("Edited", fontSize = 10.sp, color = Aez.colors.text3, modifier = Modifier.padding(horizontal = 40.dp))
        if (reactions.isNotEmpty()) {
            Text(
                reactions.mapNotNull { it.payloadAs<ReactionPayload>()?.emoji }.distinct().joinToString("") + if (reactions.size > 1) " ${reactions.size}" else "",
                fontSize = 13.sp,
                modifier = Modifier.padding(start = if (mine) 0.dp else 40.dp, end = 6.dp).clip(RoundedCornerShape(12.dp))
                    .background(Aez.colors.elev2).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MediaBubble(ref: MediaRef) {
    val ratio = if (ref.width > 0 && ref.height > 0) (ref.width.toFloat() / ref.height).coerceIn(0.56f, 1.8f) else 1f
    Box(Modifier.width(240.dp).aspectRatio(ratio)) {
        if (ref.mime.startsWith("video/")) {
            var play by remember { mutableStateOf(false) }
            if (play) VideoPlayer(ref, Modifier.fillMaxSize(), playing = true, loop = false, fill = true, controls = true)
            else {
                MediaImage(ref.poster, Modifier.fillMaxSize().clickable { play = true })
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(48.dp))
            }
        } else {
            MediaImage(ref, Modifier.fillMaxSize(), maxEdge = 1080)
        }
    }
}

@Composable
private fun SharedPost(p: MessagePayload, state: AppState, modifier: Modifier) {
    val nav = LocalNav.current
    val entity = p.sharedObjectId?.let { state.entities[it] }
    val post = entity?.post()
    Column(modifier.width(230.dp).clip(RoundedCornerShape(18.dp)).background(Aez.colors.bubbleOther).clickable {
        p.sharedObjectId?.let { nav.go(Screen.Post(it)) }
    }) {
        Text("@" + (state.users[p.sharedAuthorId]?.username ?: "post"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Aez.colors.text,
            modifier = Modifier.padding(10.dp))
        val cover = post?.media?.firstOrNull()?.let { if (it.mime.startsWith("video/")) it.poster else it }
        if (cover != null) MediaImage(cover, Modifier.fillMaxWidth().aspectRatio(1f), maxEdge = 720)
        else Text("Post unavailable", color = Aez.colors.text3, modifier = Modifier.padding(10.dp))
        post?.caption?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, fontSize = 13.sp, color = Aez.colors.text2, modifier = Modifier.padding(10.dp)) }
    }
}

@Composable
private fun CallBubble(p: MessagePayload, mine: Boolean, modifier: Modifier, onCallBack: () -> Unit) {
    val call = p.call ?: return
    Row(modifier.clickable(onClick = onCallBack).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (call.missed) Icons.Outlined.PhoneMissed else if (call.video) Icons.Outlined.Videocam else Icons.Outlined.Call, null,
            tint = if (call.missed) Aez.colors.danger else Aez.colors.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (call.missed) "Missed call" else if (call.video) "Video call" else "Audio call", fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
            val s = call.durationMs / 1000
            Text(if (call.missed) "Tap to call back" else "%d:%02d".format(s / 60, s % 60), fontSize = 12.sp, color = Aez.colors.text3)
        }
    }
}

/** A voice note, streamed and decrypted through the same data source as video. */
@OptIn(UnstableApi::class)
@Composable
private fun VoiceBubble(ref: MediaRef, tint: Color) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val player = remember(ref.blobId) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(EncryptedDataSource.Factory())).build().apply {
            setMediaItem(MediaItem.fromUri(EncryptedDataSource.uriOf(ref)))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                    progress = 0f
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            val d = player.duration
            if (d > 0) progress = player.currentPosition.toFloat() / d
            delay(100)
        }
    }
    Row(Modifier.width(220.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = tint,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { if (playing) player.pause() else player.play() })
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(26.dp), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val bars = 28
                for (i in 0 until bars) {
                    val h = 6 + ((i * 7919 + ref.blobId.hashCode()) and 15)
                    Box(Modifier.weight(1f).height(h.dp).clip(RoundedCornerShape(2.dp))
                        .background(tint.copy(alpha = if (i.toFloat() / bars <= progress) 1f else 0.4f)))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        val s = (ref.durationMs ?: 0) / 1000
        Text("%d:%02d".format(s / 60, s % 60), color = tint, fontSize = 12.sp)
    }
}

@Composable
private fun Composer(
    threadId: String,
    replyTo: Entity?,
    editing: Entity?,
    vanish: Boolean,
    once: Boolean,
    onCancel: () -> Unit,
    onToggleOnce: () -> Unit,
    onPick: () -> Unit,
    onSend: (String) -> Unit,
    onHeart: () -> Unit,
    onVoice: (xyz.aezdek.aezgram.client.PreparedMedia) -> Unit,
) {
    val context = LocalContext.current
    var text by remember(editing?.id) { mutableStateOf(editing?.payloadAs<MessagePayload>()?.text.orEmpty()) }
    var lastTyping by remember { mutableLongStateOf(0L) }
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { recorder.start() }.onSuccess { recording = true }
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        val banner = when {
            editing != null -> "Editing message"
            replyTo != null -> "Replying to " + (replyTo.payloadAs<MessagePayload>()?.text?.take(60) ?: "a message")
            else -> null
        }
        if (banner != null) {
            Row(Modifier.fillMaxWidth().background(Aez.colors.elev1).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(banner, color = Aez.colors.text2, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text("✕", color = Aez.colors.text2, modifier = Modifier.clickable(onClick = onCancel))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(26.dp))
                .background(if (vanish) Brand.violet.copy(alpha = 0.18f) else Aez.colors.elev1).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                Box(Modifier.size(10.dp).background(Color(0xFFFF3040), CircleShape))
                Spacer(Modifier.width(10.dp))
                val s = elapsed / 1000
                Text("Recording  %d:%02d".format(s / 60, s % 60), color = Aez.colors.text, modifier = Modifier.weight(1f))
                Text("Cancel", color = Aez.colors.text2, modifier = Modifier.clickable { recorder.cancel(); recording = false }.padding(10.dp))
                Box(Modifier.size(38.dp).clip(CircleShape).background(Brand.pink).clickable {
                    recording = false
                    recorder.stop()?.let(onVoice)
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Stop, "Send voice message", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                return@Row
            }
            Box(Modifier.size(38.dp).clip(CircleShape).background(Brand.gradient).clickable(onClick = onPick), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Image, "Photos", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) Text(if (vanish) "Vanish message…" else "Message…", color = Aez.colors.text3, fontSize = 15.sp)
                BasicTextField(
                    text,
                    {
                        text = it
                        val now = System.currentTimeMillis()
                        if (now - lastTyping > 3000) {
                            lastTyping = now
                            Graph.client.sendTyping(threadId)
                        }
                    },
                    textStyle = TextStyle(color = Aez.colors.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Aez.colors.text),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
            }
            if (text.isNotBlank()) {
                Text(if (editing != null) "Save" else "Send", color = Brand.violet, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    onSend(text.trim())
                    text = ""
                }.padding(horizontal = 12.dp, vertical = 10.dp))
            } else {
                IconAction(Icons.Outlined.Visibility, if (once) "View once on" else "View once off", onToggleOnce,
                    tint = if (once) Brand.pink else Aez.colors.text2, size = 22.dp)
                IconAction(Icons.Filled.Mic, "Voice message", { micPermission.launch(Manifest.permission.RECORD_AUDIO) }, size = 24.dp)
                IconAction(Icons.Filled.Favorite, "Send a heart", onHeart, tint = Brand.pink, size = 24.dp)
            }
        }
    }
}
