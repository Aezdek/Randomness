package xyz.aezdek.hush.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.PasswordRules
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.BrandMark
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand
import xyz.aezdek.hush.ui.theme.Wordmark
import kotlin.math.cos
import kotlin.math.sin

/** What someone typed as their server, as an https address; null if it can't be one. */
internal fun serverAddress(typed: String): String? {
    val text = typed.trim().trimEnd('/').removePrefix("https://")
    if (text.isEmpty() || text.startsWith("http://") || text.any { it.isWhitespace() || it == '/' || it == '@' }) return null
    return "https://$text".takeIf { it.toHttpUrlOrNull() != null }
}

private val INVITE_CODE = Regex("^[A-Z0-9]{5}-[A-Z0-9]{5}$")

/**
 * An invite link, as an admin's invite QR code holds it (https://server/?invite=CODE): the server's address and the
 * code, or null if it isn't one. Only https, and only a well-formed code.
 */
internal fun parseInviteLink(text: String): Pair<String, String>? {
    val url = text.trim().toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val code = url.queryParameter("invite")?.trim()?.uppercase() ?: return null
    if (!INVITE_CODE.matches(code)) return null
    val server = serverAddress(url.host + if (url.port != 443) ":${url.port}" else "") ?: return null
    return server to code
}

/** Scans an invite QR code with the phone's scanner and hands back what it holds (or says why not). */
private fun scanInvite(context: android.content.Context, onInvite: (server: String, code: String) -> Unit) {
    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context).startScan()
        .addOnSuccessListener { barcode ->
            val invite = barcode.rawValue?.let(::parseInviteLink)
            if (invite == null) Graph.toast("That isn't a Hush invite", error = true) else onInvite(invite.first, invite.second)
        }
        .addOnFailureListener { Graph.toast("The scanner isn't available on this phone", error = true) }
}

/**
 * A build that wasn't made for one particular server asks which one is yours, once. It checks that a Hush server
 * answers there before it takes the address.
 */
