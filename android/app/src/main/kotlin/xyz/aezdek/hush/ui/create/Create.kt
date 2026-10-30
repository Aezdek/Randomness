package xyz.aezdek.hush.ui.create

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.Composer
import xyz.aezdek.hush.media.Filters
import xyz.aezdek.hush.media.ImageEdit
import xyz.aezdek.hush.media.MediaKind
import xyz.aezdek.hush.media.MediaPrep
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.NotePayload
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ReelPayload
import xyz.aezdek.hush.protocol.Sticker
import xyz.aezdek.hush.protocol.StickerType
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.StoryText
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.components.ActionSheet
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.Chip
import xyz.aezdek.hush.ui.components.IconAction
import xyz.aezdek.hush.ui.components.SheetItem
import xyz.aezdek.hush.ui.components.TopBar
import xyz.aezdek.hush.ui.messages.PersonRow
import xyz.aezdek.hush.ui.messages.ToggleRow
import xyz.aezdek.hush.ui.messages.reachable
import xyz.aezdek.hush.ui.stories.StoryBackgrounds
import xyz.aezdek.hush.ui.stories.TextStory
import xyz.aezdek.hush.ui.stories.storyFont
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand

/** Progress while transcoding, encrypting and uploading: nothing leaves the phone unencrypted. */
@Composable
private fun Sharing(label: String, value: Float) {
    Box(Modifier.fillMaxSize().background(Aez.colors.bg.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
                Text("🔒", fontSize = 30.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Brand.pink, trackColor = Aez.colors.elev2)
        }
    }
}

private enum class Aspect(val label: String, val ratio: Float?) { ORIGINAL("Original", null), SQUARE("1:1", 1f), PORTRAIT("4:5", 0.8f) }

/** Centre crop of a [w]×[h] image to [ratio] (width / height), as fractions. */
private fun centerCrop(w: Int, h: Int, ratio: Float?): RectF? {
    ratio ?: return null
    val current = w.toFloat() / h
    return if (current > ratio) {
        val keep = ratio / current
        RectF((1 - keep) / 2, 0f, (1 + keep) / 2, 1f)
    } else {
        val keep = current / ratio
        RectF(0f, (1 - keep) / 2, 1f, (1 + keep) / 2)
    }
}

private class Picked(val uri: Uri, val video: Boolean)

