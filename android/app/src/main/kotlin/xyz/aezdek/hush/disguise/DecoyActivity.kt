package xyz.aezdek.hush.disguise

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.HushActivity
import xyz.aezdek.hush.client.DecoyInput
import xyz.aezdek.hush.client.DisguiseSecret
import xyz.aezdek.hush.client.DisguiseTrigger
import xyz.aezdek.hush.client.TriggerDetector

/**
 * What a disguised Hush's icon opens: that app "keeps stopping", drawn over the home screen as Android draws the real
 * thing. "App info", "Close app" and Back all close it, as they would. The chosen secret move ([DisguiseTrigger])
 * brings up the PIN or password prompt instead, and the right one opens Hush.
 *
 * It runs in a task of its own that stays out of the recent apps list, and closes whenever it leaves the screen.
 * Settings' "Try it" opens the same screen with a line saying what to do; then the PIN opens nothing.
 */
class DecoyActivity : ComponentActivity() {
    private var practice = false
    private var open: String? = null
    private lateinit var detector: TriggerDetector
    private val stage = MutableStateFlow(Stage.CRASH)

    private enum class Stage { CRASH, SECRET }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A practice run's token works once; turning the phone mid-run keeps it a practice run.
        practice = savedInstanceState?.getBoolean(STATE_PRACTICE) ?: Disguise.isPractice(intent)
        // Where to go once in: a place inside Hush, as a notification names it. Hush only ever navigates with it.
        open = intent?.getStringExtra(HushActivity.EXTRA_OPEN)
        if (!practice && !Disguise.locked()) {
            // Not disguised (an icon left from before), or already let in: straight to Hush.
            enterHush()
            return
        }
        if (Graph.prefs.disguiseSecret.value.isEmpty()) {
            finishAndRemoveTask()
            return
        }
        val trigger = Disguise.trigger
        detector = TriggerDetector(trigger)
        // White status bar icons over the dimmed home screen, as with the real message.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // Practice shows the look picked in Settings, which isn't put on until the disguise is turned on.
        val look = if (practice) Disguise.practiceLook(intent) else Disguise.look
        setContent {
            MaterialTheme(colorScheme = systemColors()) {
                val now by stage.collectAsState()
                when (now) {
                    Stage.CRASH -> CrashScreen(
                        look = look,
                        practiceHint = if (practice) trigger.hint else null,
                        holdGetsIn = trigger == DisguiseTrigger.HOLD_CLOSE,
                        onInput = ::feed,
                        onHeld = ::toSecret,
                        onClose = ::close,
                    )
                    Stage.SECRET -> SecretScreen(
                        pin = Graph.prefs.disguiseSecretIsPin,
                        practice = practice,
                        onRight = ::rightSecret,
                        onCancel = ::close,
                    )
                }
            }
        }
    }

    private fun feed(input: DecoyInput) {
        if (stage.value == Stage.CRASH && detector.feed(input, SystemClock.uptimeMillis())) toSecret()
    }

    /** On to the PIN, on the phone's own light or dark background (and its status bar icons to match). */
    private fun toSecret() {
        stage.value = Stage.SECRET
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
    }

    /** The volume move. The keys still change the volume, as they would anywhere else. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::detector.isInitialized && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> feed(DecoyInput.VOLUME_DOWN)
                KeyEvent.KEYCODE_VOLUME_UP -> feed(DecoyInput.VOLUME_UP)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun rightSecret() {
        if (practice) {
            Graph.toast(if (Disguise.isOn) "That's the way in" else "That's the way in. Turn the disguise on when you're ready.")
            finish()
            return
        }
        Disguise.reveal()
        enterHush()
    }

    private fun enterHush() {
        if (!Disguise.isOn) runCatching { Disguise.syncIcon(this) }
        startActivity(Disguise.hushIntent(this, open))
        finishAndRemoveTask()
    }

    private fun close() {
        if (practice) finish() else finishAndRemoveTask()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PRACTICE, practice)
    }

    override fun onStop() {
        super.onStop()
        // Left for anything else (home, another app, the screen going off): gone, like the app that "crashed".
        if (!isChangingConfigurations && !isFinishing) close()
    }

    private companion object {
        const val STATE_PRACTICE = "practice"
    }
}

/**
 * Both screens as they look, for the screenshot tests: a dark backdrop stands in for the home screen, and fixed
 * colours for the phone's own.
 */
@Composable
internal fun DecoyPreview(look: Look, secret: Boolean) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        if (secret) {
            SecretScreen(pin = true, practice = false, onRight = {}, onCancel = {})
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF1F3B4D))) {
                CrashScreen(look, practiceHint = null, holdGetsIn = false, onInput = {}, onHeld = {}, onClose = {})
            }
        }
    }
}

/** The phone's own colours (on Android 12+, its wallpaper's), as its real "keeps stopping" dialog has them. */
@Composable
private fun systemColors(): ColorScheme {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
}

