package xyz.aezdek.hush.ui.messages

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.ChatCustom
import xyz.aezdek.hush.client.Countdowns
import xyz.aezdek.hush.client.Daily
import xyz.aezdek.hush.client.Display
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Files
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.client.Lists
import xyz.aezdek.hush.client.answerDaily
import xyz.aezdek.hush.client.changeList
import xyz.aezdek.hush.client.clearCountdown
import xyz.aezdek.hush.client.daily
import xyz.aezdek.hush.client.listState
import xyz.aezdek.hush.client.saveFile
import xyz.aezdek.hush.client.sendFile
import xyz.aezdek.hush.client.sendList
import xyz.aezdek.hush.client.setCountdown
import xyz.aezdek.hush.protocol.DailyAnswer
import xyz.aezdek.hush.protocol.ListChange
import xyz.aezdek.hush.protocol.SharedFile
import xyz.aezdek.hush.protocol.ThreadDto
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.theme.Aez
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

// The smaller things a chat holds: a file, a shared list, the day's question and a countdown. Their rules are in the
// shared client's Extras.kt; the web app's features/messages/More.tsx is the same thing there.

private val CARD = RoundedCornerShape(20.dp)

@Composable
private fun Modifier.card(): Modifier = widthIn(max = 300.dp).clip(CARD).background(Aez.colors.bubbleOther).border(1.dp, Aez.colors.border, CARD)

// ------------------------------------------------------------------------------------------------------------ files

/** What the phone calls the file behind [uri], and how big it is. */
private fun describe(context: Context, uri: Uri): Pair<String?, Long> {
    var name: String? = null
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
    }
    return name to size
}

/** Sends the file the person picked. [onProgress] gets a label while it goes, and null when it is done. */
internal suspend fun sendPickedFile(context: Context, threadId: String, uri: Uri, onProgress: (String?) -> Unit) {
    val (name, size) = describe(context, uri)
    val clean = Files.safeName(name)
    onProgress("Sending $clean…")
    Graph.attempt {
        if (size <= 0) throw xyz.aezdek.hush.client.UserFacingException("That file can't be read")
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: throw xyz.aezdek.hush.client.UserFacingException("That file can't be read")
            input.use { Graph.client.sendFile(threadId, name, it, size) { p -> onProgress("Sending $clean… ${(p * 100).toInt()}%") } }
        }
    }
    onProgress(null)
}

/** Decrypts [file] into the phone's Downloads folder under its cleaned name. Hush never opens it itself. */
private suspend fun saveToDownloads(context: Context, file: SharedFile) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, Files.safeName(file.name))
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw xyz.aezdek.hush.client.UserFacingException("Couldn't save to Downloads")
    try {
        (resolver.openOutputStream(uri) ?: throw xyz.aezdek.hush.client.UserFacingException("Couldn't save to Downloads")).use { Graph.client.saveFile(file, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (e: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
}

@Composable
internal fun FileCard(file: SharedFile?, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    if (!Files.plausible(file)) {
        Text("A file that couldn't be shown", fontSize = 13.sp, color = Aez.colors.text3, modifier = modifier.padding(8.dp))
        return
    }
    val name = Files.safeName(file!!.name)
    Row(
        modifier.card().clickable(enabled = !busy) {
            busy = true
            scope.launch {
                if (Graph.attempt { saveToDownloads(context, file) } != null) Graph.toast("Saved to Downloads")
                busy = false
            }
        }.semantics { contentDescription = "Download $name" }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Aez.colors.elev2), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.InsertDriveFile, null, tint = Aez.colors.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Aez.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (busy) "Decrypting…" else Files.sizeLabel(file.size), fontSize = 12.sp, color = Aez.colors.text2)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Download, null, tint = Aez.colors.text2, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------------------------------------------------ lists

@Composable
internal fun ListCard(message: Entity, index: Index, modifier: Modifier) {
    val state = remember(index, message.id) { index.listState(message) }
    var adding by remember { mutableStateOf("") }
    fun change(change: ListChange) = Graph.launch { Graph.client.composer.changeList(message, change) }
    Column(modifier.card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Aez.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (state.items.isEmpty()) "Empty" else "${state.items.count { !it.done }} left", fontSize = 12.sp, color = Aez.colors.text2)
        }
        for (item in state.items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val box = RoundedCornerShape(6.dp)
                Box(
                    Modifier.size(22.dp).clip(box).then(if (item.done) Modifier.background(Aez.colors.text) else Modifier.border(1.5.dp, Aez.colors.text3, box))
                        .clickable { change(if (item.done) ListChange(undone = item.id) else ListChange(done = item.id)) }
                        .semantics { contentDescription = (if (item.done) "Untick " else "Tick ") + item.text },
                    contentAlignment = Alignment.Center,
                ) { if (item.done) Icon(Icons.Outlined.Check, null, tint = Aez.colors.bg, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(
                    item.text, fontSize = 14.sp, color = if (item.done) Aez.colors.text3 else Aez.colors.text,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.Close, "Remove ${item.text}", tint = Aez.colors.text3,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable { change(ListChange(remove = item.id)) }.padding(6.dp),
                )
            }
        }
        if (state.items.size < Lists.MAX_ITEMS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) { AezField(adding, { adding = it.take(Lists.MAX_ITEM) }, "Add an item") }
                AezButton("Add", {
                    change(ListChange(add = adding))
                    adding = ""
                }, small = true, enabled = adding.isNotBlank())
            }
        }
    }
}

