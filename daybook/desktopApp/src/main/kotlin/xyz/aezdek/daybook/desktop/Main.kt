package xyz.aezdek.daybook.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.data.SqliteLocalStore
import xyz.aezdek.daybook.ui.AppCommand
import xyz.aezdek.daybook.ui.DaybookApp
import java.awt.Dimension

fun main() {
    val daybook = runBlocking {
        Daybook.open(
            store = SqliteLocalStore.open(BundledSQLiteDriver(), DesktopPaths.database.absolutePath, Dispatchers.IO.limitedParallelism(1)),
            http = HttpClient(OkHttp),
            configStore = DesktopConfigStore(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    DesktopPaths.restrict(DesktopPaths.database)
    daybook.start()
    val systemDark = DesktopSystem.prefersDark()
    val reduceMotion = DesktopSystem.reduceMotion()
    val commands = MutableSharedFlow<AppCommand>(extraBufferCapacity = 8)

    val appIcon = BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(
            requireNotNull(Daybook::class.java.getResourceAsStream("/daybook.png")) { "icon missing" }.readBytes(),
        ).toComposeImageBitmap(),
    )

    application {
        val state = rememberWindowState(size = DpSize(1120.dp, 780.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Daybook",
            icon = appIcon,
            onPreviewKeyEvent = { event -> shortcut(event)?.let { commands.tryEmit(it); true } ?: false },
        ) {
            window.minimumSize = Dimension(400, 620)
            DaybookApp(daybook, systemDark = systemDark, reduceMotion = reduceMotion, commands = commands)
        }
    }
}

/**
 * Ctrl+N new task, Ctrl+T today, Alt+Left/Right previous and next day, Ctrl+1/2/3 switch screens,
 * Ctrl+R sync now, Escape closes a sheet or menu.
 */
private fun shortcut(event: KeyEvent): AppCommand? {
    if (event.type != KeyEventType.KeyDown) return null
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    return when {
        event.key == Key.Escape -> AppCommand.Dismiss
        ctrl && event.key == Key.N -> AppCommand.NewTask
        ctrl && event.key == Key.T -> AppCommand.GoToToday
        ctrl && event.key == Key.R -> AppCommand.SyncNow
        ctrl && event.key == Key.One -> AppCommand.ShowToday
        ctrl && event.key == Key.Two -> AppCommand.ShowProgress
        ctrl && event.key == Key.Three -> AppCommand.ShowSettings
        event.isAltPressed && event.key == Key.DirectionRight -> AppCommand.NextDay
        event.isAltPressed && event.key == Key.DirectionLeft -> AppCommand.PreviousDay
        else -> null
    }
}
