package xyz.aezdek.hush.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import xyz.aezdek.hush.BuildConfig
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.ApiException
import xyz.aezdek.hush.client.UserFacingException
import xyz.aezdek.hush.protocol.AppVersion
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.Spinner
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand
import java.io.File
import java.security.MessageDigest

/**
 * Updates come from your own server, never a store: the APK inside the image you approved with update.sh, signed on
 * the server with the key this app was installed with. The download is checked against the SHA-256 the server lists,
 * and Android itself refuses an APK signed with any other key.
 */
@Composable
internal fun UpdatesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf<AppVersion?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        try {
            latest = Graph.client.api.get<AppVersion>("/app/version")
        } catch (e: ApiException) {
            message = if (e.status == 404) "Your server has no Android build yet." else e.message
        } catch (e: Exception) {
            message = Graph.messageOf(e)
        }
    }
    Card("This phone") {
        Text("Hush ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = Aez.colors.text)
    }
    Card("From your server") {
        val l = latest
        when {
            l == null && message == null -> Spinner()
            l == null -> Text(message.orEmpty(), color = Aez.colors.text2)
            l.versionCode <= BuildConfig.VERSION_CODE -> Text("You're up to date.", color = Aez.colors.text2)
            else -> {
                Text("Hush ${l.versionName} is ready (${Formatter.formatShortFileSize(context, l.size)}).", color = Aez.colors.text)
                val p = progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Brand.accent, trackColor = Aez.colors.elev2)
                }
                AezButton(if (p != null) "Downloading…" else "Update", {
                    scope.launch {
                        progress = 0f
                        val apk = Graph.attempt { download(context, l) { progress = it } }
                        progress = null
                        if (apk != null) install(context, apk)
                    }
                }, Modifier.fillMaxWidth(), style = ButtonStyle.BRAND, enabled = p == null, icon = Icons.Outlined.SystemUpdate)
            }
        }
        Text("Updates only arrive after you run update.sh on the server, and Android refuses any APK not signed with your server's key.",
            fontSize = 12.sp, color = Aez.colors.text3)
    }
}

private suspend fun download(context: Context, version: AppVersion, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "hush-${version.versionCode}.apk")
    val request = Request.Builder().url(BuildConfig.SERVER_URL.trimEnd('/') + version.url).build()
    Graph.http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw UserFacingException("The server couldn't send the update (${response.code}).")
        val body = response.body
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    onProgress((done.toFloat() / version.size).coerceIn(0f, 1f))
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != version.sha256) {
            file.delete()
            throw UserFacingException("The download didn't match what the server announced. Try again.")
        }
    }
    file
}

private fun install(context: Context, apk: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        Graph.toast("Allow Hush to install updates, then tap Update again")
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
