package xyz.aezdek.daybook.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.Task
import xyz.aezdek.daybook.data.TaskDraft
import xyz.aezdek.daybook.domain.formatMinutes
import xyz.aezdek.daybook.domain.plusDays
import xyz.aezdek.daybook.domain.relativeName
import xyz.aezdek.daybook.domain.short
import xyz.aezdek.daybook.ui.components.FieldRow
import xyz.aezdek.daybook.ui.components.GroupedSection
import xyz.aezdek.daybook.ui.components.InlineCalendar
import xyz.aezdek.daybook.ui.components.ListRow
import xyz.aezdek.daybook.ui.components.LocalOverlay
import xyz.aezdek.daybook.ui.components.MenuItem
import xyz.aezdek.daybook.ui.components.SegmentedControl
import xyz.aezdek.daybook.ui.components.SheetHeader
import xyz.aezdek.daybook.ui.components.SymbolIcon
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.Motion
import xyz.aezdek.daybook.ui.theme.Symbols

private val MINUTE_OPTIONS = listOf(0, 15, 30, 45, 60, 90, 120, 180)

private fun Subject.example(): String = when (this) {
    Subject.CS -> "Write a linked list class in Python"
    Subject.MATHS -> "10 topical questions on integration by parts"
    Subject.PHYSICS -> "Past paper 42, timed"
}

/**
 * Add or edit a task. The title takes focus straight away when adding, so typing can start at once.
 * Secondary choices (paper, time, day) are one row each and open a menu or an inline calendar.
 */
