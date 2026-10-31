package xyz.aezdek.hush.ui.stories

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.media.VideoPlayer
import xyz.aezdek.hush.protocol.AnswerPayload
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.HighlightPayload
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.Sticker
import xyz.aezdek.hush.protocol.StickerType
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.StoryText
import xyz.aezdek.hush.protocol.ViewDto
import xyz.aezdek.hush.protocol.VotePayload
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.ActionSheet
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.ConfirmDialog
import xyz.aezdek.hush.ui.components.MediaImage
import xyz.aezdek.hush.ui.components.SheetItem
import xyz.aezdek.hush.ui.components.ago
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand

/** Text-story backgrounds: the same names and colours as web/src/features/stories/backgrounds.ts. */
object StoryBackgrounds {
    private fun c(hex: Long) = Color(hex or 0xFF000000)
    val all: Map<String, List<Color>> = linkedMapOf(
        "sunset" to listOf(c(0x7c5cff), c(0xe84393), c(0xff8a3d)),
        "ocean" to listOf(c(0x0f2027), c(0x2c5364), c(0x00c2a8)),
        "aurora" to listOf(c(0x11998e), c(0x38ef7d)),
        "midnight" to listOf(c(0x0b0b12), c(0x24243e), c(0x3b2a6b)),
        "peach" to listOf(c(0xffb88c), c(0xde6262)),
        "lilac" to listOf(c(0xc471f5), c(0xfa71cd)),
        "mono" to listOf(c(0x1c1c1f), c(0x3a3a40)),
        "lemon" to listOf(c(0xf7971e), c(0xffd200)),
    )

    fun brush(name: String?): Brush = Brush.linearGradient(all[name ?: "sunset"] ?: all.getValue("sunset"))
}

fun storyFont(name: String?): TextStyle = when (name) {
    "classic" -> TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 34.sp, letterSpacing = (-0.3).sp)
    "strong" -> TextStyle(fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 0.6.sp)
    "typewriter" -> TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 24.sp)
    else -> TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.5).sp)
}

/** Stories by author: swipe between people, tap through each person's stories. */
@Composable
fun StoryViewer(userIds: List<String>, start: Int) {
    val nav = LocalNav.current
    val index by Graph.client.index.collectAsState()
    val people = userIds.filter { !index.stories[it].isNullOrEmpty() }
    if (people.isEmpty()) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, people.lastIndex)) { people.size }
    val scope = rememberCoroutineScope()
    HorizontalPager(pager, Modifier.fillMaxSize().background(Color.Black), key = { people[it] }) { page ->
        val stories = index.stories[people[page]].orEmpty()
        StoryRun(
            stories, active = pager.currentPage == page,
            onFinished = { if (page < people.lastIndex) scope.launch { pager.animateScrollToPage(page + 1) } else nav.back() },
            onBefore = { if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } },
        )
    }
}

@Composable
fun HighlightViewer(highlightId: String) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val highlight = state.entities[highlightId]
    val payload = highlight?.payloadAs<HighlightPayload>()
    val stories = payload?.stories.orEmpty().mapNotNull { state.entities[it.id] }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (stories.isEmpty()) {
            Text("Loading…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            LaunchedEffect(highlightId) {
                delay(6000)
                if (Graph.client.state.value.entities[highlightId]?.payloadAs<HighlightPayload>()?.stories.orEmpty().none { Graph.client.state.value.entities.containsKey(it.id) }) nav.back()
            }
        } else {
            StoryRun(stories, active = true, onFinished = { nav.back() }, onBefore = {}, title = payload?.title)
        }
    }
}

