package xyz.aezdek.hush.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.BrandMark
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Wordmark

/** Sign in or create an account with an invite. The password never leaves the phone; only a derived key does. */
@Composable
fun Welcome() {
    var creating by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf("") }
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
            creating && password.length < 10 -> "Use at least 10 characters for your password"
            creating && password != confirm -> "The passwords don't match"
            password.isEmpty() -> "Enter your password"
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            try {
                if (creating) Graph.client.signUp(invite, username, password) else Graph.client.signIn(username, password)
            } catch (e: Exception) {
                error = Graph.messageOf(e)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Aez.colors.bg)) {
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
                Segmented(creating) { creating = it; error = null }
                AnimatedContent(creating, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "invite") { create ->
                    if (create) AezField(invite, { invite = it.uppercase() }, "Invite code", supporting = "Ask someone already on Hush")
                }
                AezField(username, { username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' }.take(30) }, "Username")
                AezField(password, { password = it }, "Password", password = true)
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
            Spacer(Modifier.height(48.dp))
        }
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

