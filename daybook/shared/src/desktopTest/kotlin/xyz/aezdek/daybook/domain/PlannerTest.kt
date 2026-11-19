package xyz.aezdek.daybook.domain

import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {

    private val thursday = LocalDate(2026, 11, 19)
    private var nextId = 0

    private fun task(date: LocalDate, done: Boolean = false, subject: Subject = Subject.MATHS, minutes: Int = 30) = Task(
        id = "t${nextId++}", title = "Task", subject = subject, paper = "", minutes = minutes,
        date = date, done = done, createdAt = nextId.toLong(),
    )

    @Test
    fun weeksStartOnMonday() {
        assertEquals(LocalDate(2026, 11, 16), thursday.weekStart())
        assertEquals(LocalDate(2026, 11, 16), LocalDate(2026, 11, 16).weekStart())
        assertEquals(LocalDate(2026, 11, 16), LocalDate(2026, 11, 22).weekStart(), "Sunday belongs to the week before")
    }

    @Test
    fun unfinishedPastTasksCarryOverOldestFirst() {
        val older = task(thursday.plusDays(-3))
        val newer = task(thursday.plusDays(-1))
        val finished = task(thursday.plusDays(-1), done = true)
        val todays = task(thursday)
        val carried = listOf(newer, finished, todays, older).carriedOver(thursday)
        assertEquals(listOf(older, newer), carried)
    }

    @Test
    fun weekSummaryCountsOnlyThatWeek() {
        val tasks = listOf(
            task(thursday, done = true, subject = Subject.CS, minutes = 60),
            task(thursday, subject = Subject.CS, minutes = 30),
            task(thursday.plusDays(3), done = true, subject = Subject.PHYSICS, minutes = 90),
            task(thursday.plusDays(4), done = true), // next Monday: another week
        )
        val week = tasks.weekSummary(thursday)
        assertEquals(Tally(done = 1, total = 2, minutesDone = 60, minutesPlanned = 90), week.bySubject[Subject.CS])
        assertEquals(Tally(1, 1, 90, 90), week.bySubject[Subject.PHYSICS])
        assertEquals(Tally.Empty, week.bySubject[Subject.MATHS])
        assertEquals(3, week.overall.total, "next week's task is left out")
    }

    @Test
    fun streakSkipsEmptyDaysAndIgnoresAnUnfinishedToday() {
        val tasks = listOf(
            task(thursday), // today, not yet done: neither counts nor breaks
            task(thursday.plusDays(-1), done = true),
            // nothing on the 17th: skipped
            task(thursday.plusDays(-3), done = true),
            task(thursday.plusDays(-3), done = true),
            task(thursday.plusDays(-4)), // unfinished: the streak stops here
            task(thursday.plusDays(-5), done = true),
        )
        assertEquals(2, tasks.streak(thursday))
    }

    @Test
    fun aFinishedTodayCounts() {
        val tasks = listOf(task(thursday, done = true), task(thursday.plusDays(-1), done = true))
        assertEquals(2, tasks.streak(thursday))
        assertEquals(0, emptyList<Task>().streak(thursday))
    }

    @Test
    fun heatmapCoversWholeWeeksEndingThisWeek() {
        val cells = listOf(task(thursday, done = true), task(thursday, done = true), task(thursday.plusDays(-8), done = true))
            .heatmap(thursday, weeks = 12)
        assertEquals(84, cells.size)
        assertEquals(LocalDate(2026, 8, 31), cells.first().date, "a Monday, eleven weeks before this one")
        assertEquals(LocalDate(2026, 11, 22), cells.last().date)
        val today = cells.single { it.isToday }
        assertEquals(2, today.done)
        assertEquals(1, today.level)
        assertTrue(cells.filter { it.date > thursday }.all { it.isFuture })
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("45 min", formatMinutes(45))
        assertEquals("2 hr", formatMinutes(120))
        assertEquals("1 hr 30 min", formatMinutes(90))
        assertEquals("2h 15m", formatMinutesShort(135))
    }

    @Test
    fun relativeDayNames() {
        assertEquals("Today", thursday.relativeName(thursday))
        assertEquals("Tomorrow", thursday.plusDays(1).relativeName(thursday))
        assertEquals("Monday", thursday.plusDays(4).relativeName(thursday))
        assertEquals("Thu 19 Nov", thursday.short())
    }
}
