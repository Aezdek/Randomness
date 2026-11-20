package xyz.aezdek.daybook.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.Task
import xyz.aezdek.daybook.domain.plusDays
import xyz.aezdek.daybook.domain.today
import xyz.aezdek.daybook.sync.Appearance
import xyz.aezdek.daybook.ui.components.AppleText
import xyz.aezdek.daybook.ui.components.BannerLayer
import xyz.aezdek.daybook.ui.components.GlassIconButton
import xyz.aezdek.daybook.ui.components.LocalOverlay
import xyz.aezdek.daybook.ui.components.MenuLayer
import xyz.aezdek.daybook.ui.components.OverlayState
import xyz.aezdek.daybook.ui.components.SheetLayer
import xyz.aezdek.daybook.ui.components.SymbolIcon
import xyz.aezdek.daybook.ui.progress.ProgressScreen
import xyz.aezdek.daybook.ui.settings.SettingsScreen
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.ContinuousShape
import xyz.aezdek.daybook.ui.theme.DaybookTheme
import xyz.aezdek.daybook.ui.theme.GlassVariant
import xyz.aezdek.daybook.ui.theme.LocalHazeState
import xyz.aezdek.daybook.ui.theme.LocalScreenMargin
import xyz.aezdek.daybook.ui.theme.Radii
import xyz.aezdek.daybook.ui.theme.Symbols
import xyz.aezdek.daybook.ui.theme.glass
import xyz.aezdek.daybook.ui.theme.scrollEdge
import xyz.aezdek.daybook.ui.today.SyncStatusRow
import xyz.aezdek.daybook.ui.today.TaskEditor
import xyz.aezdek.daybook.ui.today.TodayScreen

/** Layout switches from a bottom tab bar to a sidebar at this width (regular size class). */
private val RegularWidth = 720.dp
private val SidebarWidth = 272.dp

/**
 * The whole app. [systemDark] and [reduceMotion] come from the platform; [commands] carries keyboard
 * shortcuts on desktop and Escape on the web. [isWeb] adjusts Settings for the browser.
 */