@Composable
internal fun ListSheet(threadId: String, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var items by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Shared list", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
            AezField(title, { title = it.take(Lists.MAX_TITLE) }, "Name (Groceries, Films to watch…)")
            AezField(items, { items = it }, "First items, one per line (optional)", singleLine = false, minLines = 3)
            AezButton("Send list", {
                Graph.launch { Graph.client.composer.sendList(threadId, title, items.lines()) }
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = title.isNotBlank())
            Text("Everyone in the chat can add items and tick them off.", fontSize = 12.sp, color = Aez.colors.text3)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------- the day's question

/** An answer in the conversation: shown once everyone has answered that day, or once the day is over. */
@Composable
internal fun DailyCard(message: Entity, answer: DailyAnswer, thread: ThreadDto, index: Index, me: String, modifier: Modifier) {
    if (!Daily.isDay(answer.day)) {
        Text("An answer that couldn't be shown", fontSize = 13.sp, color = Aez.colors.text3, modifier = modifier.padding(8.dp))
        return
    }
    val state = remember(index, answer.day) { index.daily(thread, answer.day) }
    val shown = message.dto.authorId == me || state.revealed || answer.day < Daily.day()
    Column(modifier.card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.QuestionAnswer, null, tint = Aez.colors.text2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Question of the day", fontSize = 12.sp, color = Aez.colors.text2)
        }
        Text(state.question, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Aez.colors.text)
        if (shown) Text(Display.text(answer.answer, Daily.MAX_ANSWER), fontSize = 14.sp, color = Aez.colors.text)
        else Text("Answered. You'll see it once everyone has answered.", fontSize = 14.sp, color = Aez.colors.text3)
    }
}

@Composable
internal fun DailySheet(thread: ThreadDto, index: Index, state: AppState, onDismiss: () -> Unit) {
    val me = state.me?.id ?: return
    val daily = remember(index, thread.id) { index.daily(thread) }
    var answer by remember { mutableStateOf("") }
    val mine = daily.answers[me]
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Question of the day", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
            Text(daily.question, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Aez.colors.text)
            when {
                mine == null -> {
                    AezField(answer, { answer = it.take(Daily.MAX_ANSWER) }, "Your answer", singleLine = false, minLines = 2)
                    AezButton("Answer", {
                        val text = answer
                        answer = ""
                        Graph.launch { Graph.client.composer.answerDaily(Graph.client.index.value, thread, me, text) }
                    }, Modifier.fillMaxWidth(), enabled = answer.isNotBlank())
                }
                daily.revealed -> for (member in thread.members) {
                    Column {
                        Text(if (member.userId == me) "You" else index.nameIn(thread.id, member.userId, state.users), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Aez.colors.text2)
                        Text(daily.answers[member.userId].orEmpty(), fontSize = 15.sp, color = Aez.colors.text)
                    }
                }
                else -> {
                    val waiting = thread.members.filter { it.userId !in daily.answers }.joinToString(", ") { index.nameIn(thread.id, it.userId, state.users) }
                    Text("You answered. Waiting for $waiting.", fontSize = 14.sp, color = Aez.colors.text2)
                }
            }
            Text("A new question every day. Answers stay out of sight until everyone in the chat has answered.", fontSize = 12.sp, color = Aez.colors.text3)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------------- countdowns

/** "12 days until Trip", under the chat's title, when the chat has a countdown. */
@Composable
internal fun CountdownBar(custom: ChatCustom) {
    val label = Countdowns.label(custom.countdownTitle, custom.countdownAt) ?: return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Schedule, null, tint = Aez.colors.text2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.5.sp, color = Aez.colors.text2)
    }
}

