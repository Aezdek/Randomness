package xyz.aezdek.daybook.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.data.Task
import xyz.aezdek.daybook.domain.formatMinutes
import xyz.aezdek.daybook.domain.plusDays
import xyz.aezdek.daybook.domain.short
import xyz.aezdek.daybook.ui.AppController
import xyz.aezdek.daybook.ui.components.AppleText
import xyz.aezdek.daybook.ui.components.CellPosition
import xyz.aezdek.daybook.ui.components.CheckCircle
import xyz.aezdek.daybook.ui.components.LocalOverlay
import xyz.aezdek.daybook.ui.components.MenuItem
import xyz.aezdek.daybook.ui.components.SwipeAction
import xyz.aezdek.daybook.ui.components.SwipeActionsBox
import xyz.aezdek.daybook.ui.components.bottomSeparator
import xyz.aezdek.daybook.ui.components.readable
import xyz.aezdek.daybook.ui.components.shape
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.Symbols
import xyz.aezdek.daybook.ui.theme.subject

/** "Paper 3 · 45 min", or for a carried-over task "Maths · from Tue 17 Nov". */
fun Task.detail(carried: Boolean): String = listOfNotNull(
    if (carried) subject.shortTitle else null,
    paper.takeIf { it.isNotBlank() }?.let { "Paper ${it.removePrefix("P")}" },
    formatMinutes(minutes).takeIf { it.isNotEmpty() },
    if (carried) "from ${date.short()}" else null,
).joinToString(" · ")

/**
 * One task in an inset grouped section. Tap the circle to tick it, tap the row to edit it, swipe for
 * Done, Tomorrow and Delete, or long-press (right-click on desktop) for the same actions in a menu.
 */
@Composable
fun TaskRow(
    task: Task,
    position: CellPosition,
    carried: Boolean,
    today: LocalDate,
    controller: AppController,
    onOpen: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Apple.colors
    val overlay = LocalOverlay.current
    val haptics = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var lastPress by remember { mutableStateOf(Offset.Zero) }
    val moveTarget = if (carried || task.date < today) today else task.date.plusDays(1)
    val moveLabel = if (moveTarget == today) "Today" else "Tomorrow"

    fun menu(at: Offset) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        overlay.showMenu(
            origin + at,
            listOf(
                listOf(
                    MenuItem(if (task.done) "Mark as Not Done" else "Mark as Done", if (task.done) Symbols.undo else Symbols.checkmark) {
                        controller.setDone(task, !task.done)
                    },
                    MenuItem("Move to $moveLabel", Symbols.moveToTomorrow) { controller.move(task, moveTarget) },
                    MenuItem("Edit…", Symbols.pencil) { onOpen(task) },
                ),
                listOf(MenuItem("Delete", Symbols.trash, destructive = true) { controller.delete(task) }),
            ),
        )
    }

    Box(modifier.readable().padding(horizontal = Apple.margin).clip(position.shape())) {
        SwipeActionsBox(
            leading = SwipeAction(
                if (task.done) "Not Done" else "Done",
                if (task.done) Symbols.undo else Symbols.checkmark,
                colors.greenFill,
            ) { controller.setDone(task, !task.done) },
            trailing = listOf(
                SwipeAction(moveLabel, Symbols.moveToTomorrow, colors.orangeFill) { controller.move(task, moveTarget) },
                SwipeAction("Delete", Symbols.trash, colors.redFill) { controller.delete(task) },
            ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.cell)
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(Unit) {
                        // Remember where presses land so a long-press menu opens there; open it on right-click.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.type == PointerEventType.Press) {
                                    lastPress = change.position
                                    if (event.buttons.isSecondaryPressed) {
                                        event.changes.forEach { it.consume() }
                                        menu(change.position)
                                    }
                                }
                            }
                        }
                    }
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = "Edit",
                        onLongClickLabel = "Show actions",
                        onLongClick = { menu(lastPress) },
                        onClick = { onOpen(task) },
                    )
                    .bottomSeparator(!position.isLast, colors.separator, 60.dp)
                    .heightIn(min = 52.dp)
                    .padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CheckCircle(
                    checked = task.done,
                    color = colors.subject(task.subject),
                    label = task.title,
                    onToggle = { controller.setDone(task, it) },
                )
                Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    AppleText(task.title, Apple.type.body, color = if (task.done) colors.secondaryLabel else colors.label)
                    val detail = task.detail(carried)
                    if (detail.isNotEmpty()) {
                        AppleText(detail, Apple.type.subhead, Modifier.padding(top = 2.dp), color = colors.secondaryLabel)
                    }
                }
            }
        }
    }
}