@Composable
private fun ChooseServer() {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val url = serverAddress(address)
        if (url == null) {
            error = "Enter your server's address, like hush.example.com"
            return
        }
        busy = true
        error = null
        scope.launch {
            val answers = withContext(Dispatchers.IO) {
                runCatching {
                    Graph.http.newCall(Request.Builder().url("$url/healthz").build()).execute().use { it.isSuccessful && it.body.string().contains("\"ok\":true") }
                }.getOrDefault(false)
            }
            busy = false
            if (answers) Graph.useServer(url) else error = "No Hush server answered there. Check the address and your connection."
        }
    }

    Box(Modifier.fillMaxSize().background(Aez.colors.bg)) {
        Aurora(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            BrandMark(Modifier.size(64.dp))
            Spacer(Modifier.height(14.dp))
            Text("Hush", style = Wordmark.copy(fontSize = 46.sp, color = Aez.colors.text))
            Spacer(Modifier.height(8.dp))
            Text(
                "Hush runs on a server of your own.\nWhich one is yours?",
                style = MaterialTheme.typography.bodyMedium, color = Aez.colors.text2, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Aez.colors.elev1.copy(alpha = 0.86f)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AezField(address, { address = it.trim().lowercase() }, "Server address", supporting = "Like hush.example.com. Whoever invited you knows it.")
                error?.let { Text(it, color = Aez.colors.danger, style = MaterialTheme.typography.bodySmall) }
                AezButton("Continue", ::submit, Modifier.fillMaxWidth(), style = ButtonStyle.BRAND, busy = busy)
                val context = androidx.compose.ui.platform.LocalContext.current
                AezButton("Scan an invite", {
                    scanInvite(context) { server, code ->
                        // The address shows in the field, to read before Continue checks it like a typed one; the code
                        // waits for the next screen.
                        Graph.prefs.pendingInvite = code
                        address = server.removePrefix("https://")
                        Graph.toast("Check the address, then Continue")
                    }
                }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY, icon = androidx.compose.material.icons.Icons.Outlined.QrCodeScanner)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Sign in or create an account with an invite. The password never leaves the phone; only a derived key does. */
@Composable
fun Welcome() {
    if (Graph.serverUrl.isBlank()) {
        ChooseServer()
        return
    }
    // An invite scanned before the app switched to its server waits here for the join form.
    val scanned = remember { Graph.prefs.pendingInvite.also { Graph.prefs.pendingInvite = "" } }
    var creating by remember { mutableStateOf(scanned.isNotEmpty()) }
    var invite by remember { mutableStateOf(scanned) }
    var otherServer by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        error = when {
            username.isBlank() -> "Choose a username"
            creating && invite.isBlank() -> "Enter your invite code"
            creating -> PasswordRules.check(password, username)?.message ?: if (password != confirm) "The passwords don't match" else null
            password.isEmpty() -> "Enter your password"
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            try {
                if (creating) {
                    Graph.client.signUp(invite, username, password)
                    Graph.prefs.setForcedOut(false)
                } else {
                    Graph.client.signIn(username, password)
                    Graph.prefs.setForcedOut(false)
                    // An older password the rules would refuse now: ask for a better one, once signed in.
                    Graph.prefs.setWeakPassword(PasswordRules.check(password, username) != null)
                }
            } catch (e: Exception) {
                error = Graph.messageOf(e)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Aez.colors.bg)) {
        Aurora(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            BrandMark(Modifier.size(64.dp))
            Spacer(Modifier.height(14.dp))
            Text("Hush", style = Wordmark.copy(fontSize = 46.sp, color = Aez.colors.text))
            Spacer(Modifier.height(8.dp))
            Text(
                "Photos, stories and calls with the people who matter.\nEnd-to-end encrypted. Not even the server can see them.",
                style = MaterialTheme.typography.bodyMedium, color = Aez.colors.text2, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Aez.colors.elev1.copy(alpha = 0.86f)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val forcedOut by Graph.prefs.forcedOut.collectAsState()
                if (forcedOut && !creating) {
                    Text(
                        "This phone was signed out from somewhere else: another device, a password change, or whoever runs your server. " +
                            "If that wasn't you, sign in and look at Settings → Security → Recent sign-ins.",
                        style = MaterialTheme.typography.bodySmall, color = Aez.colors.text,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Aez.colors.danger.copy(alpha = 0.14f)).padding(12.dp),
                    )
                }
                Segmented(creating) { creating = it; error = null }
                AnimatedContent(creating, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "invite") { create ->
                    if (create) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AezField(invite, { invite = it.uppercase() }, "Invite code", supporting = "Ask someone already on Hush")
                            AezButton("Scan an invite", {
                                scanInvite(context) { server, code ->
                                    if (server.trimEnd('/') == Graph.serverUrl.trimEnd('/')) invite = code else otherServer = server to code
                                }
                            }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY, small = true, icon = androidx.compose.material.icons.Icons.Outlined.QrCodeScanner)
                        }
                    }
                }
                AezField(username, { username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' }.take(30) }, "Username")
                AezField(
                    password, { password = it }, "Password", password = true,
                    supporting = if (creating && password.isNotEmpty()) PasswordRules.check(password, username)?.message ?: "Strong enough." else null,
                )
                AnimatedContent(creating, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "confirm") { create ->
                    if (create) AezField(confirm, { confirm = it }, "Confirm password", password = true)
                }
                error?.let { Text(it, color = Aez.colors.danger, style = MaterialTheme.typography.bodySmall) }
                AezButton(if (creating) "Create account" else "Sign in", ::submit, Modifier.fillMaxWidth(), style = ButtonStyle.BRAND, busy = busy)
                if (creating) {
                    Text(
                        "Your password unlocks your keys. There's no reset: if you forget it, your account can't be recovered.",
                        style = MaterialTheme.typography.bodySmall, color = Aez.colors.text3, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (Graph.serverIsChosen) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Server: ${Graph.serverUrl.removePrefix("https://")}  ·  Change",
                    style = MaterialTheme.typography.bodySmall, color = Aez.colors.text3,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Graph.useServer("") }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
    otherServer?.let { (server, code) ->
        xyz.aezdek.hush.ui.components.ConfirmDialog(
            "Join a different server?",
            "This invite is for ${server.removePrefix("https://")}, not ${Graph.serverUrl.removePrefix("https://")}. Hush will restart and use that server.",
            "Join it", danger = false,
            onConfirm = {
                Graph.prefs.pendingInvite = code
                Graph.useServer(server)
            },
            onDismiss = { otherServer = null },
        )
    }
}

@Composable
private fun Segmented(creating: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Aez.colors.elev2).padding(4.dp)) {
        for ((value, label) in listOf(false to "Sign in", true to "Create account")) {
            val selected = value == creating
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (selected) Aez.colors.bg else Color.Transparent)
                    .clickable { onChange(value) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (selected) Aez.colors.text else Aez.colors.text2)
            }
        }
    }
}

/** Slowly drifting brand-coloured light behind the welcome card. */
@Composable
private fun Aurora(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val alpha = if (Aez.colors.isDark) 0.55f else 0.35f
    Canvas(modifier.blur(90.dp)) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(Brand.violet, w * (0.25f + 0.12f * cos(t)), h * (0.18f + 0.06f * sin(t * 2)), w * 0.7f)
        blob(Brand.pink, w * (0.8f + 0.1f * sin(t)), h * (0.32f + 0.08f * cos(t)), w * 0.6f)
        blob(Brand.orange, w * (0.5f + 0.15f * cos(t + 1.5f)), h * (0.08f + 0.05f * sin(t + 1f)), w * 0.45f)
    }
}