@Composable
internal fun CountdownSheet(threadId: String, custom: ChatCustom, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Countdown", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
            AezField(title, { title = it.take(Countdowns.MAX_TITLE) }, "What for? (Trip, Together since…)")
            AezButton(
                date?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())) } ?: "Pick a date",
                { picking = true }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY, icon = Icons.Outlined.CalendarMonth,
            )
            AezButton("Set countdown", {
                val at = date!!.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                Graph.launch { Graph.client.composer.setCountdown(threadId, title, at) }
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && date != null)
            if (custom.countdownTitle.isNotEmpty()) {
                AezButton("Remove “${custom.countdownTitle}”", {
                    Graph.launch { Graph.client.composer.clearCountdown(threadId) }
                    onDismiss()
                }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
            }
            Text("Shows at the top of the chat for everyone: days to go, or days since a date in the past.", fontSize = 12.sp, color = Aez.colors.text3)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (picking) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

// ------------------------------------------------------------------------------------------------------- send later

/** A message that arrives at a time you pick: the server keeps it sealed until then. Also lists what is waiting. */
@Composable
internal fun LaterSheet(threadId: String, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    var time by remember { mutableStateOf(java.time.LocalTime.now(zone).plusHours(1).withSecond(0).withNano(0)) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(emptyList<xyz.aezdek.hush.protocol.ScheduledDto>()) }
    suspend fun refresh() {
        waiting = runCatching { Graph.client.composer.scheduled().filter { it.threadId == threadId } }.getOrDefault(waiting)
    }
    androidx.compose.runtime.LaunchedEffect(threadId) { refresh() }
    val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send later", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
            AezField(text, { text = it.take(xyz.aezdek.hush.client.MAX_LATER_TEXT) }, "Message", singleLine = false, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AezButton(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at)), { pickingDate = true }, Modifier.weight(1f), style = ButtonStyle.SECONDARY, icon = Icons.Outlined.CalendarMonth)
                AezButton(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)), { pickingTime = true }, Modifier.weight(1f), style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Schedule)
            }
            AezButton("Send then", {
                val words = text.trim()
                scope.launch {
                    Graph.attempt {
                        val kept = Graph.client.composer.sendLater(threadId, words, at)
                        Graph.prefs.setLaterText(kept.id, words)
                        text = ""
                        Graph.toast("It will arrive then")
                    }
                    refresh()
                }
            }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
            for (message in waiting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Graph.prefs.laterText(message.id) ?: "A message", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Aez.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            message.failed?.let { "Not sent: $it" } ?: DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(message.releaseAt)),
                            fontSize = 12.sp, color = if (message.failed != null) Aez.colors.danger else Aez.colors.text2,
                        )
                    }
                    AezButton(if (message.failed != null) "Clear" else "Cancel", {
                        scope.launch {
                            Graph.attempt { Graph.client.composer.cancelScheduled(message.id) }
                            Graph.prefs.setLaterText(message.id, null)
                            refresh()
                        }
                    }, style = ButtonStyle.SECONDARY, small = true)
                }
            }
            Text("Your server keeps it sealed and delivers it at that time, even if this phone is off. Words only, up to 60 days ahead.", fontSize = 12.sp, color = Aez.colors.text3)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton({
                    picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
    if (pickingTime) {
        val picker = androidx.compose.material3.rememberTimePickerState(time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context))
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton({
                    time = java.time.LocalTime.of(picker.hour, picker.minute)
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickingTime = false }) { Text("Cancel") } },
            text = { androidx.compose.material3.TimePicker(picker) },
        )
    }
}
