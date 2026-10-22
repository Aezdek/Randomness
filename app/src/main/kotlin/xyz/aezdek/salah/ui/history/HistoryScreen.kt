package xyz.aezdek.salah.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.aezdek.salah.core.PeriodSummary
import xyz.aezdek.salah.core.Prayer
import xyz.aezdek.salah.core.PrayerStatus
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.core.day
import xyz.aezdek.salah.core.statusOf
import xyz.aezdek.salah.core.streak
import xyz.aezdek.salah.core.summary
import xyz.aezdek.salah.ui.MonthName
import xyz.aezdek.salah.ui.MonthYear
import xyz.aezdek.salah.ui.ScreenTitle
import xyz.aezdek.salah.ui.grouped
import xyz.aezdek.salah.ui.theme.statusColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/** A month calendar with a dot per prayer, the month's totals, and the current streak. Tap a day to open it. */
@Composable
fun HistoryScreen(data: SalahData, today: LocalDate, onOpenDay: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val thisMonth = YearMonth.from(today)
    var month by rememberSaveable { mutableStateOf(thisMonth) }
    val summary = remember(data, month, today) { data.summary(month.atDay(1), minOf(month.atEndOfMonth(), today)) }
    val monthName = month.format(MonthName)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("History")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(
                label = "Prayed",
                value = summary.prayedShare?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                caption = "in $monthName",
                modifier = Modifier.weight(1f),
            )
            Stat(
                label = "Missed",
                value = summary.missed.grouped(),
                caption = "in $monthName",
                valueColor = if (summary.missed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            val streak = data.streak(today)
            Stat(
                label = "Streak",
                value = streak.grouped(),
                caption = if (streak == 1) "full day" else "full days",
                modifier = Modifier.weight(1f),
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(
                        month.format(MonthYear),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < thisMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }
                Calendar(data, month, today, onOpenDay)
                Legend(Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
        }
        PrayerBreakdown(summary, monthName)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, caption: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = valueColor)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Calendar(data: SalahData, month: YearMonth, today: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    val locale = Locale.getDefault()
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    Row(Modifier.fillMaxWidth()) {
        repeat(7) {
            Text(
                firstDayOfWeek.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val leadingBlanks = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells: List<LocalDate?> = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { date ->
                if (date == null) Spacer(Modifier.weight(1f)) else DayCell(data, date, today, onOpenDay, Modifier.weight(1f))
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DayCell(data: SalahData, date: LocalDate, today: LocalDate, onOpenDay: (LocalDate) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val future = date > today
    val day = data.day(date)
    val background = when {
        day.allPrayed -> colors.primaryContainer
        day.missed > 0 -> colors.errorContainer
        else -> Color.Transparent
    }
    val shape = MaterialTheme.shapes.small
    Column(
        modifier
            .padding(2.dp)
            .clip(shape)
            .background(background)
            .then(if (date == today) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .clickable(enabled = !future, onClickLabel = "Open day") { onOpenDay(date) }
            .padding(vertical = 6.dp)
            .testTag("day-$date"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                future -> colors.onSurface.copy(alpha = 0.38f)
                day.allPrayed -> colors.onPrimaryContainer
                day.missed > 0 -> colors.onErrorContainer
                else -> colors.onSurface
            },
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Prayer.entries.forEach { prayer ->
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (future) Color.Transparent else statusColor(data.statusOf(date, prayer))),
                )
            }
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        listOf(PrayerStatus.PRAYED to "Prayed", PrayerStatus.MISSED to "Missed", null to "Not logged").forEach { (status, label) ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(status)),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun PrayerBreakdown(summary: PeriodSummary, monthName: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Each prayer in $monthName", style = MaterialTheme.typography.titleMedium)
            summary.perPrayer.forEach { tally ->
                val logged = tally.prayed + tally.missed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tally.prayer.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(76.dp))
                    LinearProgressIndicator(
                        progress = { if (logged == 0) 0f else tally.prayed / logged.toFloat() },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = if (tally.missed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp),
                    )
                    Text(
                        if (tally.missed > 0) "${tally.missed} missed" else "${tally.prayed} prayed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tally.missed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(76.dp),
                    )
                }
            }
        }
    }
}
