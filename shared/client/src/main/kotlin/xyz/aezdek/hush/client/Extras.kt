package xyz.aezdek.hush.client

import xyz.aezdek.hush.protocol.DailyAnswer
import xyz.aezdek.hush.protocol.ListChange
import xyz.aezdek.hush.protocol.SharedFile
import xyz.aezdek.hush.protocol.SharedList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// The smaller things a chat can hold: files, shared lists, the day's question, a countdown, arrivals. All of it
// arrives from other people's apps, so everything here cuts what it is given down to what an honest app would send.
// Mirrored in web/src/core/extras.ts; the tests on both sides use the same cases.

/** Files sent in a chat. */
object Files {
    /** The largest file either app sends: it is decrypted in one piece in a browser. */
    const val MAX_BYTES = 250L * 1024 * 1024
    const val MAX_NAME = 120
    private val BLOB_ID = Regex("^[0-9a-f]{64}$")

    /**
     * A file's name as it may be shown and saved: no folders, no control characters, and none of the invisible marks
     * that can make "photo\u202Egpj.exe" read as "photoexe.jpg". Never empty, never only dots, at most [MAX_NAME]
     * characters with the ending kept.
     */
    fun safeName(name: String?): String {
        var clean = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || Character.getType(it).toByte() in setOf(Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR) }
            .filterNot { it in "<>:\"|?*" }
            .trim().trim('.').trim()
        if (clean.isEmpty()) return "file"
        if (clean.length > MAX_NAME) {
            val ending = clean.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
            clean = if (ending == null) clean.take(MAX_NAME) else clean.take(MAX_NAME - ending.length - 1).trimEnd('.', ' ') + "." + ending
        }
        return clean
    }

    /** Whether [file] could be a real file to fetch. */
    fun plausible(file: SharedFile?): Boolean = file != null && BLOB_ID.matches(file.blobId) && file.size in 0..MAX_BYTES && file.key.length in 1..200

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

data class ListItem(val id: String, val text: String, val done: Boolean, val by: String?)
data class ListState(val title: String, val items: List<ListItem>)

/** One change to a list, with the id of the message that carried it and who sent it. */
data class ListEdit(val id: String, val by: String, val change: ListChange)

/** A list a chat keeps together: the list as it was sent, with everyone's changes applied in order. */
object Lists {
    const val MAX_ITEMS = 100
    const val MAX_ITEM = 200
    const val MAX_TITLE = 80

    private fun item(text: String?): String = Display.text(text?.replace('\n', ' '), MAX_ITEM).trim()

    fun title(list: SharedList): String = item(list.title).take(MAX_TITLE).ifEmpty { "List" }

    fun fold(list: SharedList, author: String, edits: List<ListEdit>): ListState {
        val items = LinkedHashMap<String, ListItem>()
        list.items.take(MAX_ITEMS).forEachIndexed { i, text -> item(text).takeIf { it.isNotEmpty() }?.let { items[i.toString()] = ListItem(i.toString(), it, false, author) } }
        for (edit in edits) {
            val c = edit.change
            c.add?.let { text -> item(text).takeIf { it.isNotEmpty() && items.size < MAX_ITEMS && edit.id !in items }?.let { items[edit.id] = ListItem(edit.id, it, false, edit.by) } }
            c.done?.let { id -> items[id]?.let { items[id] = it.copy(done = true, by = edit.by) } }
            c.undone?.let { id -> items[id]?.let { items[id] = it.copy(done = false, by = edit.by) } }
            c.remove?.let { id -> items.remove(id) }
        }
        return ListState(title(list), items.values.toList())
    }
}

/** The day's question: one a day per chat, the same for everyone in it, answers hidden until all have answered. */
object Daily {
    const val MAX_ANSWER = 500
    const val MAX_QUESTION = 200