@Composable
fun DaybookApp(
    app: Daybook,
    systemDark: Boolean,
    reduceMotion: Boolean,
    commands: Flow<AppCommand> = emptyFlow(),
    initialTab: Tab = Tab.Today,
    initialDate: LocalDate? = null,
    isWeb: Boolean = false,
) {
    val appearance by app.appearance.collectAsState()
    val dark = when (appearance) {
        Appearance.System -> systemDark
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    DaybookTheme(dark = dark, reduceMotion = reduceMotion) {
        val colors = Apple.colors
        val overlay = remember { OverlayState() }
        val controller = remember(app) { AppController(app, overlay, app.scope) }
        val tasks by app.tasks.tasks.collectAsState(initial = emptyList())
        val examDate by app.tasks.examDate.collectAsState(initial = null)
        val syncStatus by app.sync.status.collectAsState()
        val pending by app.tasks.pendingCount.collectAsState(initial = 0)
        val today by produceState(today()) {
            while (true) {
                delay(30_000)
                value = today()
            }
        }
        var tab by remember { mutableStateOf(initialTab) }
        var selected by remember { mutableStateOf(initialDate ?: today) }
        var lastToday by remember { mutableStateOf(today) }
        if (today != lastToday) {
            // Past midnight: keep following "today" if that's what was on screen.
            if (selected == lastToday) selected = today
            lastToday = today
        }
        val todayList = rememberLazyListState()
        val progressList = rememberLazyListState()
        val settingsList = rememberLazyListState()
        val hazeState = rememberHazeState()

        fun openEditor(task: Task?, subject: Subject? = null) {
            overlay.showSheet(key = task?.id ?: "new") { dismiss ->
                TaskEditor(
                    existing = task,
                    initialSubject = subject ?: Subject.CS,
                    initialDate = selected,
                    today = today,
                    onSave = { draft -> if (task == null) controller.add(draft) else controller.edit(task, draft) },
                    onDelete = task?.let { { controller.delete(it) } },
                    dismiss = dismiss,
                )
            }
        }

        fun go(to: Tab) {
            tab = to
            overlay.dismissBanner()
        }

        LaunchedEffect(commands) {
            commands.collect { command ->
                when (command) {
                    AppCommand.NewTask -> { go(Tab.Today); openEditor(null) }
                    AppCommand.GoToToday -> { go(Tab.Today); selected = today }
                    AppCommand.NextDay -> selected = selected.plusDays(1)
                    AppCommand.PreviousDay -> selected = selected.plusDays(-1)
                    AppCommand.ShowToday -> go(Tab.Today)
                    AppCommand.ShowProgress -> go(Tab.Progress)
                    AppCommand.ShowSettings -> go(Tab.Settings)
                    AppCommand.SyncNow -> controller.syncNow()
                    AppCommand.Dismiss -> overlay.dismissTop()
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            val regular = maxWidth >= RegularWidth
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val tabBarSpace = 64.dp + 12.dp + navInset
            val chrome = if (regular) PaddingValues(start = SidebarWidth) else PaddingValues(bottom = tabBarSpace)

            CompositionLocalProvider(
                LocalHazeState provides hazeState,
                LocalOverlay provides overlay,
                LocalScreenMargin provides if (regular) 20.dp else 16.dp,
            ) {
                // Content layer.
                Crossfade(tab, animationSpec = tween(if (reduceMotion) 120 else 200), label = "tab") { current ->
                    when (current) {
                        Tab.Today -> TodayScreen(
                            controller = controller,
                            tasks = tasks,
                            today = today,
                            selected = selected,
                            onSelect = { selected = it },
                            listState = todayList,
                            chrome = chrome,
                            regular = regular,
                            syncStatus = syncStatus,
                            pending = pending,
                            onOpenSync = { go(Tab.Settings) },
                            onAdd = { openEditor(null, it) },
                            onOpen = { openEditor(it) },
                        )
                        Tab.Progress -> ProgressScreen(controller, tasks, examDate, today, selected, progressList, chrome)
                        Tab.Settings -> SettingsScreen(app, settingsList, chrome, isWeb)
                    }
                }

                // Control layer.
                if (regular) {
                    Sidebar(tab, onSelect = ::go, syncStatus = syncStatus, pending = pending, onOpenSync = { go(Tab.Settings) })
                } else {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(tabBarSpace + 24.dp)
                            .scrollEdge(top = false),
                    )
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TabBar(tab, onSelect = ::go, modifier = Modifier.weight(1f))
                        if (tab == Tab.Today) {
                            GlassIconButton(Symbols.plus, "New Task", onClick = { openEditor(null) }, variant = GlassVariant.Prominent, size = 64.dp)
                        }
                    }
                }

                BannerLayer(overlay, bottomPadding = if (regular) 24.dp else tabBarSpace + 12.dp)
                SheetLayer(overlay, regular)
                MenuLayer(overlay)
            }
        }

        PlatformBackHandler(enabled = overlay.sheet != null || overlay.menu != null) { overlay.dismissTop() }
    }
}

private data class TabItem(val tab: Tab, val symbol: ImageVector, val selectedSymbol: ImageVector)

private val TabItems = listOf(
    TabItem(Tab.Today, Symbols.calendar, Symbols.calendarFill),
    TabItem(Tab.Progress, Symbols.chart, Symbols.chartFill),
    TabItem(Tab.Settings, Symbols.gear, Symbols.gearFill),
)

/** Top-level destinations only. A floating glass capsule; the selected tab sits on a lighter lens. */
@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val colors = Apple.colors
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .height(64.dp)
            .glass(RoundedCornerShape(50))
            .padding(4.dp)
            .selectableGroup(),
    ) {
        for (item in TabItems) {
            val isSelected = item.tab == current
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) colors.tertiaryFill else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = {
                            if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(item.tab)
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val tint = if (isSelected) colors.accent else colors.label
                SymbolIcon(if (isSelected) item.selectedSymbol else item.symbol, null, tint = tint, size = 24.dp)
                AppleText(item.tab.title, Apple.type.caption2Semibold, Modifier.padding(top = 2.dp), color = tint, maxLines = 1)
            }
        }
    }
}

/** In regular width the tab bar becomes a floating glass sidebar. Its rows are concentric with it. */
@Composable
private fun Sidebar(
    current: Tab,
    onSelect: (Tab) -> Unit,
    syncStatus: xyz.aezdek.daybook.sync.SyncStatus,
    pending: Int,
    onOpenSync: () -> Unit,
) {
    val colors = Apple.colors
    val inset = 10.dp
    val innerPadding = 12.dp
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .padding(start = inset, top = inset + topInset, bottom = inset + bottomInset)
            .width(SidebarWidth - inset * 2)
            .fillMaxHeight()
            .glass(ContinuousShape(Radii.sheet))
            .padding(innerPadding)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppleText("Daybook", Apple.type.title2Bold, Modifier.padding(start = 10.dp, top = 8.dp, bottom = 12.dp))
        for (item in TabItems) {
            val isSelected = item.tab == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(ContinuousShape(Radii.concentric(Radii.sheet, innerPadding)))
                    .background(if (isSelected) colors.tertiaryFill else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item.tab) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SymbolIcon(if (isSelected) item.selectedSymbol else item.symbol, null, tint = if (isSelected) colors.accent else colors.label, size = 22.dp)
                AppleText(item.tab.title, if (isSelected) Apple.type.bodySemibold else Apple.type.body)
            }
        }
        Spacer(Modifier.weight(1f))
        SyncStatusRow(syncStatus, pending, onOpenSync, Modifier.fillMaxWidth())
    }
}
