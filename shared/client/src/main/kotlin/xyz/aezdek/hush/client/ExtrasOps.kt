package xyz.aezdek.hush.client

import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.DailyAnswer
import xyz.aezdek.hush.protocol.ListChange
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.SharedFile
import xyz.aezdek.hush.protocol.SharedList
import xyz.aezdek.hush.protocol.ThreadDto
import java.io.InputStream
import java.io.OutputStream

// Sending and reading the smaller things a chat holds (see Extras.kt for their rules; web/src/core/more.ts is the same
// for the web app).

// ------------------------------------------------------------------------------------------------------------ files

/** Sends [size] bytes from [input] as a file called [name]: encrypted in pieces as it uploads, never held whole. */
suspend fun AezClient.sendFile(threadId: String, name: String?, input: InputStream, size: Long, onProgress: (Float) -> Unit = {}): Entity {
    if (size <= 0) throw UserFacingException("That file is empty")
    if (size > Files.MAX_BYTES) throw UserFacingException("Files can be up to ${Files.sizeLabel(Files.MAX_BYTES)}")
    val (blobId, key) = media.uploadEncrypted(input, size, onProgress)
    val clean = Files.safeName(name)
    return composer.sendMessage(
        threadId, MessagePayload(MessageType.FILE, text = clean, file = SharedFile(clean, blobId, B64.encode(key), size)), blobIds = listOf(blobId),
    )
}

/** Decrypts [file] into [out], a few megabytes at a time. */
suspend fun AezClient.saveFile(file: SharedFile, out: OutputStream, onProgress: (Float) -> Unit = {}) {
    if (!Files.plausible(file)) throw UserFacingException("That file can't be opened")
    val ranged = media.openRanged(MediaRef(file.blobId, file.key, "application/octet-stream", file.size))
    if (ranged.length > Files.MAX_BYTES) throw UserFacingException("That file can't be opened")
    var at = 0L
    while (at < ranged.length) {
        val piece = ranged.read(at, 4 * 1024 * 1024)
        if (piece.isEmpty()) break
        out.write(piece)
        at += piece.size
        onProgress(at.toFloat() / ranged.length)
    }
}

// ----------------------------------------------------------------------------------------------------------- nudges

suspend fun Composer.sendNudge(threadId: String): Entity = sendMessage(threadId, MessagePayload(MessageType.NUDGE))

// ------------------------------------------------------------------------------------------------------------ lists

suspend fun Composer.sendList(threadId: String, title: String, items: List<String>): Entity {
    val t = title.trim().take(Lists.MAX_TITLE)
    if (t.isEmpty()) throw UserFacingException("Give the list a name")
    val clean = items.map { it.trim().take(Lists.MAX_ITEM) }.filter { it.isNotEmpty() }.take(Lists.MAX_ITEMS)
    return sendMessage(threadId, MessagePayload(MessageType.LIST, text = t, list = SharedList(t, clean)))
}

suspend fun Composer.changeList(list: Entity, change: ListChange): Entity? {
    val clean = if (change.add != null) ListChange(add = change.add!!.trim().take(Lists.MAX_ITEM).ifEmpty { return null }) else change
    return sendMessage(list.dto.threadId ?: return null, MessagePayload(MessageType.LIST_CHANGE, replyTo = list.id, listChange = clean))
}

/** A list message with everyone's changes applied. */
fun Index.listState(list: Entity): ListState {
    val edits = messages[list.dto.threadId].orEmpty().mapNotNull { m ->
        val p = m.payloadAs<MessagePayload>() ?: return@mapNotNull null
        if (p.type != MessageType.LIST_CHANGE || p.replyTo != list.id) return@mapNotNull null
        p.listChange?.let { ListEdit(m.id, m.dto.authorId, it) }
    }
    return Lists.fold(list.payloadAs<MessagePayload>()?.list ?: SharedList(""), list.dto.authorId, edits)
}

// ------------------------------------------------------------------------------------------------- the day's question

fun Index.daily(thread: ThreadDto, day: String = Daily.day()): Daily.State {
    val answers = messages[thread.id].orEmpty().mapNotNull { m ->
        val p = m.payloadAs<MessagePayload>() ?: return@mapNotNull null
        if (p.type != MessageType.DAILY) return@mapNotNull null
        p.daily?.takeIf { Daily.isDay(it.day) }?.let { m.dto.authorId to it }
    }
    return Daily.state(thread.id, day, thread.members.map { it.userId }, answers)
}

suspend fun Composer.answerDaily(index: Index, thread: ThreadDto, me: String, answer: String): Entity {
    val day = Daily.day()
    val state = index.daily(thread, day)
    val text = answer.trim().take(Daily.MAX_ANSWER)
    if (text.isEmpty()) throw UserFacingException("Write an answer")
    if (me in state.answers) throw UserFacingException("You already answered today")
    return sendMessage(thread.id, MessagePayload(MessageType.DAILY, daily = DailyAnswer(day, state.question, text)))
}

// ------------------------------------------------------------------------------------------- countdowns and arrivals

suspend fun Composer.setCountdown(threadId: String, title: String, at: Long): Entity =
    sendChatEvent(threadId, ChatEvent(countdownTitle = Countdowns.title(title), countdownAt = at))

suspend fun Composer.clearCountdown(threadId: String): Entity = sendChatEvent(threadId, ChatEvent(countdownTitle = "", countdownAt = 0))

suspend fun Composer.sendArrived(threadId: String, place: String): Entity = sendMessage(threadId, MessagePayload(MessageType.ARRIVED, text = Places.name(place)))

/** The line a nudge or an arrival is in the conversation. */
fun lineOf(payload: MessagePayload, mine: Boolean, name: String): String = when (payload.type) {
    MessageType.ARRIVED -> "${if (mine) "You" else name} arrived at ${Places.name(payload.text)}"
    else -> if (mine) "You sent a nudge 👋" else "$name nudged you 👋"
}

/** Messages drawn as a line in the middle of the chat (or, for list changes, not at all) rather than as a bubble. */
val LINE_TYPES = setOf(MessageType.SYSTEM, MessageType.NUDGE, MessageType.ARRIVED, MessageType.LIST_CHANGE)