@Composable
fun ColumnScope.TaskEditor(
    existing: Task?,
    initialSubject: Subject,
    initialDate: LocalDate,
    today: LocalDate,
    onSave: (TaskDraft) -> Unit,
    onDelete: (() -> Unit)?,
    dismiss: () -> Unit,
) {
    val colors = Apple.colors
    val overlay = LocalOverlay.current
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var subject by remember { mutableStateOf(existing?.subject ?: initialSubject) }
    var paper by remember { mutableStateOf(existing?.paper ?: "") }
    var minutes by remember { mutableStateOf(existing?.minutes ?: 0) }
    var date by remember { mutableStateOf(existing?.date ?: initialDate) }
    var calendarOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var paperAnchor by remember { mutableStateOf(Offset.Zero) }
    var timeAnchor by remember { mutableStateOf(Offset.Zero) }

    val canSave = title.isNotBlank()
    fun save() {
        if (!canSave) return
        onSave(TaskDraft(title.trim(), subject, paper, minutes, date))
        dismiss()
    }

    LaunchedEffect(Unit) {
        if (existing == null) {
            delay(120)
            runCatching { focus.requestFocus() }
        }
    }

    SheetHeader(
        title = if (existing == null) "New Task" else "Edit Task",
        onCancel = dismiss,
        confirmLabel = if (existing == null) "Add" else "Done",
        confirmEnabled = canSave,
        onConfirm = ::save,
    )

    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        GroupedSection(
            background = colors.sheetCell,
            rows = listOf {
                FieldRow(
                    value = title,
                    // The field wraps long titles, so Return arrives as a newline: treat it as Add.
                    onValueChange = { value ->
                        if ('\n' in value) {
                            title = value.replace("\n", "").take(300)
                            save()
                        } else {
                            title = value.take(300)
                        }
                    },
                    placeholder = subject.example(),
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                    onImeAction = ::save,
                    focusRequester = focus,
                    singleLine = false,
                )
            },
        )

        SegmentedControl(
            options = Subject.entries.map { it.shortTitle },
            selected = Subject.entries.indexOf(subject),
            onSelect = { index ->
                val next = Subject.entries[index]
                if (next != subject) {
                    subject = next
                    if (next.paper(paper) == null) paper = ""
                }
            },
            label = "Subject",
            modifier = Modifier.padding(horizontal = Apple.margin).padding(top = 16.dp),
        )

        GroupedSection(
            modifier = Modifier.padding(top = 16.dp),
            background = colors.sheetCell,
            rows = listOf(
                {
                    ListRow(
                        title = "Paper",
                        icon = Symbols.doc,
                        value = subject.paper(paper)?.let { "${it.id} ${it.name}" } ?: "Any",
                        modifier = Modifier.onGloballyPositioned { paperAnchor = it.positionInRoot() + Offset(it.size.width * 0.55f, it.size.height.toFloat()) },
                        onClick = {
                            overlay.showMenu(
                                paperAnchor,
                                listOf(
                                    listOf(MenuItem("Any", if (paper.isEmpty()) Symbols.checkmark else null) { paper = "" }),
                                    subject.papers.map { p ->
                                        MenuItem("${p.id} ${p.name}", if (p.id == paper) Symbols.checkmark else null) { paper = p.id }
                                    },
                                ),
                            )
                        },
                        trailing = { SymbolIcon(Symbols.chevronUpDown, null, tint = colors.secondaryLabel, size = 16.dp) },
                    )
                },
                {
                    ListRow(
                        title = "Time",
                        icon = Symbols.clock,
                        value = formatMinutes(minutes).ifEmpty { "None" },
                        modifier = Modifier.onGloballyPositioned { timeAnchor = it.positionInRoot() + Offset(it.size.width * 0.55f, it.size.height.toFloat()) },
                        onClick = {
                            overlay.showMenu(
                                timeAnchor,
                                listOf(
                                    MINUTE_OPTIONS.map { m ->
                                        MenuItem(formatMinutes(m).ifEmpty { "None" }, if (m == minutes) Symbols.checkmark else null) { minutes = m }
                                    },
                                ),
                            )
                        },
                        trailing = { SymbolIcon(Symbols.chevronUpDown, null, tint = colors.secondaryLabel, size = 16.dp) },
                    )
                },
                {
                    Column {
                        ListRow(
                            title = "Day",
                            icon = Symbols.calendar,
                            value = if (date == today || date == today.plusDays(1)) date.relativeName(today) else date.short(),
                            onClick = { calendarOpen = !calendarOpen },
                            trailing = {
                                SymbolIcon(if (calendarOpen) Symbols.chevronDown else Symbols.chevronRight, null, tint = colors.secondaryLabel, size = 14.dp)
                            },
                        )
                        AnimatedVisibility(
                            visible = calendarOpen,
                            enter = if (Apple.reduceMotion) fadeIn() else expandVertically(Motion.standard(false)) + fadeIn(),
                            exit = if (Apple.reduceMotion) fadeOut() else shrinkVertically(Motion.standard(false)) + fadeOut(),
                        ) {
                            InlineCalendar(selected = date, today = today, onSelect = { date = it; calendarOpen = false })
                        }
                    }
                },
            ),
        )

        if (existing != null && onDelete != null) {
            GroupedSection(
                modifier = Modifier.padding(top = 16.dp),
                background = colors.sheetCell,
                rows = listOf(
                    {
                        val target = if (existing.date < today) today else existing.date.plusDays(1)
                        // Saves any other edits too, with the new day, and closes.
                        ListRow(
                            title = if (target == today) "Move to Today" else "Move to Tomorrow",
                            icon = Symbols.moveToTomorrow,
                            titleColor = if (canSave) colors.accent else colors.tertiaryLabel,
                            onClick = {
                                if (canSave) {
                                    onSave(TaskDraft(title.trim(), subject, paper, minutes, target))
                                    dismiss()
                                }
                            },
                        )
                    },
                    {
                        ListRow(
                            title = "Delete Task",
                            icon = Symbols.trash,
                            iconTint = colors.red,
                            titleColor = colors.red,
                            onClick = {
                                onDelete()
                                dismiss()
                            },
                        )
                    },
                ),
            )
        }
    }
}