@Composable
private fun StoryRun(stories: List<Entity>, active: Boolean, onFinished: () -> Unit, onBefore: () -> Unit, title: String? = null) {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    if (stories.isEmpty()) return
    var current by remember { mutableIntStateOf(stories.indexOfFirst { !state.seenStories.containsKey(it.id) }.takeIf { it >= 0 } ?: 0) }
    val story = stories[current.coerceIn(0, stories.lastIndex)]
    val payload = story.payloadAs<StoryPayload>() ?: StoryPayload()
    val mine = story.dto.authorId == me.id
    var paused by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var viewers by remember { mutableStateOf(false) }
    val progress = remember(story.id) { Animatable(0f) }
    val video = payload.media?.mime?.startsWith("video/") == true
    val duration = if (video) (payload.media?.durationMs ?: 15_000L).coerceIn(1_000, 60_000) else payload.durationMs.coerceIn(2_000, 20_000)

    LaunchedEffect(story.id, active) {
        if (!active) return@LaunchedEffect
        Graph.client.engine.markStorySeen(story.id)
        if (!mine) Graph.client.composer.recordView(story.id)
    }
    LaunchedEffect(story.id, active, paused || typing || menu || viewers) {
        if (!active || paused || typing || menu || viewers) {
            progress.stop()
            return@LaunchedEffect
        }
        val remaining = ((1f - progress.value) * duration).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (current < stories.lastIndex) current++ else onFinished()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().padding(top = 0.dp).clip(RoundedCornerShape(0.dp))) {
            StoryCanvas(payload, active && !paused)
            // Tap left/right thirds to step, hold anywhere to pause.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().pointerInput(story.id) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val start = System.currentTimeMillis()
                        paused = true
                        waitForUpOrCancellation()
                        paused = false
                        if (System.currentTimeMillis() - start < 250) { if (current > 0) current-- else onBefore() }
                    }
                })
                Box(Modifier.weight(2f).fillMaxSize().pointerInput(story.id) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val start = System.currentTimeMillis()
                        paused = true
                        waitForUpOrCancellation()
                        paused = false
                        if (System.currentTimeMillis() - start < 250) { if (current < stories.lastIndex) current++ else onFinished() }
                    }
                })
            }
            Stickers(story, payload, index, state, onPause = { paused = it })
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                stories.forEachIndexed { i, _ ->
                    val fraction = when {
                        i < current -> 1f
                        i == current -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(fraction).height(2.5.dp).background(Color.White))
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val name = if (mine) me.username else state.users[story.dto.authorId]?.username ?: ""
                Avatar(story.dto.authorId, 34.dp, index, name) { nav.go(Screen.Profile(story.dto.authorId)) }
                Spacer(Modifier.width(10.dp))
                Text(title ?: name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("  ${ago(story.dto.createdAt)}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                if (story.dto.audience == Audience.CLOSE_FRIENDS) {
                    Text("  Close friends", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp).background(Brand.closeFriends, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                if (mine) Icon(Icons.Outlined.MoreHoriz, "More", tint = Color.White, modifier = Modifier.size(40.dp).clickable { menu = true }.padding(8.dp))
                Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(40.dp).clickable { nav.back() }.padding(8.dp))
            }
        }
        if (mine) {
            Row(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp).clickable { viewers = true }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Visibility, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Activity", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        } else {
            ReplyBar(story, onFocus = { typing = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (menu) {
        var confirm by remember { mutableStateOf(false) }
        ActionSheet({ menu = false }) {
            SheetItem("Delete story", Icons.Outlined.Delete, danger = true) { confirm = true }
        }
        if (confirm) {
            ConfirmDialog("Delete story?", "It's removed for everyone.", "Delete", onConfirm = {
                menu = false
                Graph.launch { Graph.client.composer.delete(story.id) }
            }, onDismiss = { confirm = false })
        }
    }
    if (viewers) Viewers(story, index, state) { viewers = false }
}

/** The story itself: photo, video or text on a gradient, plus the drawing layer. */
@Composable
private fun StoryCanvas(payload: StoryPayload, playing: Boolean) {
    Box(Modifier.fillMaxSize()) {
        val media = payload.media
        when {
            media != null && media.mime.startsWith("video/") -> {
                MediaImage(media.poster, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                VideoPlayer(media, Modifier.fillMaxSize(), playing = playing, loop = false, fill = false)
            }
            media != null -> MediaImage(media, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, maxEdge = 1440)
            else -> TextStory(payload.text ?: StoryText(""), Modifier.fillMaxSize())
        }
        payload.drawing?.let { MediaImage(it, Modifier.fillMaxSize().background(Color.Transparent), contentScale = ContentScale.Fit) }
    }
}

@Composable
fun TextStory(text: StoryText, modifier: Modifier = Modifier) {
    Box(modifier.background(StoryBackgrounds.brush(text.background)).padding(28.dp), contentAlignment = Alignment.Center) {
        Text(
            text.text, color = Color.White, style = storyFont(text.font),
            textAlign = when (text.align) { "left" -> TextAlign.Start; "right" -> TextAlign.End; else -> TextAlign.Center },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Interactive stickers, placed by their fractional position so they land in the same spot on every screen. */
@Composable
private fun Stickers(story: Entity, payload: StoryPayload, index: Index, state: AppState, onPause: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        for (sticker in payload.stickers) {
            Box(
                Modifier.offset(x = maxWidth * sticker.x - 110.dp, y = maxHeight * sticker.y - 40.dp).width(220.dp)
                    .rotate(sticker.rotation).scale(sticker.scale.coerceIn(0.4f, 3f)),
                contentAlignment = Alignment.Center,
            ) {
                StickerView(sticker, story, index, state, onPause)
            }
        }
    }
}

@Composable
private fun StickerView(sticker: Sticker, story: Entity, index: Index, state: AppState, onPause: (Boolean) -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val me = state.me ?: return
    val mine = story.dto.authorId == me.id
    val votes = index.children[story.id].orEmpty().filter { it.dto.kind == Kind.VOTE }.mapNotNull { e -> e.payloadAs<VotePayload>()?.takeIf { it.stickerId == sticker.id }?.let { e.dto.authorId to it } }
    val myVote = votes.firstOrNull { it.first == me.id }?.second
    val chip = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp)
    when (sticker.type) {
        StickerType.TEXT -> Text(sticker.text.orEmpty(), color = sticker.color?.let { parseColor(it) } ?: Color.White, style = storyFont("modern").copy(fontSize = 26.sp),
            textAlign = TextAlign.Center)
        StickerType.EMOJI -> Text(sticker.emoji ?: sticker.text.orEmpty(), fontSize = 64.sp)
        StickerType.MENTION -> Text("@" + (state.users[sticker.userId]?.username ?: sticker.text.orEmpty()), color = Brand.pink, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = chip.clickable { sticker.userId?.let { nav.go(Screen.Profile(it)) } })
        StickerType.HASHTAG -> Text("#" + sticker.text.orEmpty().removePrefix("#"), color = Brand.violet, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = chip.clickable { nav.go(Screen.Hashtag(sticker.text.orEmpty().removePrefix("#"))) })
        StickerType.LOCATION -> Text("📍 " + sticker.text.orEmpty(), color = Brand.violet, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = chip)
        StickerType.LINK -> Text("🔗 " + (sticker.text ?: sticker.url.orEmpty().removePrefix("https://")), color = Color(0xFF2D5BE3), fontWeight = FontWeight.Bold,
            fontSize = 15.sp, modifier = chip.clickable { sticker.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } })
        StickerType.COUNTDOWN -> {
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
            val left = ((sticker.endsAt ?: now) - now).coerceAtLeast(0) / 1000
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Brand.gradient).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sticker.text ?: "Countdown", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(if (left == 0L) "It's time!" else "%dd %02dh %02dm %02ds".format(left / 86400, left / 3600 % 24, left / 60 % 60, left % 60),
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
        StickerType.POLL, StickerType.QUIZ -> {
            val quiz = sticker.type == StickerType.QUIZ
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp).width(200.dp)) {
                sticker.text?.let { Text(it, color = Color.Black, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) }
                val total = votes.size.coerceAtLeast(1)
                sticker.options.forEachIndexed { i, option ->
                    val count = votes.count { it.second.option == i }
                    val reveal = myVote != null || mine
                    val correct = quiz && sticker.correct == i
                    val bg = when {
                        quiz && reveal && correct -> Color(0xFF22C55E)
                        quiz && myVote?.option == i -> Color(0xFFFF3B4E)
                        else -> Color(0xFFF1F1F4)
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable(enabled = myVote == null && !mine) {
                        Graph.launch { Graph.client.composer.vote(story.id, VotePayload(sticker.id, option = i)) }
                    }) {
                        if (reveal && !quiz) Box(Modifier.fillMaxWidth(count.toFloat() / total).height(38.dp).background(Brand.violet.copy(alpha = 0.25f)))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(option, color = if (bg == Color(0xFFF1F1F4)) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (reveal) Text("${count * 100 / total}%", color = Color.Black.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        StickerType.SLIDER -> {
            var value by remember(story.id) { mutableFloatStateOf(myVote?.value ?: 0.5f) }
            val average = votes.mapNotNull { it.second.value }.takeIf { it.isNotEmpty() }?.average()
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp).width(200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                sticker.text?.let { Text(it, color = Color.Black, fontWeight = FontWeight.Bold) }
                Slider(
                    value, { value = it; onPause(true) },
                    onValueChangeFinished = {
                        onPause(false)
                        if (myVote == null && !mine) Graph.launch { Graph.client.composer.vote(story.id, VotePayload(sticker.id, value = value)) }
                    },
                    enabled = myVote == null && !mine,
                    colors = SliderDefaults.colors(thumbColor = Brand.pink, activeTrackColor = Brand.pink),
                )
                Text(sticker.emoji ?: "😍", fontSize = 26.sp)
                if (average != null && (myVote != null || mine)) Text("Average ${(average * 100).toInt()}%", color = Color.Black.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
        StickerType.QUESTION -> {
            var answering by remember { mutableStateOf(false) }
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).width(210.dp)) {
                Text(sticker.text ?: "Ask me a question", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(Brand.gradient).padding(10.dp))
                Text(if (mine) "See answers in your notifications" else "Type something…", color = Color.Black.copy(alpha = 0.55f), fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !mine) { answering = true; onPause(true) }.padding(12.dp), textAlign = TextAlign.Center)
            }
            if (answering) {
                var text by remember { mutableStateOf("") }
                ActionSheet({ answering = false; onPause(false) }) {
                    Text(sticker.text ?: "Ask me a question", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                    AezField(text, { text = it.take(300) }, "Your answer", singleLine = false)
                    Spacer(Modifier.height(10.dp))
                    AezButton("Send", {
                        answering = false
                        onPause(false)
                        Graph.launch { Graph.client.composer.answer(story, AnswerPayload(story.id, sticker.id, text.trim())) }
                        Graph.toast("Sent privately to @${state.users[story.dto.authorId]?.username}")
                    }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
                }
            }
        }
    }
}

private fun parseColor(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

@Composable
private fun ReplyBar(story: Entity, onFocus: (Boolean) -> Unit, modifier: Modifier) {
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    var text by remember(story.id) { mutableStateOf("") }
    val liked = index.isLiked(story.id, me.id)
    fun sendToAuthor(payload: MessagePayload) {
        Graph.launch {
            val threadId = Graph.client.composer.openDirect(story.dto.authorId)
            Graph.client.composer.sendMessage(threadId, payload)
            Graph.toast("Sent")
        }
    }
    Column(modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp)) {
        if (text.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (emoji in listOf("😂", "😮", "😍", "😢", "👏", "🔥")) {
                    Text(emoji, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable {
                        sendToAuthor(MessagePayload(MessageType.STORY_REACTION, text = emoji, storyId = story.id))
                    }.padding(4.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (text.isEmpty()) Text("Send message", color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
                BasicTextField(text, {
                    text = it
                    onFocus(it.isNotEmpty())
                }, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            if (text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = Color.White, modifier = Modifier.size(40.dp).clickable {
                    sendToAuthor(MessagePayload(MessageType.STORY_REPLY, text = text.trim(), storyId = story.id))
                    text = ""
                    onFocus(false)
                }.padding(8.dp))
            } else {
                Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Like", tint = if (liked) Color(0xFFFF3040) else Color.White,
                    modifier = Modifier.size(40.dp).clickable { Graph.launch { Graph.client.composer.toggleLike(story.id) } }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun Viewers(story: Entity, index: Index, state: AppState, onDismiss: () -> Unit) {
    val views by produceState<List<ViewDto>?>(null, story.id) {
        value = runCatching { Graph.client.api.get<List<ViewDto>>("/objects/${story.id}/views") }.getOrDefault(emptyList())
    }
    val likes = index.likesOf(story.id).map { it.dto.authorId }.toSet()
    ActionSheet(onDismiss) {
        Text(views?.let { if (it.size == 1) "1 viewer" else "${it.size} viewers" } ?: "Loading…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(views.orEmpty(), key = { it.userId }) { view ->
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(view.userId, 40.dp, index, state.users[view.userId]?.username)
                    Spacer(Modifier.width(12.dp))
                    Text(state.users[view.userId]?.username ?: "", Modifier.weight(1f), color = Aez.colors.text, fontWeight = FontWeight.SemiBold)
                    if (view.userId in likes) Icon(Icons.Filled.Favorite, "Liked", tint = Color(0xFFFF3040), modifier = Modifier.size(18.dp))
                    Text("  ${ago(view.at)}", color = Aez.colors.text3, fontSize = 12.sp)
                }
            }
        }
    }
}
