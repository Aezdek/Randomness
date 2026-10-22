package xyz.aezdek.salah.ui.log

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import xyz.aezdek.salah.core.DaySummary
import xyz.aezdek.salah.core.Hijri
import xyz.aezdek.salah.core.Prayer
import xyz.aezdek.salah.core.PrayerStatus
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.core.day
import xyz.aezdek.salah.core.statusOf
import xyz.aezdek.salah.core.streak
import xyz.aezdek.salah.core.withRestPrayed
import xyz.aezdek.salah.core.withStatus
import xyz.aezdek.salah.ui.LongDate
import xyz.aezdek.salah.ui.StatusStrip
import xyz.aezdek.salah.ui.dayTitle
import xyz.aezdek.salah.ui.plural
import java.time.DayOfWeek
import java.time.LocalDate

/** One day's five prayers, each ticked as prayed or missed. */
@Composable
fun LogScreen(
    data: SalahData,
    date: LocalDate,
    today: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    onEdit: ((SalahData) -> SalahData) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val summary = data.day(date)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateHeader(
            date = date,
            today = today,
            hijriOffset = data.settings.hijriOffset,
            onPrevious = { onDateChange(date.minusDays(1)) },
            onNext = { onDateChange(date.plusDays(1)) },
            onPick = { picking = true },
        )
        if (date != today) {
            AssistChip(
                onClick = { onDateChange(today) },
                label = { Text("Back to today") },
                leadingIcon = { Icon(Icons.Filled.Today, contentDescription = null, Modifier.size(18.dp)) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        DayProgress(
            statuses = Prayer.entries.map { data.statusOf(date, it) },
            summary = summary,
            streak = if (date == today) data.streak(today) else 0,
        )
        Prayer.entries.forEach { prayer ->
            PrayerCard(
                prayer = prayer,
                status = data.statusOf(date, prayer),
                jumuah = prayer == Prayer.DHUHR && date.dayOfWeek == DayOfWeek.FRIDAY,
                onStatus = { status -> onEdit { it.withStatus(date, prayer, status) } },
            )
        }
        if (summary.logged < Prayer.entries.size) {
            FilledTonalButton(
                onClick = { onEdit { it.withRestPrayed(date) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rest-prayed"),
            ) {
                Icon(Icons.Filled.DoneAll, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (summary.logged == 0) "Mark all prayed" else "Mark the rest prayed")
            }
        }
        if (summary.missed > 0) {
            Text(
                "Missed prayers are added to your Qada list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (picking) {
        DayPicker(
            date = date,
            today = today,
            onPick = {
                picking = false
                onDateChange(it)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun DateHeader(
    date: LocalDate,
    today: LocalDate,
    hijriOffset: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("previous-day")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        Column(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = "Pick a day", onClick = onPick)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(dayTitle(date, today), style = MaterialTheme.typography.headlineMedium)
            Text(date.format(LongDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Hijri.format(date, hijriOffset)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        IconButton(onClick = onNext, enabled = date < today, modifier = Modifier.testTag("next-day")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@Composable
private fun DayProgress(statuses: List<PrayerStatus?>, summary: DaySummary, streak: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${summary.prayed}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    " of ${Prayer.entries.size} prayed",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                if (summary.missed > 0) {
                    Text(
                        "${summary.missed} missed",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            StatusStrip(statuses, Modifier.fillMaxWidth())
            if (streak > 0) {
                Text(
                    "${plural(streak, "day")} in a row with all five prayed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrayerCard(prayer: Prayer, status: PrayerStatus?, jumuah: Boolean, onStatus: (PrayerStatus?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when (status) {
            PrayerStatus.PRAYED -> colors.primaryContainer
            PrayerStatus.MISSED -> colors.errorContainer
            null -> colors.surfaceContainerHigh
        },
        label = "prayer card",
    )
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(prayer.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (jumuah) "Jumu'ah · ${prayer.arabic}" else prayer.arabic,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Tapping the chosen side again clears it.
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = status == PrayerStatus.PRAYED,
                    onClick = { onStatus(if (status == PrayerStatus.PRAYED) null else PrayerStatus.PRAYED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = colors.primary, activeContentColor = colors.onPrimary),
                    icon = {
                        if (status == PrayerStatus.PRAYED) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                    },
                    modifier = Modifier.testTag("prayed-${prayer.name}"),
                ) { Text("Prayed") }
                SegmentedButton(
                    selected = status == PrayerStatus.MISSED,
                    onClick = { onStatus(if (status == PrayerStatus.MISSED) null else PrayerStatus.MISSED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = colors.error, activeContentColor = colors.onError),
                    icon = {
                        if (status == PrayerStatus.MISSED) Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(18.dp))
                    },
                    modifier = Modifier.testTag("missed-${prayer.name}"),
                ) { Text("Missed") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val latest = today.toEpochDay() * MILLIS_PER_DAY
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.toEpochDay() * MILLIS_PER_DAY,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis
                if (picked == null) onDismiss() else onPick(LocalDate.ofEpochDay(Math.floorDiv(picked, MILLIS_PER_DAY)))
            }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
