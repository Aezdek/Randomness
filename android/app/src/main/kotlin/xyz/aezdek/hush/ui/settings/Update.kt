package xyz.aezdek.hush.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VerifiedUser
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
import androidx.compose.runtime.rememberCoroutineScope
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
import xyz.aezdek.hush.ui.components.ConfirmDialog
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.messages.ToggleRow
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand
import xyz.aezdek.hush.update.AutoUpdater
import xyz.aezdek.hush.update.AutoUpdater.State
import xyz.aezdek.hush.update.WebAppCheck
import kotlinx.coroutines.launch

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
    var verify by remember { mutableStateOf(Graph.prefs.verifyUpdates) }
    var confirmUnverified by remember { mutableStateOf(false) }
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
            is State.Unverified -> {
                Text("Hush ${s.version.versionName} is downloaded, but it couldn't be compared with the public build.", color = Aez.colors.text)
                Text(s.why, fontSize = 13.sp, color = Aez.colors.text2)
                AezButton("Try again", { AutoUpdater.checkSoon(context, force = true) }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
                AezButton("Install without checking", { confirmUnverified = true }, Modifier.fillMaxWidth(), style = ButtonStyle.GHOST)
            }
            is State.Refused -> {
                Text("Hush refused the update your server offers (${s.version.versionName}).", color = Aez.colors.danger, fontWeight = FontWeight.SemiBold)
                Text("${s.why} Someone may have changed it on the server. Tell whoever runs your server; this phone keeps the version it has.",
                    fontSize = 13.sp, color = Aez.colors.text2)
            }
        }
        if (state is State.UpToDate || state is State.Failed || state is State.Refused) {
            AezButton("Check now", { AutoUpdater.checkSoon(context, force = true) }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
        }
        Text(
            "New versions only appear after you run update.sh on the server. Android refuses any app not signed with your server's key, and " +
                "Hush refuses one whose contents aren't those of the public build.",
            fontSize = 12.sp, color = Aez.colors.text3,
        )
    }
    if (confirmUnverified) {
        ConfirmDialog(
            "Install without checking?", "Hush couldn't confirm this update is the public build. Only go on if you trust your server right now.",
            "Install", onConfirm = { confirmUnverified = false; AutoUpdater.installUnverified(context) }, onDismiss = { confirmUnverified = false },
        )
    }
    WebAppCard(verify)
    Card {
        ToggleRow(
            Icons.Outlined.SystemUpdate, "Update automatically", auto,
            "Checks every few hours and installs while Hush is in the background, never during a call.",
        ) {
            auto = !auto
            Graph.prefs.autoUpdate = auto
            if (auto) AutoUpdater.checkSoon(context, force = true)
        }
        if (WebAppCheck.available) {
            ToggleRow(
                Icons.Outlined.VerifiedUser, "Check against the public build", verify,
                "Compares each update, and your server's web app, with the record published where Hush is built. Leave this on.",
            ) {
                verify = !verify
                Graph.prefs.verifyUpdates = verify
            }
        }
    }
}

/** Whether the web app the server hands out is the public build (WebAppCheck). */
@Composable
private fun WebAppCard(enabled: Boolean) {
    if (!WebAppCheck.available || !enabled) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val last by WebAppCheck.last.collectAsState()
    val progress by WebAppCheck.progress.collectAsState()
    Card("Your server's web app") {
        val running = progress
        when {
            running != null -> {
                Text(if (running.second > 0) "Checking file ${running.first} of ${running.second}…" else "Checking…", color = Aez.colors.text)
                LinearProgressIndicator(
                    progress = { if (running.second > 0) running.first.toFloat() / running.second else 0f },
                    modifier = Modifier.fillMaxWidth(), color = Brand.pink, trackColor = Aez.colors.elev2,
                )
            }
            last.at == 0L -> Text("Not checked yet.", color = Aez.colors.text2)
            else -> {
                Text(
                    when (last.result) {
                        1 -> "Matches the public build"
                        -1 -> "Doesn't match the public build"
                        else -> "Couldn't be checked"
                    },
                    color = if (last.result == -1) Aez.colors.danger else Aez.colors.text, fontWeight = FontWeight.SemiBold,
                )
                Text(last.text, fontSize = 13.sp, color = Aez.colors.text2)
                Text("Checked ${android.text.format.DateUtils.getRelativeTimeSpanString(last.at)}", fontSize = 12.sp, color = Aez.colors.text3)
            }
        }
        if (running == null) {
            AezButton("Check now", { scope.launch { runCatching { WebAppCheck.run(context) } } }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
        }
        Text(
            "A browser runs whatever your server sends it. This fetches the same pages and scripts and compares every one with the public " +
                "build, so a change made on the server gets noticed.",
            fontSize = 12.sp, color = Aez.colors.text3,
        )
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
