package xyz.aezdek.hush.ui.settings

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.DisguiseSecret
import xyz.aezdek.hush.client.DisguiseTrigger
import xyz.aezdek.hush.disguise.Disguise
import xyz.aezdek.hush.disguise.Look
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.ButtonStyle
import xyz.aezdek.hush.ui.components.ConfirmDialog
import xyz.aezdek.hush.ui.messages.ToggleRow
import xyz.aezdek.hush.ui.messages.findActivity
import xyz.aezdek.hush.ui.theme.Aez

/** Settings → Disguise: the icon Hush wears, the secret move, the PIN, notifications, and turning it on or off. */
@Composable
internal fun DisguiseSection() {
    val context = LocalContext.current
    val lookId by Graph.prefs.disguiseLook.collectAsState()
    val triggerId by Graph.prefs.disguiseTrigger.collectAsState()
    val secret by Graph.prefs.disguiseSecret.collectAsState()
    val notify by Graph.prefs.disguiseNotify.collectAsState()
    val current = Look.of(lookId)
    val on = current != Look.HUSH
    val trigger = DisguiseTrigger.of(triggerId)
    var picked by remember { mutableStateOf(if (on) current else Look.CALCULATOR) }
    var turningOn by remember { mutableStateOf<Look?>(null) }
    var turningOff by remember { mutableStateOf(false) }
    val ready = secret.isNotEmpty()

    Card(
        note = "Hush can look like another app on your home screen. Opening it then shows that app crashing, and only your " +
            "secret move and PIN get you in.",
    ) {
        if (on) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LookIcon(current, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Disguise is on", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Aez.colors.text)
                    Text("Hush looks like ${current.title} on your home screen.", fontSize = 13.sp, color = Aez.colors.text2)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AezButton("Try it", { context.startActivity(Disguise.practiceIntent(context, current)) }, Modifier.weight(1f), style = ButtonStyle.SECONDARY)
                AezButton("Turn off", { turningOff = true }, Modifier.weight(1f), style = ButtonStyle.DANGER)
            }
        }
    }

    Card(
        "Icon",
        note = "What your home screen shows instead of Hush. Pick one your phone doesn't already have, or its app list " +
            "will show two. Blank leaves an empty spot, with no name, that still opens.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (look in Look.DISGUISES) {
                LookTile(look, selected = look == picked) {
                    picked = look
                    if (on && look != current) turningOn = look
                }
            }
        }
    }

    Card("Your secret move", note = "Made on the \"keeps stopping\" message. Pick one nobody would make by accident; nothing on screen gives it away.") {
        for (option in DisguiseTrigger.entries) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Graph.prefs.setDisguiseTrigger(option.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == trigger, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Aez.colors.text))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(option.title, fontSize = 15.sp, color = Aez.colors.text)
                    Text(option.hint, fontSize = 12.sp, color = Aez.colors.text2, lineHeight = 16.sp)
                }
            }
        }
    }

    SecretCard(ready)

    Card {
        ToggleRow(
            Icons.Outlined.Notifications, "Notifications while disguised", notify,
            if (notify) "They come, with no names or words, under the disguise's name. Android still puts \"Hush\" at the top of each."
            else "Nothing shows or rings until you open Hush. Calls that come meanwhile are missed.",
        ) {
            Graph.prefs.setDisguiseNotify(!notify)
            Disguise.refreshService(context)
        }
    }

    if (!on) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AezButton("Try it", { context.startActivity(Disguise.practiceIntent(context, picked)) }, Modifier.weight(1f), style = ButtonStyle.SECONDARY, enabled = ready)
            AezButton("Turn on disguise", { turningOn = picked }, Modifier.weight(1f), style = ButtonStyle.BRAND, enabled = ready)
        }
        if (!ready) {
            Text("Set a PIN or password first.", fontSize = 13.sp, color = Aez.colors.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }

    Card("Good to know") {
        val notes = buildList {
            add("Hush closes whenever you leave it or the screen turns off. You get back in the same way.")
            add("A new icon may not be on your home screen at first: find it in your app list and drag it there.")
            add("Android still shows Hush's real name in Settings → Apps, and at the top of any notification.")
            if (Build.VERSION.SDK_INT < 33) add("On this version of Android, screenshots of Hush are blocked while it's disguised.")
            add("Forgot the PIN? In Android's Settings → Apps → Hush, clear its storage (or reinstall it) and sign in again. Everything is kept on your server.")
        }
        for (note in notes) {
            Row {
                Text("•", fontSize = 13.sp, color = Aez.colors.text2, modifier = Modifier.padding(end = 8.dp))
                Text(note, fontSize = 13.sp, color = Aez.colors.text2, lineHeight = 18.sp)
            }
        }
    }

    turningOn?.let { look ->
        val first = !on
        ConfirmDialog(
            if (first) "Turn on the disguise?" else "Change to ${look.title}?",
            (if (first) "Hush closes now and comes back as ${look.title}. " else "Hush closes now to change its icon. ") +
                "From now on, open ${look.title}, ${trigger.title.replaceFirstChar { it.lowercase() }}, then enter your ${if (Graph.prefs.disguiseSecretIsPin) "PIN" else "password"}.",
            if (first) "Turn on" else "Change",
            danger = false,
            onConfirm = { context.findActivity()?.let { Disguise.turnOn(it, look) } },
            onDismiss = {
                turningOn = null
                if (on) picked = current
            },
        )
    }
    if (turningOff) {
        ConfirmDialog(
            "Turn off the disguise?",
            "Hush goes back to its own name and icon. If it isn't on your home screen, find it in your app list.",
            "Turn off",
            onConfirm = {
                Disguise.turnOff(context)
                Graph.toast("Hush is itself again")
            },
            onDismiss = { turningOff = false },
        )
    }
}