@Composable
private fun CrashScreen(
    look: Look,
    practiceHint: String?,
    holdGetsIn: Boolean,
    onInput: (DecoyInput) -> Unit,
    onHeld: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        // The empty top-right corner, for the corner move. Taps anywhere else outside the dialog do nothing.
        Box(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().size(128.dp)
                .clickable(interactionSource = null, indication = null) { onInput(DecoyInput.CORNER) },
        )
        if (practiceHint != null) {
            Text(
                "Practice. $practiceHint",
                color = colors.onPrimaryContainer, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 136.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(16.dp)).background(colors.primaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp).widthIn(max = 420.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(colors.surfaceContainerHigh).padding(top = 24.dp, bottom = 12.dp),
        ) {
            Text(
                "${look.crashName} keeps stopping",
                color = colors.onSurface, fontSize = 22.sp, lineHeight = 28.sp,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)
                    .clickable(interactionSource = null, indication = null) { onInput(DecoyInput.MESSAGE) },
            )
            DialogAction(Icons.Outlined.Info, "App info", Modifier.clickable(onClick = onClose))
            CloseAction(holdGetsIn, onHeld, onClose)
        }
    }
}

@Composable
private fun DialogAction(icon: ImageVector, text: String, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
    }
}

/** "Close app": a tap closes, as anyone expects. Held for three seconds, with the hold move chosen, it gets in. */
@Composable
private fun CloseAction(holdGetsIn: Boolean, onHeld: () -> Unit, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    DialogAction(
        Icons.Outlined.Close, "Close app",
        Modifier.indication(interaction, ripple()).pointerInput(holdGetsIn) {
            detectTapGestures(onPress = { offset ->
                val press = PressInteraction.Press(offset)
                interaction.emit(press)
                var held = false
                val timer = if (holdGetsIn) scope.launch {
                    delay(TriggerDetector.HOLD_MS)
                    held = true
                    onHeld()
                } else null
                val released = tryAwaitRelease()
                timer?.cancel()
                interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                if (released && !held) onClose()
            })
        },
    )
}

/** The way in: the PIN pad (or a password field), with a wait after too many wrong tries. */
@Composable
private fun SecretScreen(pin: Boolean, practice: Boolean, onRight: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var entered by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(if (practice) "Now your ${if (pin) "PIN" else "password"}" else null) }
    var busy by remember { mutableStateOf(false) }
    var wait by remember { mutableLongStateOf(if (practice) 0 else Disguise.waitLeft()) }
    val shake = remember { Animatable(0f) }
    BackHandler(onBack = onCancel)
    LaunchedEffect(wait > 0) {
        while (wait > 0) {
            delay(1_000)
            wait = Disguise.waitLeft()
        }
    }

    fun submit() {
        if (busy || entered.isEmpty() || wait > 0) return
        busy = true
        val attempt = entered
        scope.launch {
            val result = withContext(Dispatchers.Default) { Disguise.check(attempt) }
            // The duress PIN: Hush opens, but only after its account is off this phone. (Practising, it is a wrong PIN.)
            if (result == Disguise.Entered.DURESS && !practice) {
                xyz.aezdek.hush.Wipe.now(context.applicationContext)
                Graph.prefs.disguiseSucceeded()
                onRight()
                return@launch
            }
            busy = false
            if (result == Disguise.Entered.RIGHT) {
                if (!practice) Graph.prefs.disguiseSucceeded()
                onRight()
                return@launch
            }
            if (!practice) Graph.prefs.disguiseFailed(System.currentTimeMillis())
            entered = ""
            wait = if (practice) 0 else Disguise.waitLeft()
            message = if (pin) "Wrong PIN" else "Wrong password"
            for (x in listOf(16f, -12f, 8f, -4f, 0f)) shake.animateTo(x, tween(50))
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Lock, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text(if (pin) "Enter PIN" else "Enter password", color = colors.onSurface, fontSize = 22.sp)
        Spacer(Modifier.height(20.dp))
        val note = when {
            wait > 0 -> "Too many tries. Try again in ${(wait + 999) / 1_000} s"
            busy -> "Checking…"
            else -> message
        }
        Text(note ?: " ", color = if (wait > 0 || note == "Wrong PIN" || note == "Wrong password") colors.error else colors.onSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        if (pin) {
            Row(
                Modifier.height(20.dp).offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                    .semantics { contentDescription = "${entered.length} digits entered" },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(entered.length) { Box(Modifier.size(12.dp).clip(CircleShape).background(colors.onSurface)) }
            }
            Spacer(Modifier.height(36.dp))
            Keypad(
                enabled = !busy && wait == 0L,
                onDigit = { if (entered.length < DisguiseSecret.MAX_PIN) entered += it },
                onDelete = { entered = entered.dropLast(1) },
                onEnter = ::submit,
            )
        } else {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = entered,
                onValueChange = { if (it.length <= DisguiseSecret.MAX_PASSWORD) entered = it },
                singleLine = true,
                enabled = !busy && wait == 0L,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().focusRequester(focus).offset { IntOffset(shake.value.dp.roundToPx(), 0) },
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = ::submit, enabled = !busy && wait == 0L && entered.isNotEmpty()) { Text("Unlock") }
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onEnter: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in listOf("123", "456", "789")) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (digit in row) Key(enabled, { onDigit(digit) }) { Text(digit.toString(), fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Key(enabled, onDelete, tonal = false) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Key(enabled, { onDigit('0') }) { Text("0", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface) }
            Key(enabled, onEnter, tonal = false) {
                Icon(Icons.Outlined.Check, "Unlock", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun Key(enabled: Boolean, onClick: () -> Unit, tonal: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape)
            .background(if (tonal) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
