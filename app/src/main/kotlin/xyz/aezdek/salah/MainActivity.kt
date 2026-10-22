package xyz.aezdek.salah

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aezdek.salah.core.Settings
import xyz.aezdek.salah.ui.SalahRoot
import xyz.aezdek.salah.ui.rememberToday
import xyz.aezdek.salah.ui.theme.SalahTheme
import xyz.aezdek.salah.ui.theme.isDark
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val data by viewModel.data.collectAsStateWithLifecycle()
            val today = rememberToday()
            val settings = data?.settings ?: Settings()
            val dark = settings.isDark()
            // The app can be dark while the system is light (or the reverse), so the bar icons follow the app.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { dark },
                )
                onDispose {}
            }
            SalahTheme(settings) {
                val loaded = data
                if (loaded == null) {
                    Surface(Modifier.fillMaxSize()) {}
                } else {
                    SalahRoot(
                        data = loaded,
                        today = today,
                        onEdit = viewModel::edit,
                        backupText = { viewModel.backupText(LocalDateTime.now().withNano(0).toString()) },
                        restore = { viewModel.restore(it) },
                    )
                }
            }
        }
    }
}

private val LightScrim = Color.argb(0xe6, 0xff, 0xff, 0xff)
private val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
