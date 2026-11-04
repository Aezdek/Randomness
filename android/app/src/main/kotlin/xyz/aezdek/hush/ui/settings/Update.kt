package xyz.aezdek.hush.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.BuildConfig
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.messages.ToggleRow
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand
import xyz.aezdek.hush.update.AutoUpdater
import xyz.aezdek.hush.update.AutoUpdater.State

/**
 * Updates come from your own server, never a store: the APK inside the image you approved with update.sh, signed on
 * the server with the key this app was installed with. Hush checks by itself (see [AutoUpdater]); this screen shows
 * where that stands and lets you check or update right now.
 */
@Composable
internal fun UpdatesSection() {
    val context = LocalContext.current
    val state by AutoUpdater.state.collectAsState()
    var auto by remember { mutableStateOf(Graph.prefs.autoUpdate) }
    LaunchedEffect(Unit) { AutoUpdater.check(context, force = true) }
    Card("This phone") {
        Text("Hush ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = Aez.colors.text)
    }
    Card("From your server") {
        when (val s = state) {
            State.Idle, State.Checking -> Spinner()
            State.UpToDate -> Text("You're up to date.", color = Aez.colors.text2)
            is State.Failed -> Text(s.message, color = Aez.colors.text2)
            is State.Downloading -> {
                Text("Downloading Hush ${s.version.versionName} (${Formatter.formatShortFileSize(context, s.version.size)})…", color = Aez.colors.text)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = Brand.pink, trackColor = Aez.colors.elev2)
            }
            is State.Ready -> {
                Text("Hush ${s.version.versionName} is ready to install.", color = Aez.colors.text)
                AezButton("Update now", { AutoUpdater.installNow(context) }, Modifier.fillMaxWidth(), style = ButtonStyle.BRAND, icon = Icons.Outlined.SystemUpdate)
            }
            is State.Installing -> Text("Installing Hush ${s.version.versionName}… Hush restarts when it's done.", color = Aez.colors.text)
        }
        if (state is State.UpToDate || state is State.Failed) {
            AezButton("Check now", { AutoUpdater.checkSoon(context, force = true) }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
        }
        Text(
            "New versions only appear after you run update.sh on the server, and Android refuses any APK not signed with your server's key.",
            fontSize = 12.sp, color = Aez.colors.text3,
        )
    }
    Card {
        ToggleRow(
            Icons.Outlined.SystemUpdate, "Update automatically", auto,
            "Checks every few hours and installs while Hush is in the background, never during a call.",
        ) {
            auto = !auto
            Graph.prefs.autoUpdate = auto
            if (auto) AutoUpdater.checkSoon(context, force = true)
        }
    }
}

/**
 * Shown over the app when an update is ready while you're using Hush: installing closes the app for a few seconds,
 * so it asks. "Later" leaves it to install the next time Hush is in the background.
 */
@Composable
internal fun UpdatePrompt() {
    val context = LocalContext.current
    val state by AutoUpdater.state.collectAsState()
    val ready = state as? State.Ready ?: return
    var dismissed by rememberSaveable { mutableStateOf<Int?>(null) }
    if (dismissed == ready.version.versionCode) return
    AlertDialog(
        onDismissRequest = { dismissed = ready.version.versionCode },
        containerColor = Aez.colors.elev2,
        title = { Text("Hush ${ready.version.versionName} is ready", style = MaterialTheme.typography.titleLarge) },
        text = { Text("Updating takes a few seconds and closes Hush while it installs. Later, it updates by itself the next time Hush is in the background.", color = Aez.colors.text2) },
        confirmButton = {
            TextButton(onClick = { dismissed = ready.version.versionCode; AutoUpdater.installNow(context) }) {
                Text("Update now", color = Aez.colors.text, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { dismissed = ready.version.versionCode }) { Text("Later", color = Aez.colors.text2) } },
    )
}
