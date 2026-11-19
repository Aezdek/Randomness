package xyz.aezdek.daybook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import xyz.aezdek.daybook.domain.initial
import xyz.aezdek.daybook.domain.long
import xyz.aezdek.daybook.domain.monthAndYear
import xyz.aezdek.daybook.domain.plusDays
import xyz.aezdek.daybook.domain.weekStart
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.Symbols

/** The graphical date picker: one month, Monday first, with month arrows. Shown inline, below its row. */
@Composable
fun InlineCalendar(
    selected: LocalDate?,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Apple.colors
    val haptics = LocalHapticFeedback.current
    var month by remember { mutableStateOf(LocalDate((selected ?: today).year, (selected ?: today).month, 1)) }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppleText(month.monthAndYear(), Apple.type.headline, Modifier.weight(1f).padding(start = 4.dp))
            IconButton(Symbols.chevronLeft, "Previous month", onClick = { month = month.plus(-1, DateTimeUnit.MONTH) })
            IconButton(Symbols.chevronRight, "Next month", onClick = { month = month.plus(1, DateTimeUnit.MONTH) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            DayOfWeek.entries.forEach { dow ->
                AppleText(
                    dow.initial,
                    Apple.type.caption1Semibold,
                    Modifier.weight(1f),
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }
        val first = month.weekStart()
        val weeks = ((month.dayOfWeek.isoDayNumber - 1) + daysInMonth(month) + 6) / 7
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0..6) {
                    val date = first.plusDays(week * 7 + d)
                    val inMonth = date.month == month.month
                    val isSelected = date == selected
                    val isToday = date == today
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .sizeIn(minHeight = 40.dp)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) colors.accentFill else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(
                                selected = isSelected,
                                enabled = inMonth,
                                role = Role.Button,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(date)
                                },
                            )
                            .semantics { contentDescription = date.long() + if (isToday) ", today" else "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (inMonth) {
                            AppleText(
                                date.day.toString(),
                                if (isToday || isSelected) Apple.type.bodySemibold else Apple.type.body,
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
        }
    }
}

private fun daysInMonth(first: LocalDate): Int {
    val next = first.plus(1, DateTimeUnit.MONTH)
    var count = 0
    var d = first
    while (d < next) {
        count++
        d = d.plusDays(1)
    }
    return count
}
