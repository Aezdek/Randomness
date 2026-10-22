package xyz.aezdek.salah.ui.qada

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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.aezdek.salah.core.QadaLine
import xyz.aezdek.salah.core.QadaSummary
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.core.qada
import xyz.aezdek.salah.core.withMadeUp
import xyz.aezdek.salah.core.withOwed
import xyz.aezdek.salah.core.withOwedAdded
import xyz.aezdek.salah.ui.ScreenTitle
import xyz.aezdek.salah.ui.approxDuration
import xyz.aezdek.salah.ui.formatPace
import xyz.aezdek.salah.ui.grouped
import xyz.aezdek.salah.ui.plural
import java.time.LocalDate
import kotlin.math.roundToInt

/** What's left to make up for each prayer, with a button to tick one off. */
@Composable
fun QadaScreen(
    data: SalahData,
    today: LocalDate,
    onEdit: ((SalahData) -> SalahData) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val qada = remember(data, today) { data.qada(today) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Qada", "Missed prayers to make up")
        if (qada.owed == 0) {
            NothingOwed(onAdd = { adding = true })
        } else {
            Overview(qada)
            qada.lines.forEach { line ->
                QadaCard(
                    line = line,
                    onMadeUp = { onEdit { it.withMadeUp(today, line.prayer, 1) } },
                    onUndo = { onEdit { it.withMadeUp(today, line.prayer, -1) } },
                )
            }
            FilledTonalButton(
                onClick = {
                    onEdit { current ->
                        current.qada(today).lines.filter { it.remaining > 0 }.fold(current) { acc, line -> acc.withMadeUp(today, line.prayer, 1) }
                    }
                },
                enabled = qada.remaining > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.DoneAll, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Made up one of each")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f).testTag("add-missed")) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add missed")
                }
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit counts")
                }
            }
            Text(
                "Left to make up = old missed prayers you added + prayers marked missed in your log − prayers made up. " +
                    "Marking a missed day as prayed takes it off again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (adding) {
        AddMissedSheet(
            today = today,
            onDismiss = { adding = false },
            onAdd = { counts ->
                adding = false
                onEdit { it.withOwedAdded(counts) }
                scope.launch { snackbar.showSnackbar("Added ${plural(counts.values.sum(), "missed prayer")}") }
            },
        )
    }
    if (editing) {
        EditOwedDialog(
            owed = data.owed,
            onDismiss = { editing = false },
            onSave = { counts ->
                editing = false
                onEdit { it.withOwed(counts) }
            },
        )
    }
}

@Composable
private fun NothingOwed(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text("Nothing to make up", style = MaterialTheme.typography.titleLarge)
            Text(
                "Prayers you mark as missed on the Today tab show up here. If you missed prayers before you started " +
                    "logging, add them, then tick them off as you make them up.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onAdd, modifier = Modifier.testTag("add-missed")) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add old missed prayers")
            }
        }
    }
}

@Composable
private fun Overview(qada: QadaSummary) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(qada.remaining.grouped(), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("remaining-total"))
            Text(
                if (qada.remaining == 0) "All made up. Alhamdulillah!" else if (qada.remaining == 1) "prayer left to make up" else "prayers left to make up",
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = { qada.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            Text(
                "${qada.madeUp.grouped()} of ${qada.owed.grouped()} made up (${(qada.progress * 100).roundToInt()}%)",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (qada.madeUpToday > 0) {
                Text("Today: ${plural(qada.madeUpToday, "prayer")} made up", style = MaterialTheme.typography.bodyMedium)
            }
            qada.daysLeft?.let { days ->
                Text(
                    "At about ${formatPace(qada.pacePerDay)} a day, you'll be done in about ${approxDuration(days)}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun QadaCard(line: QadaLine, onMadeUp: () -> Unit, onUndo: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(line.prayer.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(line.prayer.arabic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        line.remaining.grouped(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (line.remaining > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("remaining-${line.prayer.name}"),
                    )
                    Text(" left", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(breakdown(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onUndo, enabled = line.madeUpToday > 0, modifier = Modifier.testTag("undo-${line.prayer.name}")) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo one made up today")
            }
            FilledTonalButton(onClick = onMadeUp, enabled = line.remaining > 0, modifier = Modifier.testTag("madeup-${line.prayer.name}")) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Made up")
            }
        }
    }
}

private fun breakdown(line: QadaLine): String = buildList {
    if (line.added > 0) add("${line.added.grouped()} added")
    if (line.missedInLog > 0) add("${line.missedInLog.grouped()} from log")
    if (line.madeUp > 0) add("${line.madeUp.grouped()} made up" + if (line.madeUpToday > 0) " (${line.madeUpToday} today)" else "")
}.joinToString(" · ").ifEmpty { "Nothing owed" }
