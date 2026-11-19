package xyz.aezdek.daybook.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import xyz.aezdek.daybook.data.Task
import xyz.aezdek.daybook.domain.initial
import xyz.aezdek.daybook.domain.long
import xyz.aezdek.daybook.domain.monthAndYear
import xyz.aezdek.daybook.domain.on
import xyz.aezdek.daybook.domain.plusDays
import xyz.aezdek.daybook.domain.tally
import xyz.aezdek.daybook.domain.weekStart
import xyz.aezdek.daybook.ui.components.AppleText
import xyz.aezdek.daybook.ui.components.IconButton
import xyz.aezdek.daybook.ui.components.readable
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.Symbols

private const val PAGES = 2001
private const val CENTER = PAGES / 2

/**
 * A week of days, swiped sideways a week at a time like Calendar's week row, with arrow buttons as the
 * alternative to the swipe. Each day shows a ring that fills as its tasks are ticked.
 */
@Composable
fun WeekStrip(
    selected: LocalDate,
    today: LocalDate,
    tasks: List<Task>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = remember(today) { today.weekStart() }
    fun pageOf(date: LocalDate) = CENTER + base.daysUntil(date.weekStart()) / 7
    fun weekOf(page: Int) = base.plusDays((page - CENTER) * 7)

    val pager = rememberPagerState(initialPage = pageOf(selected)) { PAGES }
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Selection moved elsewhere (Today button, keyboard): bring its week into view.
    LaunchedEffect(selected, base) {
        val target = pageOf(selected)
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }
    // Swiped to another week: keep the same weekday selected there.
    LaunchedEffect(pager, base) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val start = weekOf(page)
            if (currentSelected.weekStart() != start) {
                currentOnSelect(start.plusDays(currentSelected.dayOfWeek.isoDayNumber - 1))
            }
        }
    }

    Column(modifier.readable()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Apple.margin + 4.dp, end = Apple.margin - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppleText(selected.monthAndYear(), Apple.type.subheadSemibold, Modifier.weight(1f), color = Apple.colors.secondaryLabel)
            IconButton(Symbols.chevronLeft, "Previous week", onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, size = 18.dp)
            IconButton(Symbols.chevronRight, "Next week", onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, size = 18.dp)
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), key = { it }) { page ->
            val start = weekOf(page)
            Row(Modifier.fillMaxWidth().padding(horizontal = Apple.margin - 4.dp)) {
                for (i in 0..6) {
                    val date = start.plusDays(i)
                    DayCell(
                        date = date,
                        isSelected = date == selected,
                        isToday = date == today,
                        tasks = tasks.on(date),
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    tasks: List<Task>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Apple.colors
    val haptics = LocalHapticFeedback.current
    val tally = tasks.tally()
    val description = buildString {
        append(date.long())
        if (isToday) append(", today")
        append(if (tally.total == 0) ", nothing planned" else ", ${tally.done} of ${tally.total} done")
    }
    Column(
        modifier
            .selectable(
                selected = isSelected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = {
                    if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppleText(
            date.dayOfWeek.initial,
            Apple.type.caption1Semibold,
            color = if (isToday) colors.accent else colors.secondaryLabel,
        )
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            val track = colors.tertiaryFill
            val progress = colors.greenFill
            Canvas(Modifier.size(44.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                if (tally.total > 0) {
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    if (tally.done > 0) {
                        drawArc(progress, -90f, 360f * tally.fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
            }
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accentFill else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                AppleText(
                    date.day.toString(),
                    if (isSelected || isToday) Apple.type.bodySemibold else Apple.type.body,
                    color = when {
                        isSelected -> colors.onAccent
                        isToday -> colors.accent
                        else -> colors.label
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
