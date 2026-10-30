package xyz.aezdek.hush

import android.Manifest
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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

    /** Notification taps carry where to go. */
    private fun handle(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_OPEN) ?: return
        val (kind, id) = target.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
        when (kind) {
            "chat" -> id?.let { navigator.go(Screen.Chat(it)) }
            "activity" -> navigator.go(Screen.Activity)
            "post" -> id?.let { navigator.go(Screen.Post(it)) }
            "inbox" -> navigator.go(Screen.Inbox)
            "readiness" -> navigator.go(Screen.Readiness)
        }
    }

    @Composable
    private fun Root() {
        val booted by Graph.booted.collectAsState()
        val state by Graph.client.state.collectAsState()
        val appLock by Graph.prefs.appLock.collectAsState()
        val isUnlocked by unlocked.collectAsState()
        Box(Modifier.fillMaxSize().background(Aez.colors.bg)) {
            when {
                !booted -> Unit
                state.me == null -> Welcome()
                appLock && !isUnlocked -> LockScreen { authenticate() }
                else -> {
                    SignedIn()
                    BackHandler(enabled = navigator.stack.size > 1 || navigator.current != Screen.Home) { navigator.back() }
                    Shell()
                }
            }
            ToastHost(Modifier.align(Alignment.BottomCenter))
        }
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