/** The PIN or password: set it, or change it. Kept as a hash on this phone only. */
@Composable
private fun SecretCard(isSet: Boolean) {
    val scope = rememberCoroutineScope()
    var editing by remember(isSet) { mutableStateOf(!isSet) }
    var pin by remember { mutableStateOf(Graph.prefs.disguiseSecretIsPin) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val word = if (pin) "PIN" else "password"
    Card(
        if (pin) "PIN" else "Password",
        note = "Asked for after your secret move. It is only for the disguise (not your account's password) and never leaves this phone.",
    ) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Aez.colors.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("${word.replaceFirstChar { it.uppercase() }} set", fontSize = 15.sp, color = Aez.colors.text, modifier = Modifier.weight(1f))
                AezButton("Change", {
                    first = ""
                    second = ""
                    editing = true
                }, style = ButtonStyle.SECONDARY, small = true)
            }
            return@Card
        }
        Choice(!pin, "PIN", "Password") {
            pin = !it
            first = ""
            second = ""
        }
        val problem = DisguiseSecret.problem(first, pin)
        val type = if (pin) KeyboardType.NumberPassword else KeyboardType.Password
        val limit = if (pin) DisguiseSecret.MAX_PIN else DisguiseSecret.MAX_PASSWORD
        AezField(first, { first = it.take(limit) }, "New $word", password = true, keyboardType = type, supporting = problem.takeIf { first.isNotEmpty() })
        AezField(
            second, { second = it.take(limit) }, "The same again", password = true, keyboardType = type,
            supporting = "Doesn't match".takeIf { second.isNotEmpty() && second != first },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isSet) AezButton("Cancel", { editing = false }, Modifier.weight(1f), style = ButtonStyle.GHOST)
            AezButton(
                "Save $word",
                {
                    busy = true
                    val chosen = first
                    val isPin = pin
                    scope.launch {
                        val kept = withContext(Dispatchers.Default) { DisguiseSecret.hash(chosen) }
                        Graph.prefs.setDisguiseSecret(kept, isPin)
                        busy = false
                        editing = false
                        first = ""
                        second = ""
                        Graph.toast("${if (isPin) "PIN" else "Password"} saved")
                    }
                },
                Modifier.weight(1f),
                enabled = problem == null && first == second,
                busy = busy,
            )
        }
    }
}

@Composable
private fun LookTile(look: Look, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(78.dp).clip(shape)
            .background(if (selected) Aez.colors.elev2 else Color.Transparent)
            .border(2.dp, if (selected) Aez.colors.text else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LookIcon(look, 48.dp)
        Spacer(Modifier.height(6.dp))
        Text(look.title, fontSize = 12.sp, color = Aez.colors.text, maxLines = 1)
    }
}

/** A look's icon as a round launcher draws it: its two layers, the outer third cut away. */
@Composable
internal fun LookIcon(look: Look, size: Dp) {
    if (look.background == 0) {
        Box(Modifier.size(size).border(1.5.dp, Aez.colors.border, CircleShape))
        return
    }
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Image(painterResource(look.background), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(look.foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}
