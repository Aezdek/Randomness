package xyz.aezdek.daybook.ui.progress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.Task
import xyz.aezdek.daybook.domain.HeatCell
import xyz.aezdek.daybook.domain.daysUntil
import xyz.aezdek.daybook.domain.formatMinutes
import xyz.aezdek.daybook.domain.heatmap
import xyz.aezdek.daybook.domain.initial
import xyz.aezdek.daybook.domain.long
import xyz.aezdek.daybook.domain.plusDays
import xyz.aezdek.daybook.domain.short
import xyz.aezdek.daybook.domain.streak
import xyz.aezdek.daybook.domain.weekStart
import xyz.aezdek.daybook.domain.weekSummary
import xyz.aezdek.daybook.domain.withYear
import xyz.aezdek.daybook.ui.AppController
import xyz.aezdek.daybook.ui.components.AppleScreen
import xyz.aezdek.daybook.ui.components.AppleText
import xyz.aezdek.daybook.ui.components.CapsuleProgress
import xyz.aezdek.daybook.ui.components.GroupedSection
import xyz.aezdek.daybook.ui.components.InlineCalendar
import xyz.aezdek.daybook.ui.components.ListRow
import xyz.aezdek.daybook.ui.components.SymbolIcon
import xyz.aezdek.daybook.ui.components.readable
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.ContinuousShape
import xyz.aezdek.daybook.ui.theme.Motion
import xyz.aezdek.daybook.ui.theme.Radii
import xyz.aezdek.daybook.ui.theme.Symbols
import xyz.aezdek.daybook.ui.theme.subject

