package xyz.aezdek.salah.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.ui.history.HistoryScreen
import xyz.aezdek.salah.ui.log.LogScreen
import xyz.aezdek.salah.ui.qada.QadaScreen
import xyz.aezdek.salah.ui.settings.SettingsScreen
import java.time.LocalDate

enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Filled.Today),
    HISTORY("History", Icons.Filled.CalendarMonth),
    QADA("Qada", Icons.Filled.Restore),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * The whole app: a bottom bar with the day's log, the calendar, qada and settings.
 *
 * Every change goes through [onEdit] as a transform of [SalahData], so the screens are plain functions of the data.
 */
@Composable
fun SalahRoot(
    data: SalahData,
    today: LocalDate,
    onEdit: ((SalahData) -> SalahData) -> Unit,
    backupText: suspend () -> String,
    restore: suspend (SalahData) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    // The day shown on the Today tab, as an epoch day; null follows today, also across midnight.
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val shownDate = pickedDay?.let(LocalDate::ofEpochDay)?.takeIf { it < today } ?: today
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = tab != Tab.TODAY || shownDate != today) {
        if (tab != Tab.TODAY) tab = Tab.TODAY else pickedDay = null
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            // Tapping Today again jumps back to today.
                            if (item == Tab.TODAY && tab == Tab.TODAY) pickedDay = null
                            tab = item
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        modifier = Modifier.testTag("tab-${item.name}"),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (tab) {
            Tab.TODAY -> LogScreen(
                data = data,
                date = shownDate,
                today = today,
                onDateChange = { pickedDay = if (it >= today) null else it.toEpochDay() },
                onEdit = onEdit,
                modifier = modifier,
            )
            Tab.HISTORY -> HistoryScreen(
                data = data,
                today = today,
                onOpenDay = {
                    pickedDay = if (it >= today) null else it.toEpochDay()
                    tab = Tab.TODAY
                },
                modifier = modifier,
            )
            Tab.QADA -> QadaScreen(data = data, today = today, onEdit = onEdit, snackbar = snackbar, modifier = modifier)
            Tab.SETTINGS -> SettingsScreen(
                data = data,
                today = today,
                onEdit = onEdit,
                backupText = backupText,
                restore = restore,
                snackbar = snackbar,
                modifier = modifier,
            )
        }
    }
}