@Composable
fun CreatePost() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val picked = remember { mutableStateListOf<Picked>() }
    var step by remember { mutableStateOf(0) }
    var aspect by remember { mutableStateOf(Aspect.PORTRAIT) }
    var filter by remember { mutableStateOf(0) }
    var caption by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var tagged by remember { mutableStateOf(listOf<String>()) }
    var hideLikes by remember { mutableStateOf(false) }
    var commentsOff by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isEmpty() && picked.isEmpty()) nav.back()
        picked.addAll(uris.map { Picked(it, MediaPrep.isVideo(context, it)) })
    }
    LaunchedEffect(Unit) { if (picked.isEmpty()) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    fun share() {
        scope.launch {
            val refs = ArrayList<MediaRef>()
            val ok = Graph.attempt {
                picked.forEachIndexed { i, item ->
                    val n = "${i + 1} of ${picked.size}"
                    val base = i.toFloat() / picked.size
                    val span = 1f / picked.size
                    val prepared = if (item.video) {
                        progress = "Optimising video $n" to base
                        MediaPrep.prepareVideo(context, item.uri, MediaKind.POST) { f -> progress = "Optimising video $n" to base + f * span * 0.6f }
                    } else {
                        progress = "Preparing photo $n" to base
                        val f = Filters.all[filter]
                        withContext(Dispatchers.Default) {
                            val bitmap = MediaPrep.decode(context, item.uri, MediaKind.POST.imageEdge * 2)
                            try {
                                MediaPrep.prepareBitmap(context, bitmap, MediaKind.POST,
                                    ImageEdit(crop = centerCrop(bitmap.width, bitmap.height, aspect.ratio), filter = f.matrix, vignette = f.vignette))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    progress = "Encrypting and uploading $n" to base + span * 0.6f
                    refs += Graph.client.media.upload(prepared) { f -> progress = "Encrypting and uploading $n" to base + span * (0.6f + 0.4f * f) }
                }
                progress = "Sharing" to 1f
                Graph.client.composer.createPost(PostPayload(caption.trim(), refs, location.trim().ifBlank { null }, tagged, hideLikes, commentsOff))
            }
            progress = null
            if (ok != null) nav.replace(Screen.Post(ok.id))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(if (step == 0) "New post" else "Details", actions = {
                if (picked.isNotEmpty()) {
                    Text(if (step == 0) "Next" else "Share", color = Aez.colors.link, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.clickable { if (step == 0) step = 1 else share() }.padding(14.dp))
                }
            })
            if (step == 0) {
                if (picked.isNotEmpty()) {
                    val pager = rememberPagerState { picked.size }
                    Box(Modifier.fillMaxWidth().aspectRatio(aspect.ratio ?: 1f).background(Color.Black)) {
                        HorizontalPager(pager, Modifier.fillMaxSize()) { page -> Preview(picked[page], if (picked[page].video) null else Filters.all[filter]) }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (a in Aspect.entries) Chip(a.label, selected = aspect == a) { aspect = a }
                        Spacer(Modifier.weight(1f))
                        IconAction(Icons.Outlined.AddPhotoAlternate, "Add more", {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        })
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(Filters.all) { i, f ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { filter = i }) {
                                Box(Modifier.size(78.dp).clip(RoundedCornerShape(12.dp))
                                    .then(if (filter == i) Modifier.border(2.dp, Aez.colors.text, RoundedCornerShape(12.dp)) else Modifier)) {
                                    picked.firstOrNull { !it.video }?.let { Preview(it, f, maxEdge = 240) }
                                }
                                Text(f.name, fontSize = 12.sp, color = if (filter == i) Aez.colors.text else Aez.colors.text2, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AezField(caption, { caption = it.take(2200) }, "Write a caption…", singleLine = false, minLines = 3)
                    AezField(location, { location = it.take(80) }, "Add location")
                    SheetItem(if (tagged.isEmpty()) "Tag people" else "Tagged: " + tagged.mapNotNull { state.users[it]?.username }.joinToString(), null) { tagging = true }
                    ToggleRow(null, "Hide like count", hideLikes) { hideLikes = !hideLikes }
                    ToggleRow(null, "Turn off commenting", commentsOff) { commentsOff = !commentsOff }
                    Text("Your post is encrypted on this phone for your ${if (state.me?.isPrivate == true) "followers" else "Hush members"} only.",
                        fontSize = 12.sp, color = Aez.colors.text3)
                }
            }
        }
        progress?.let { (label, value) -> Sharing(label, value) }
    }
    if (tagging) {
        ActionSheet({ tagging = false }) {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(reachable(state), key = { it.id }) { user ->
                    PersonRow(user, index, selected = user.id in tagged) { tagged = if (user.id in tagged) tagged - user.id else tagged + user.id }
                }
            }
        }
    }
}

/** A local, unencrypted preview of picked media; [maxEdge] keeps filter thumbnails cheap. */
@Composable
private fun Preview(item: Picked, filter: Filters.Filter?, maxEdge: Int = 1080) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri, maxEdge) {
        value = withContext(Dispatchers.IO) {
            if (item.video) runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.uri)
                    retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge, maxEdge)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            else runCatching { MediaPrep.decode(context, item.uri, maxEdge) }.getOrNull()
        }
    }
    val matrix = filter?.matrix
    bitmap?.let {
        Image(
            it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            colorFilter = matrix?.let { m -> ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(m.array)) },
        )
    }
}