@Composable
fun ProgressScreen(
    controller: AppController,
    tasks: List<Task>,
    examDate: LocalDate?,
    today: LocalDate,
    week: LocalDate,
    listState: LazyListState,
    chrome: PaddingValues,
) {
    val colors = Apple.colors
    val summary = remember(tasks, week) { tasks.weekSummary(week) }
    val streak = remember(tasks, today) { tasks.streak(today) }
    val cells = remember(tasks, today) { tasks.heatmap(today, weeks = 12) }
    val weekLabel = "${summary.start.short()} – ${summary.start.plusDays(6).short()}"
    val isThisWeek = week.weekStart() == today.weekStart()

    AppleScreen(title = "Progress", subtitle = if (isThisWeek) "This week, $weekLabel" else weekLabel, listState = listState, chrome = chrome) {
        item(key = "week") {
            val overall = summary.overall
            GroupedSection(
                modifier = Modifier.readable(),
                header = if (isThisWeek) "This Week" else "That Week",
                footer = if (overall.total == 0) "No tasks set for these seven days yet."
                else "${overall.done} of ${overall.total} tasks ticked" +
                    (formatMinutes(overall.minutesDone).takeIf { it.isNotEmpty() }?.let { ", $it studied." } ?: "."),
                rows = Subject.entries.map { subject ->
                    {
                        val t = summary.bySubject.getValue(subject)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SymbolIcon(Symbols.subject(subject), null, tint = colors.subject(subject), size = 22.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppleText(subject.title, Apple.type.body, Modifier.weight(1f))
                                    AppleText(if (t.total == 0) "None set" else "${t.done} of ${t.total}", Apple.type.subhead, color = colors.secondaryLabel)
                                }
                                CapsuleProgress(t.fraction, colors.subject(subject), Modifier.semantics {
                                    contentDescription = "${subject.title}: ${t.done} of ${t.total} done"
                                })
                                if (t.minutesPlanned > 0) {
                                    AppleText(
                                        "${formatMinutes(t.minutesDone).ifEmpty { "0 min" }} of ${formatMinutes(t.minutesPlanned)}",
                                        Apple.type.footnote,
                                        color = colors.secondaryLabel,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }

        item(key = "streak") {
            GroupedSection(
                modifier = Modifier.readable(),
                header = "Streak",
                footer = "Days in a row on which every planned task was ticked. Days with nothing planned don't count, and don't break it.",
                rows = listOf(
                    {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            SymbolIcon(Symbols.flame, null, tint = colors.physics, size = 36.dp)
                            Column {
                                AppleText(streak.toString(), Apple.type.numeral)
                                AppleText(if (streak == 1) "day in a row" else "days in a row", Apple.type.subhead, color = colors.secondaryLabel)
                            }
                        }
                    },
                    { Heatmap(cells) },
                ),
            )
        }

        item(key = "exam") { ExamSection(examDate, today, controller) }
    }
}

@Composable
private fun Heatmap(cells: List<HeatCell>) {
    val colors = Apple.colors
    val total = cells.sumOf { it.done }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Last 12 weeks: $total tasks ticked" },
    ) {
        AppleText("Last 12 Weeks", Apple.type.subheadSemibold, Modifier.padding(bottom = 10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.take(7).forEachIndexed { index, cell ->
                    Box(Modifier.size(width = 14.dp, height = 18.dp), contentAlignment = Alignment.Center) {
                        if (index % 2 == 0) AppleText(cell.date.dayOfWeek.initial, Apple.type.caption2, color = colors.secondaryLabel)
                    }
                }
            }
            cells.chunked(7).forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { cell ->
                        val shape = ContinuousShape(Radii.heatCell + 2.dp)
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(shape)
                                .then(
                                    when {
                                        cell.isFuture -> Modifier.border(1.dp, colors.separator, shape)
                                        cell.isToday -> Modifier.background(colors.heat[cell.level]).border(2.dp, colors.label, shape)
                                        else -> Modifier.background(colors.heat[cell.level])
                                    },
                                ),
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppleText("Fewer", Apple.type.caption1, color = colors.secondaryLabel)
            colors.heat.forEach { Box(Modifier.size(12.dp).clip(ContinuousShape(3.dp)).background(it)) }
            AppleText("More ticked", Apple.type.caption1, color = colors.secondaryLabel)
        }
    }
}

@Composable
private fun ExamSection(examDate: LocalDate?, today: LocalDate, controller: AppController) {
    val colors = Apple.colors
    var picking by remember { mutableStateOf(false) }
    val rows = buildList<@Composable () -> Unit> {
        if (examDate != null) {
            add {
                val days = daysUntil(today, examDate)
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SymbolIcon(Symbols.graduationCap, null, tint = colors.accent, size = 36.dp)
                    Column {
                        when {
                            days > 0 -> {
                                AppleText(days.toString(), Apple.type.numeral)
                                val weeks = (days + 3) / 7
                                AppleText(
                                    (if (days == 1) "day to go" else "days to go") + if (weeks >= 2) ", about $weeks weeks" else "",
                                    Apple.type.subhead,
                                    color = colors.secondaryLabel,
                                )
                            }
                            days == 0 -> AppleText("Your first paper is today. Good luck.", Apple.type.title3Semibold)
                            else -> AppleText("That date has passed. Set your next paper.", Apple.type.body, color = colors.secondaryLabel)
                        }
                    }
                }
            }
        }
        add {
            Column {
                ListRow(
                    title = "First Paper",
                    icon = Symbols.calendar,
                    value = examDate?.withYear() ?: "Not Set",
                    onClick = { picking = !picking },
                    trailing = { SymbolIcon(if (picking) Symbols.chevronDown else Symbols.chevronRight, null, tint = colors.secondaryLabel, size = 14.dp) },
                )
                AnimatedVisibility(
                    visible = picking,
                    enter = if (Apple.reduceMotion) fadeIn() else expandVertically(Motion.standard(false)) + fadeIn(),
                    exit = if (Apple.reduceMotion) fadeOut() else shrinkVertically(Motion.standard(false)) + fadeOut(),
                ) {
                    InlineCalendar(selected = examDate, today = today, onSelect = {
                        controller.setExamDate(it)
                        picking = false
                    })
                }
            }
        }
        if (examDate != null) {
            add { ListRow("Clear Date", titleColor = colors.red, onClick = { controller.setExamDate(null) }) }
        }
    }
    GroupedSection(
        modifier = Modifier.readable(),
        header = "Exams",
        footer = if (examDate == null) "Set the date of your first paper to count down to it on every device." else "Countdown to ${examDate.long()}.",
        rows = rows,
    )
}