    val QUESTIONS = listOf(
        "What made you smile today?",
        "What is a small thing you are looking forward to?",
        "What song has been stuck in your head lately?",
        "What is a meal you could eat every week?",
        "Where would you go if you could leave tomorrow?",
        "What is something you changed your mind about?",
        "What is your favourite memory of us?",
        "What did you want to be when you were ten?",
        "What is a film you can watch again and again?",
        "What is one thing you would like to learn this year?",
        "What does a perfect lazy day look like?",
        "What is the best gift you ever got?",
        "What smell takes you straight back somewhere?",
        "What are you proud of that you rarely mention?",
        "What is something you find beautiful that others overlook?",
        "Which season suits you best, and why?",
        "What is the kindest thing someone did for you lately?",
        "What would you do with a free afternoon and no phone?",
        "What is a habit you would like to pick up?",
        "What is a place that feels like home that isn't home?",
        "What was the last thing that made you laugh out loud?",
        "What food do you miss from when you were little?",
        "What is a question you wish people asked you more?",
        "What would you name a boat?",
        "What is your idea of a good morning?",
        "What book or show changed how you see things?",
        "What is something you are getting better at?",
        "Who do you admire, and what for?",
        "What is a tradition you would like us to start?",
        "What is one thing on your list for this month?",
        "What is the best advice you were ever given?",
        "What sound do you find calming?",
        "What would you cook to impress someone?",
        "What is a tiny luxury you love?",
        "What is something you kept from childhood?",
        "What do you wish you had more time for?",
        "What is the best thing about this week so far?",
        "If today had a title, what would it be?",
        "What is a word you love the sound of?",
        "What would you like to do together soon?",
    )

    /** Today's date where this device is ("2026-11-15"). */
    fun day(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    fun isDay(day: String?): Boolean = day != null && DAY.matches(day)

    /** The question for [threadId] on [day]: the same on every device, whatever its platform. */
    fun question(threadId: String, day: String): String {
        var h = 0
        for (c in threadId + day) h = (h * 31 + c.code) and 0x7fffffff
        return QUESTIONS[h % QUESTIONS.size]
    }

    data class State(val question: String, val answers: Map<String, String>, val revealed: Boolean)

    /**
     * The day's question for a chat and who has answered it. [answers] are the chat's daily answers, oldest first,
     * with their authors: each person's first answer of the day counts, and the first answer's question is the
     * question (so two apps with different lists still talk about the same one).
     */
    fun state(threadId: String, day: String, members: Collection<String>, answers: List<Pair<String, DailyAnswer>>): State {
        val today = answers.filter { it.second.day == day && it.first in members }
        val byUser = LinkedHashMap<String, String>()
        for ((author, answer) in today) {
            val text = Display.text(answer.answer, MAX_ANSWER).trim()
            if (author !in byUser && text.isNotEmpty()) byUser[author] = text
        }
        val asked = today.firstOrNull()?.second?.question?.replace('\n', ' ')?.trim()?.take(MAX_QUESTION)?.takeIf { it.isNotEmpty() }
        return State(asked ?: question(threadId, day), byUser, members.isNotEmpty() && members.all { it in byUser })
    }
}

/** A date a chat counts down to or up from, as the line at the top of the chat. */
object Countdowns {
    const val MAX_TITLE = 40

    fun title(raw: String?): String = raw?.replace('\n', ' ')?.trim()?.take(MAX_TITLE).orEmpty()

    /** "12 days until Trip", "Trip is tomorrow", "Trip is today", "412 days since Trip"; null if there is none. */
    fun label(title: String, at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
        if (title.isEmpty() || !Display.plausibleTime(at)) return null
        val days = ChronoUnit.DAYS.between(LocalDate.ofInstant(Instant.ofEpochMilli(now), zone), LocalDate.ofInstant(Instant.ofEpochMilli(at), zone))
        return when {
            days == 0L -> "$title is today"
            days == 1L -> "$title is tomorrow"
            days > 1 -> "$days days until $title"
            days == -1L -> "1 day since $title"
            else -> "${-days} days since $title"
        }
    }
}

/** Places someone can say they have arrived at. */
object Places {
    /** How near counts as there, in metres. */
    const val ARRIVED_WITHIN = 150.0
    const val MAX_NAME = 40

    fun name(raw: String?): String = raw?.replace('\n', ' ')?.trim()?.take(MAX_NAME).orEmpty().ifEmpty { "their destination" }

    /** Metres between two points on Earth (haversine). */
    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
