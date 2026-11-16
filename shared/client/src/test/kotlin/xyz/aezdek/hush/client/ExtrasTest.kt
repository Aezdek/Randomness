package xyz.aezdek.hush.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.DailyAnswer
import xyz.aezdek.hush.protocol.ListChange
import xyz.aezdek.hush.protocol.SharedFile
import xyz.aezdek.hush.protocol.SharedList
import java.time.ZoneId

/** The same cases as web/src/core/extras.test.ts. */
class ExtrasTest {
    @Test
    fun `file names are cleaned of folders, control and direction marks`() {
        assertEquals("notes.pdf", Files.safeName("notes.pdf"))
        assertEquals("passwd", Files.safeName("../../etc/passwd"))
        assertEquals("evil.exe", Files.safeName("C:\\Users\\me\\evil.exe"))
        assertEquals("photogpj.exe", Files.safeName("photo\u202Egpj.exe"))
        assertEquals("ab.txt", Files.safeName("a\u0000b\n.txt"))
        assertEquals("file", Files.safeName("  ..  "))
        assertEquals("file", Files.safeName(null))
        assertEquals("ab.txt", Files.safeName("a<b>.txt"))
        val long = Files.safeName("x".repeat(500) + ".tar")
        assertEquals(Files.MAX_NAME, long.length)
        assertTrue(long.endsWith(".tar"))
    }

    @Test
    fun `only real-looking files are fetched`() {
        val ok = SharedFile("a.txt", "ab".repeat(32), "key", 10)
        assertTrue(Files.plausible(ok))
        assertFalse(Files.plausible(ok.copy(blobId = "../../etc/passwd")))
        assertFalse(Files.plausible(ok.copy(size = -1)))
        assertFalse(Files.plausible(ok.copy(size = Files.MAX_BYTES + 1)))
        assertFalse(Files.plausible(null))
        assertEquals("1.5 MB", Files.sizeLabel(1_572_864))
        assertEquals("12 KB", Files.sizeLabel(12_800))
        assertTrue(Files.risky("game.APK") && Files.risky("page.html") && Files.risky("a.tar.sh"))
        assertFalse(Files.risky("photo.jpg") || Files.risky("notes") || Files.risky("apk"))
    }

    @Test
    fun `a list is what was sent with everyone's changes applied in order`() {
        val list = SharedList("Groceries", listOf("Milk", "Eggs", "  "))
        val state = Lists.fold(list, "ana", listOf(
            ListEdit("m1", "bo", ListChange(add = "Bread")),
            ListEdit("m2", "bo", ListChange(done = "0")),
            ListEdit("m3", "ana", ListChange(remove = "1")),
            ListEdit("m4", "ana", ListChange(done = "m1")),
            ListEdit("m5", "bo", ListChange(undone = "m1")),
            ListEdit("m6", "bo", ListChange(done = "no-such-item")),
        ))
        assertEquals("Groceries", state.title)
        assertEquals(listOf("0" to true, "m1" to false), state.items.map { it.id to it.done })
        assertEquals(listOf("Milk", "Bread"), state.items.map { it.text })
    }

    @Test
    fun `a list can't be flooded`() {
        val list = SharedList("x".repeat(5_000), List(1_000) { "item $it" })
        val edits = List(500) { ListEdit("e$it", "bo", ListChange(add = "y".repeat(10_000))) }
        val state = Lists.fold(list, "ana", edits)
        assertEquals(Lists.MAX_ITEMS, state.items.size)
        assertEquals(Lists.MAX_TITLE, state.title.length)
        val one = Lists.fold(SharedList("L"), "ana", listOf(ListEdit("e", "bo", ListChange(add = "y".repeat(10_000)))))
        assertTrue(one.items.single().text.length <= Lists.MAX_ITEM + 1)
    }

    @Test
    fun `the day's question is the same on every device`() {
        // Fixed values: the web app's test expects the same ones.
        assertEquals(Daily.QUESTIONS[28], Daily.question("thread-1", "2026-11-15"))
        assertEquals(Daily.QUESTIONS[29], Daily.question("thread-1", "2026-11-16"))
        assertEquals(40, Daily.QUESTIONS.size)
        assertEquals("2026-11-15", Daily.day(1_794_700_800_000, ZoneId.of("UTC")))
    }

