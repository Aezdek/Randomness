package xyz.aezdek.hush

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Key
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.aezdek.hush.service.ConnectionService
import xyz.aezdek.hush.ui.CreateKind
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.Navigator
import xyz.aezdek.hush.ui.Screen
import xyz.aezdek.hush.ui.auth.Welcome
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.ToastHost
import xyz.aezdek.hush.ui.shell.Shell
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.AezTheme

/** The one activity for the app (calls have their own, so they can show over the lock screen). */
class MainActivity : FragmentActivity() {
    private val navigator = Navigator()
    private val unlocked = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !Graph.booted.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        // Lock again whenever the app goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && Graph.prefs.appLock.value) unlocked.value = false
        })
        setContent {
            val theme by Graph.prefs.theme.collectAsState()
            AezTheme(theme) {
                CompositionLocalProvider(LocalNav provides navigator) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Notification taps carry where to go (CI's smoke test opens every screen this way too). Only ever navigates. */
    private fun handle(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_OPEN) ?: return
        val (kind, id) = target.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
        val me = Graph.client.state.value.me?.id
        when (kind) {
            "home" -> navigator.go(Screen.Home)
            "chat" -> id?.let { navigator.go(Screen.Chat(it)) }
            "chat-info" -> id?.let { navigator.go(Screen.ThreadInfo(it)) }
            "activity" -> navigator.go(Screen.Activity)
            "post" -> id?.let { navigator.go(Screen.Post(it)) }
            "inbox" -> navigator.go(Screen.Inbox)
            "new-chat" -> navigator.go(Screen.NewChat)
            "explore" -> navigator.go(Screen.Explore())
            "search" -> navigator.go(Screen.Explore(search = true))
            "reels" -> navigator.go(Screen.Reels())
            "profile" -> (id ?: me)?.let { navigator.go(Screen.Profile(it)) }
            "edit-profile" -> navigator.go(Screen.EditProfile)
            "followers" -> (id ?: me)?.let { navigator.go(Screen.FollowList(it, followers = true)) }
            "stories" -> id?.let { navigator.go(Screen.Stories(listOf(it))) }
            "hashtag" -> id?.let { navigator.go(Screen.Hashtag(it)) }
            "verify" -> id?.let { navigator.go(Screen.Verify(it)) }
            "settings" -> navigator.go(Screen.Settings(id))
            "readiness" -> navigator.go(Screen.Readiness)
            "create" -> CreateKind.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }?.let { navigator.go(Screen.Create(it)) }
        }
    }

    @Composable
    private fun Root() {
        val booted by Graph.booted.collectAsState()
        val state by Graph.client.state.collectAsState()
        val appLock by Graph.prefs.appLock.collectAsState()
        val isUnlocked by unlocked.collectAsState()
        val weakPassword by Graph.prefs.weakPassword.collectAsState()
        Box(Modifier.fillMaxSize().background(Aez.colors.bg)) {
            when {
                !booted -> Unit
                state.me == null -> Welcome()
                appLock && !isUnlocked -> LockScreen { authenticate() }
                else -> {
                    SignedIn()
                    BackHandler(enabled = navigator.stack.size > 1 || navigator.current != Screen.Home) { navigator.back() }
                    Shell()
                    xyz.aezdek.hush.ui.settings.UpdatePrompt()
                    if (weakPassword && navigator.current != Screen.Settings("account")) {
                        WeakPasswordBanner(Modifier.align(Alignment.BottomCenter)) { navigator.go(Screen.Settings("account")) }
                    }
                }
            }
            ToastHost(Modifier.align(Alignment.BottomCenter))
            if (booted) LastCrash()
        }
    }

    /** Signed in with a password today's rules would refuse: asks for a better one until it changes. */
    @Composable
    private fun WeakPasswordBanner(modifier: Modifier, onChange: () -> Unit) {
        Row(
            modifier.navigationBarsPadding().padding(bottom = 70.dp, start = 12.dp, end = 12.dp)
                .clip(RoundedCornerShape(50)).background(Aez.colors.elev3).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Key, null, tint = Aez.colors.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Your password is easy to guess.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Aez.colors.text)
            Spacer(Modifier.width(10.dp))
            Text(
                "Change it", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Aez.colors.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Aez.colors.primary).clickable(onClick = onChange)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    /** After Hush closed unexpectedly: what happened, ready to copy and pass on to whoever can fix it. */
    @Composable
    private fun LastCrash() {
        var report by remember { mutableStateOf(CrashLog.pending()?.takeIf { it.startsWith("Crash") }) }
        val text = report ?: return
        val context = LocalContext.current
        fun dismiss() {
            CrashLog.clear()
            report = null
        }
        AlertDialog(
            onDismissRequest = ::dismiss,
            containerColor = Aez.colors.elev1,
            title = { Text("Hush closed unexpectedly") },
            text = {
                Column {
                    Text(
                        "Sorry about that. The details below say where it went wrong, and nothing about your messages or account. " +
                            "Copy them and send them to whoever runs your server, or include them in a bug report.",
                        style = MaterialTheme.typography.bodyMedium, color = Aez.colors.text2,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text.lineSequence().take(14).joinToString("\n"),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = Aez.colors.text3,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton({
                    (context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hush crash report", text))
                    Graph.toast("Copied")
                    dismiss()
                }) { Text("Copy details", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(::dismiss) { Text("Close") } },
        )
    }

    @Composable
    private fun SignedIn() {
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            ConnectionService.start(this@MainActivity)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @Composable
    private fun LockScreen(onUnlock: () -> Unit) {
        LaunchedEffect(Unit) { onUnlock() }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Lock, null, tint = Aez.colors.text, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Hush is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            AezButton("Unlock", onUnlock)
        }
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked.value = true
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked.value = true
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Hush").setAllowedAuthenticators(authenticators).build(),
        )
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
