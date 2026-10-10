package xyz.aezdek.aezprint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import xyz.aezdek.aezprint.designsystem.theme.AezPrintTheme
import xyz.aezdek.aezprint.domain.model.AppSettings
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import xyz.aezdek.aezprint.lock.AppLock
import xyz.aezdek.aezprint.lock.LockScreen
import xyz.aezdek.aezprint.navigation.AezPrintNavHost
import xyz.aezdek.aezprint.navigation.IncomingIntents
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appLock: AppLock

    private val intents = MutableSharedFlow<Intent>(replay = 1, extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.let(intents::tryEmit)

        val settingsFlow = settingsRepository.settings.map<AppSettings, AppSettings?> { it }
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val unlocked by appLock.unlocked.collectAsStateWithLifecycle()
            val current = settings
            if (current != null) AezPrintTheme(themeMode = current.themeMode, dynamicColor = current.dynamicColor) {
                if (current.biometricLock && !unlocked) {
                    LockScreen(this@MainActivity, onUnlocked = appLock::unlock)
                } else {
                    val navController = rememberNavController()
                    AezPrintNavHost(navController)
                    NotificationPermissionRequest()
                    LaunchedEffect(navController) {
                        intents.collect { incoming ->
                            IncomingIntents.destinationFor(incoming)?.let { navController.navigate(it) }
                            intents.resetReplayCache()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intents.tryEmit(intent)
    }

    @Composable
    private fun NotificationPermissionRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