    @Test
    fun `answers stay hidden until everyone in the chat has answered`() {
        val day = "2026-11-15"
        val members = listOf("ana", "bo")
        val one = Daily.state("t", day, members, listOf("ana" to DailyAnswer(day, "Asked?", "Tea")))
        assertFalse(one.revealed)
        assertEquals("Asked?", one.question)
        val both = Daily.state("t", day, members, listOf(
            "ana" to DailyAnswer(day, "Asked?", "Tea"),
            "ana" to DailyAnswer(day, "Asked?", "Changed my mind"),
            "bo" to DailyAnswer(day, "Another question", "Coffee"),
            "mallory" to DailyAnswer(day, "x", "not in this chat"),
            "bo" to DailyAnswer("2026-11-14", "x", "yesterday"),
        ))
        assertTrue(both.revealed)
        assertEquals(mapOf("ana" to "Tea", "bo" to "Coffee"), both.answers)
        // Nobody answered yet: the question comes from the list; blank answers don't count.
        val none = Daily.state("t", day, members, listOf("ana" to DailyAnswer(day, "Asked?", "   ")))
        assertFalse(none.revealed)
        assertTrue(none.answers.isEmpty())
        assertTrue(Daily.isDay(day))
        assertFalse(Daily.isDay("tomorrow"))
    }

    @Test
    fun `countdowns count calendar days, both ways`() {
        val utc = ZoneId.of("UTC")
        val now = 1_794_700_800_000 + 10 * 3_600_000L // 2026-11-15 10:00 UTC
        val day = 86_400_000L
        assertEquals("Trip is today", Countdowns.label("Trip", now + 3 * 3_600_000L, now, utc))
        assertEquals("Trip is tomorrow", Countdowns.label("Trip", now + day, now, utc))
        assertEquals("12 days until Trip", Countdowns.label("Trip", now + 12 * day, now, utc))
        assertEquals("1 day since Trip", Countdowns.label("Trip", now - day, now, utc))
        assertEquals("412 days since Us", Countdowns.label("Us", now - 412 * day, now, utc))
        assertNull(Countdowns.label("", now, now, utc))
        assertNull(Countdowns.label("Trip", Long.MAX_VALUE, now, utc))
    }

    @Test
    fun `a chat's countdown is set, capped and cleared by events`() {
        var custom = ChatCustom().apply(ChatEvent(countdownTitle = "  " + "T".repeat(300), countdownAt = 1_800_000_000_000))
        assertEquals(Countdowns.MAX_TITLE, custom.countdownTitle.length)
        assertEquals(1_800_000_000_000, custom.countdownAt)
        custom = custom.apply(ChatEvent(countdownTitle = "Trip", countdownAt = Long.MAX_VALUE))
        assertEquals("", custom.countdownTitle)
        custom = custom.apply(ChatEvent(countdownTitle = "Trip", countdownAt = 5)).apply(ChatEvent(countdownTitle = ""))
        assertEquals("", custom.countdownTitle)
        assertEquals(0, custom.countdownAt)
    }

    @Test
    fun `distance between two places`() {
        // Roughly 111 km per degree of latitude.
        assertEquals(111_195.0, Places.distance(0.0, 0.0, 1.0, 0.0), 100.0)
        assertTrue(Places.distance(52.52, 13.405, 52.5205, 13.4055) < Places.ARRIVED_WITHIN)
        assertEquals("their destination", Places.name("  "))
    }

    @Test
    fun `what a member with a modified app sends is refused or cut down, never trusted`() {
        // The same payloads CI sends the Android app on the emulator (see Hostile): here, what each turns into.
        val decoded = Hostile.extreme(System.currentTimeMillis()).mapNotNull {
            Payloads.decodeOrNull(xyz.aezdek.hush.protocol.Kind.MESSAGE, it) as? xyz.aezdek.hush.protocol.MessagePayload
        }
        val files = decoded.filter { it.type == "file" }
        assertTrue(files.isNotEmpty())
        // None of the hostile files would be fetched, and the names that would be shown are short and plain.
        assertTrue(files.none { Files.plausible(it.file) })
        assertTrue(files.all { Files.safeName(it.file?.name).let { n -> n.length <= Files.MAX_NAME && '/' !in n && '\u202E' !in n } })
        for (list in decoded.mapNotNull { it.list }) {
            val state = Lists.fold(list, "mallory", decoded.mapNotNull { it.listChange }.mapIndexed { i, c -> ListEdit("e$i", "mallory", c) })
            assertTrue(state.items.size <= Lists.MAX_ITEMS && state.title.length <= Lists.MAX_TITLE && state.items.all { it.text.length <= Lists.MAX_ITEM + 1 })
        }
        val answers = decoded.mapNotNull { it.daily }.map { "mallory" to it }
        val today = Daily.state("t", "2026-11-15", listOf("ana", "mallory"), answers)
        assertFalse(today.revealed)
        assertTrue(today.question.length <= Daily.MAX_QUESTION)
        var custom = ChatCustom()
        for (event in decoded.mapNotNull { it.event }) custom = custom.apply(event)
        assertTrue(custom.countdownTitle.length <= Countdowns.MAX_TITLE)
        assertTrue(Countdowns.label(custom.countdownTitle, custom.countdownAt) == null || Display.plausibleTime(custom.countdownAt))
        assertTrue(decoded.filter { it.type == "arrived" }.all { lineOf(it, false, "mallory").length < 80 })
    }
}