@Composable
fun CreateReel() {
    val nav = LocalNav.current
    val context = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var duration by remember { mutableStateOf(0L) }
    var range by remember { mutableStateOf(0f..1f) }
    var caption by remember { mutableStateOf("") }
    var hideLikes by remember { mutableStateOf(false) }
    var commentsOff by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked == null) {
            if (uri == null) nav.back()
            return@rememberLauncherForActivityResult
        }
        uri = picked
        scope.launch {
            duration = runCatching { withContext(Dispatchers.IO) { MediaPrep.probe(context, picked).durationMs } }.getOrDefault(0L)
            range = 0f..(minOf(duration, 180_000L).toFloat() / duration.coerceAtLeast(1))
        }
    }
    LaunchedEffect(Unit) { if (uri == null) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("New reel", actions = {
                val current = uri
                if (current != null) {
                    Text("Share", color = Aez.colors.link, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.clickable {
                        scope.launch {
                            val start = (range.start * duration).toLong()
                            val end = (range.endInclusive * duration).toLong()
                            val reel = Graph.attempt {
                                progress = "Optimising video" to 0f
                                val prepared = MediaPrep.prepareVideo(context, current, MediaKind.REEL, start..end) { f -> progress = "Optimising video" to f * 0.6f }
                                progress = "Encrypting and uploading" to 0.6f
                                val ref = Graph.client.media.upload(prepared) { f -> progress = "Encrypting and uploading" to 0.6f + f * 0.4f }
                                Graph.client.composer.createReel(ReelPayload(caption.trim(), ref, null, emptyList(), hideLikes, commentsOff))
                            }
                            progress = null
                            if (reel != null) nav.replace(Screen.Reels(reel.id))
                        }
                    }.padding(14.dp))
                }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                uri?.let { picked ->
                    Box(Modifier.fillMaxWidth(0.6f).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp))) {
                        Preview(Picked(picked, true), null)
                    }
                    if (duration > 0) {
                        val seconds = ((range.endInclusive - range.start) * duration / 1000).toInt()
                        Text("Trim · ${seconds}s (up to 3 min)", style = MaterialTheme.typography.titleSmall)
                        RangeSlider(range, { r ->
                            val maxSpan = 180_000f / duration
                            range = if (r.endInclusive - r.start > maxSpan) r.start..(r.start + maxSpan) else r
                        }, colors = SliderDefaults.colors(thumbColor = Brand.pink, activeTrackColor = Brand.pink))
                    }
                }
                AezField(caption, { caption = it.take(2200) }, "Write a caption…", singleLine = false, minLines = 3)
                ToggleRow(null, "Hide like count", hideLikes) { hideLikes = !hideLikes }
                ToggleRow(null, "Turn off commenting", commentsOff) { commentsOff = !commentsOff }
            }
        }
        progress?.let { (label, value) -> Sharing(label, value) }
    }
}

@Composable
fun CreateNote() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    var text by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Aez.colors.bg).imePadding()) {
        TopBar("New note")
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The note as your friends will see it above your photo in their inbox.
            Box(Modifier.padding(top = 12.dp)) {
                Box(
                    Modifier.width(260.dp).clip(RoundedCornerShape(24.dp)).background(Aez.colors.elev2).padding(horizontal = 20.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (text.isEmpty()) Text("Share a thought…", color = Aez.colors.text3, fontSize = 17.sp, textAlign = TextAlign.Center)
                    BasicTextField(text, { text = it.take(60) }, textStyle = TextStyle(color = Aez.colors.text, fontSize = 17.sp, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(Aez.colors.text), modifier = Modifier.fillMaxWidth())
                }
                emoji?.let { Text(it, fontSize = 24.sp, modifier = Modifier.align(Alignment.TopStart).offset(x = (-8).dp, y = (-12).dp)) }
            }
            Box(Modifier.padding(top = 4.dp, end = 70.dp).size(12.dp).clip(CircleShape).background(Aez.colors.elev2))
            Box(Modifier.padding(top = 3.dp, end = 52.dp).size(7.dp).clip(CircleShape).background(Aez.colors.elev2))
            Spacer(Modifier.height(8.dp))
            Avatar(me.id, 96.dp, index, me.username)
            Text("${text.length}/60 · friends see it for 24 hours", fontSize = 12.sp, color = Aez.colors.text3, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in listOf("🎵", "☕", "🏃", "🌙", "🎉", "💭")) {
                    Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).background(if (emoji == e) Aez.colors.elev3 else Color.Transparent)
                        .clickable { emoji = if (emoji == e) null else e }.padding(6.dp))
                }
            }
            AezButton("Share", {
                Graph.launch { Graph.client.composer.createNote(NotePayload(text.trim(), emoji)) }
                nav.back()
            }, Modifier.fillMaxWidth(), enabled = text.isNotBlank(), style = ButtonStyle.BRAND)
        }
    }
}

// ----------------------------------------------------------------------------------------------------------- story

private val STORY_FONTS = listOf("modern", "classic", "strong", "typewriter")

