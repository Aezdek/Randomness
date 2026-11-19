package xyz.aezdek.daybook.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.Task
import xyz.aezdek.daybook.domain.carriedOver
import xyz.aezdek.daybook.domain.formatMinutes
import xyz.aezdek.daybook.domain.long
import xyz.aezdek.daybook.domain.longName
import xyz.aezdek.daybook.domain.monthName
import xyz.aezdek.daybook.domain.on
import xyz.aezdek.daybook.domain.relativeName
import xyz.aezdek.daybook.domain.tally
import xyz.aezdek.daybook.sync.SyncStatus
import xyz.aezdek.daybook.ui.AppController
import xyz.aezdek.daybook.ui.components.AppleScreen
import xyz.aezdek.daybook.ui.components.AppleText
import xyz.aezdek.daybook.ui.components.Cell
import xyz.aezdek.daybook.ui.components.CellPosition
import xyz.aezdek.daybook.ui.components.GlassCapsuleButton
import xyz.aezdek.daybook.ui.components.IconButton
import xyz.aezdek.daybook.ui.components.SectionHeader
import xyz.aezdek.daybook.ui.components.SymbolIcon
import xyz.aezdek.daybook.ui.components.TextButton
import xyz.aezdek.daybook.ui.components.TintedButton
import xyz.aezdek.daybook.ui.components.readable
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.GlassVariant
import xyz.aezdek.daybook.ui.theme.Symbols
import xyz.aezdek.daybook.ui.theme.subject

@Composable
fun TodayScreen(
    controller: AppController,
    tasks: List<Task>,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    listState: LazyListState,
    chrome: PaddingValues,
    regular: Boolean,
    syncStatus: SyncStatus,
    pending: Int,
    onOpenSync: () -> Unit,
    onAdd: (Subject?) -> Unit,
    onOpen: (Task) -> Unit,
) {
    val colors = Apple.colors
    val day = remember(tasks, selected) { tasks.on(selected) }
    val carried = remember(tasks, selected, today) { if (selected == today) tasks.carriedOver(today) else emptyList() }
    val tally = day.tally()

    val title = selected.relativeName(today)
    // The title already names the weekday unless it says Today, Tomorrow or Yesterday.
    val dateText = if (title == selected.dayOfWeek.longName) "${selected.day} ${selected.monthName}" else selected.long()
    val summary = when {
        tally.total == 0 -> "Nothing planned"
        tally.done == tally.total -> if (tally.total == 1) "Done" else "All done"
        else -> "${tally.done} of ${tally.total} done"
    }
    val planned = formatMinutes(tally.minutesPlanned).takeIf { it.isNotEmpty() }?.let { " · $it planned" } ?: ""

    AppleScreen(
        title = title,
        subtitle = "$dateText · $summary$planned",
        listState = listState,
        chrome = chrome,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != today) GlassCapsuleButton("Today", onClick = { onSelect(today) })
                // In regular width the sidebar shows sync state.
                if (!regular) SyncPill(syncStatus, pending, onOpenSync)
                if (regular) {
                    GlassCapsuleButton("New Task", onClick = { onAdd(null) }, symbol = Symbols.plus, variant = GlassVariant.Prominent)
                }
            }
        },
    ) {
        item(key = "week") {
            WeekStrip(selected = selected, today = today, tasks = tasks, onSelect = onSelect, modifier = Modifier.padding(bottom = 4.dp))
        }

        if (carried.isNotEmpty()) {
            item(key = "carried-header") {
                SectionHeader(
                    "Carried Over",
                    Modifier.readable(),
                    leading = { SymbolIcon(Symbols.undo, null, tint = colors.secondaryLabel, size = 20.dp) },
                    trailing = {
                        TextButton("Move All Here", onClick = { carried.forEach { controller.move(it, today) } }, style = Apple.type.subheadSemibold)
                    },
                )
            }
            taskRows(carried, keyPrefix = "carried", carried = true, today = today, controller = controller, onOpen = onOpen)
        }

        if (day.isEmpty() && carried.isEmpty()) {
            item(key = "empty") { EmptyDay(selected, today, onAdd = { onAdd(null) }) }
        } else {
            for (subject in Subject.entries) {
                val subjectTasks = day.filter { it.subject == subject }
                val t = subjectTasks.tally()
                item(key = "header-${subject.id}") {
                    SectionHeader(
                        subject.title,
                        Modifier.readable(),
                        leading = { SymbolIcon(Symbols.subject(subject), null, tint = colors.subject(subject), size = 20.dp) },
                        trailing = {
                            if (t.total > 0) {
                                AppleText("${t.done} of ${t.total}", Apple.type.footnote, color = colors.secondaryLabel)
                                IconButton(Symbols.plus, "Add ${subject.title} task", onClick = { onAdd(subject) }, size = 18.dp)
                            }
                        },
                    )
                }
                if (subjectTasks.isEmpty()) {
                    item(key = "add-${subject.id}") {
                        Cell(CellPosition.Only, Modifier.readable(), onClick = { onAdd(subject) }) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SymbolIcon(Symbols.plus, null, tint = colors.accent, size = 20.dp)
                                AppleText("Add a ${subject.shortTitle} task", Apple.type.body, color = colors.accent)
                            }
                        }
                    }
                } else {
                    taskRows(subjectTasks, keyPrefix = subject.id, carried = false, today = today, controller = controller, onOpen = onOpen)
                }
            }
        }
    }
}

private fun LazyListScope.taskRows(
    tasks: List<Task>,
    keyPrefix: String,
    carried: Boolean,
    today: LocalDate,
    controller: AppController,
    onOpen: (Task) -> Unit,
) {
    itemsIndexed(tasks, key = { _, task -> "$keyPrefix-${task.id}" }) { index, task ->
        TaskRow(
            task = task,
            position = CellPosition.of(index, tasks.size),
            carried = carried,
            today = today,
            controller = controller,
            onOpen = onOpen,
            modifier = Modifier.animateItem(),
        )
    }
}

/** Symbol, one line saying what goes here, one action to add the first item. */
@Composable
private fun EmptyDay(date: LocalDate, today: LocalDate, onAdd: () -> Unit) {
    val colors = Apple.colors
    val name = if (date == today) "today" else date.relativeName(today).let { if (it == "Tomorrow") "tomorrow" else it }
    Column(
        Modifier.readable().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymbolIcon(Symbols.checklist, null, tint = colors.secondaryLabel, size = 52.dp)
        AppleText("Nothing planned for $name", Apple.type.title3Semibold, textAlign = TextAlign.Center)
        AppleText(
            "Add what you'll do in Computer Science, Maths and Physics.",
            Apple.type.subhead,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        TintedButton("Add Task", onClick = onAdd, symbol = Symbols.plus, modifier = Modifier.padding(top = 8.dp))
    }
}
