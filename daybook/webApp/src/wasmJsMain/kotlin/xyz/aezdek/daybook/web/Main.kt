package xyz.aezdek.daybook.web

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.window.ComposeViewport
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.data.MemoryLocalStore
import xyz.aezdek.daybook.ui.AppCommand
import xyz.aezdek.daybook.ui.DaybookApp

/**
 * Daybook in the browser: the same Compose UI as the Android and Linux apps, compiled to WebAssembly.
 * Tasks are kept in this browser's storage, so the page works offline once loaded, and sync with the
 * server it was loaded from.
 */
fun main() {
    val commands = MutableSharedFlow<AppCommand>(extraBufferCapacity = 8)
    MainScope().launch {
        val app = Daybook.open(
            store = MemoryLocalStore(BrowserSnapshotStorage()),
            http = HttpClient(Js),
            configStore = WebConfigStore(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        app.start()
        // Hold the long poll only while the tab is visible; catch up as soon as it is shown again.
        onVisibilityChange { visible -> app.sync.setForeground(visible) }
        onEscapeKey { commands.tryEmit(AppCommand.Dismiss) }
        val reduceMotion = prefersReducedMotion()

        hideLoadingMessage()
        ComposeViewport("daybook") {
            DaybookApp(app, systemDark = isSystemInDarkTheme(), reduceMotion = reduceMotion, commands = commands, isWeb = true)
        }
    }
}