@Composable
fun CreateStory() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    var media by remember { mutableStateOf<Picked?>(null) }
    var text by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("sunset") }
    var font by remember { mutableStateOf(0) }
    var align by remember { mutableStateOf("center") }
    val stickers = remember { mutableStateListOf<Sticker>() }
    var closeFriends by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) media = Picked(uri, MediaPrep.isVideo(context, uri))
    }

    fun share() {
        scope.launch {
            val story = Graph.attempt {
                val ref = media?.let { m ->
                    val prepared = if (m.video) {
                        progress = "Optimising video" to 0f
                        MediaPrep.prepareVideo(context, m.uri, MediaKind.STORY) { f -> progress = "Optimising video" to f * 0.6f }
                    } else {
                        progress = "Preparing photo" to 0.2f
                        MediaPrep.prepareImage(context, m.uri, MediaKind.STORY)
                    }
                    progress = "Encrypting and uploading" to 0.6f
                    Graph.client.media.upload(prepared) { f -> progress = "Encrypting and uploading" to 0.6f + f * 0.4f }
                }
                val payload = StoryPayload(
                    media = ref,
                    text = if (ref == null) StoryText(text.trim(), background, STORY_FONTS[font], align) else null,
                    stickers = stickers.toList() + if (ref != null && text.isNotBlank()) listOf(Sticker(Composer.newObjectId(), StickerType.TEXT, 0.5f, 0.3f, text = text.trim())) else emptyList(),
                    durationMs = ref?.durationMs ?: 6_000L,
                )
                Graph.client.composer.createStory(payload, closeFriends)
            }
            progress = null
            if (story != null) nav.back()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val current = media
        if (current != null) Preview(current, null) else TextStory(StoryText("", background, STORY_FONTS[font], align), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            if (text.isEmpty()) Text(if (current == null) "Start typing…" else "", color = Color.White.copy(alpha = 0.6f), style = storyFont(STORY_FONTS[font]))
            BasicTextField(text, { text = it.take(400) }, cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth(),
                textStyle = storyFont(STORY_FONTS[font]).copy(color = Color.White,
                    textAlign = when (align) { "left" -> TextAlign.Start; "right" -> TextAlign.End; else -> TextAlign.Center }))
        }
        DraggableStickers(stickers)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Outlined.Close, "Close", { nav.back() }, tint = Color.White)
            Spacer(Modifier.weight(1f))
            if (current == null) {
                IconAction(Icons.Outlined.Palette, "Background", {
                    val names = StoryBackgrounds.all.keys.toList()
                    background = names[(names.indexOf(background) + 1) % names.size]
                }, tint = Color.White)
            }
            IconAction(Icons.Outlined.TextFields, "Font", { font = (font + 1) % STORY_FONTS.size }, tint = Color.White)
            IconAction(Icons.Outlined.FormatAlignCenter, "Align", {
                align = when (align) { "center" -> "left"; "left" -> "right"; else -> "center" }
            }, tint = Color.White)
            IconAction(Icons.Outlined.EmojiEmotions, "Stickers", { adding = true }, tint = Color.White)
            IconAction(Icons.Outlined.AddPhotoAlternate, "Photo or video", {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }, tint = Color.White)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AezButton("Your story", ::share, Modifier.weight(1f), style = ButtonStyle.PRIMARY, enabled = current != null || text.isNotBlank())
            Box(
                Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp)).background(if (closeFriends) Brand.closeFriends else Color(0x33FFFFFF))
                    .clickable { closeFriends = !closeFriends },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (closeFriends) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(" Close friends", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        progress?.let { (label, value) -> Sharing(label, value) }
    }
    if (adding) StickerPicker(state, index, onAdd = { stickers += it; adding = false }) { adding = false }
}

@Composable
private fun DraggableStickers(stickers: MutableList<Sticker>) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        stickers.forEachIndexed { i, sticker ->
            Box(
                Modifier.offset(x = with(density) { (sticker.x * width).toDp() } - 100.dp, y = with(density) { (sticker.y * height).toDp() } - 30.dp)
                    .width(200.dp)
                    .pointerInput(sticker.id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val s = stickers.getOrNull(i) ?: return@detectDragGestures
                            stickers[i] = s.copy(x = (s.x + drag.x / width).coerceIn(0.05f, 0.95f), y = (s.y + drag.y / height).coerceIn(0.08f, 0.92f))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(stickerLabel(sticker), color = Color.Black, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

private fun stickerLabel(s: Sticker): String = when (s.type) {
    StickerType.POLL, StickerType.QUIZ -> "${s.text ?: "?"}  ·  ${s.options.joinToString(" / ")}"
    StickerType.QUESTION -> "❓ ${s.text}"
    StickerType.SLIDER -> "${s.emoji} ${s.text}"
    StickerType.COUNTDOWN -> "⏳ ${s.text}"
    StickerType.MENTION -> "@${s.text}"
    StickerType.HASHTAG -> "#${s.text}"
    StickerType.LOCATION -> "📍 ${s.text}"
    StickerType.LINK -> "🔗 ${s.text ?: s.url}"
    StickerType.EMOJI -> s.emoji ?: ""
    else -> s.text ?: ""
}

@Composable
private fun StickerPicker(state: xyz.aezdek.hush.client.AppState, index: xyz.aezdek.hush.client.Index, onAdd: (Sticker) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var a by remember { mutableStateOf("Yes") }
    var b by remember { mutableStateOf("No") }
    var hours by remember { mutableFloatStateOf(24f) }
    fun make(type: String, extra: (Sticker) -> Sticker = { it }) =
        onAdd(extra(Sticker(Composer.newObjectId(), type, 0.5f, 0.62f, text = text.trim().ifBlank { null })))
    ActionSheet(onDismiss) {
        when (kind) {
            null -> {
                Text("Stickers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (e in listOf("🔥", "😍", "🎉", "✨", "😂", "💜")) Text(e, fontSize = 32.sp, modifier = Modifier.clickable {
                        onAdd(Sticker(Composer.newObjectId(), StickerType.EMOJI, 0.5f, 0.4f, emoji = e))
                    })
                }
                for ((type, label) in listOf(
                    StickerType.POLL to "Poll", StickerType.QUIZ to "Quiz", StickerType.QUESTION to "Questions", StickerType.SLIDER to "Emoji slider",
                    StickerType.COUNTDOWN to "Countdown", StickerType.MENTION to "Mention", StickerType.HASHTAG to "Hashtag",
                    StickerType.LOCATION to "Location", StickerType.LINK to "Link",
                )) SheetItem(label) { kind = type }
            }
            StickerType.MENTION -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(reachable(state), key = { it.id }) { user ->
                    PersonRow(user, index) { onAdd(Sticker(Composer.newObjectId(), StickerType.MENTION, 0.5f, 0.62f, text = user.username, userId = user.id)) }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val type = kind!!
                AezField(text, { text = it.take(80) }, when (type) {
                    StickerType.LINK -> "https://…"
                    StickerType.QUESTION -> "Ask me a question"
                    StickerType.HASHTAG -> "hashtag"
                    StickerType.LOCATION -> "Place"
                    else -> "Question or title"
                })
                if (type == StickerType.POLL || type == StickerType.QUIZ) {
                    AezField(a, { a = it.take(24) }, if (type == StickerType.QUIZ) "Right answer" else "Option 1")
                    AezField(b, { b = it.take(24) }, if (type == StickerType.QUIZ) "Wrong answer" else "Option 2")
                }
                if (type == StickerType.COUNTDOWN) {
                    Text("Ends in ${hours.toInt()} hours", color = Aez.colors.text2)
                    androidx.compose.material3.Slider(hours, { hours = it }, valueRange = 1f..168f)
                }
                AezButton("Add", {
                    when (type) {
                        StickerType.POLL -> make(type) { it.copy(options = listOf(a, b)) }
                        StickerType.QUIZ -> {
                            val options = listOf(a, b).shuffled()
                            make(type) { it.copy(options = options, correct = options.indexOf(a)) }
                        }
                        StickerType.SLIDER -> make(type) { it.copy(emoji = "😍") }
                        StickerType.COUNTDOWN -> make(type) { it.copy(endsAt = System.currentTimeMillis() + (hours * 3_600_000).toLong()) }
                        StickerType.LINK -> make(type) { it.copy(url = text.trim().let { u -> if (u.startsWith("http")) u else "https://$u" }) }
                        StickerType.HASHTAG -> make(type) { it.copy(text = text.trim().removePrefix("#")) }
                        else -> make(type)
                    }
                }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() || type == StickerType.QUESTION)
            }
        }
    }
}
