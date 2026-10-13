package xyz.aezdek.aria.phone

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.aezdek.aria.core.Track
import kotlin.math.abs

/**
 * Screenshots of the real phone screens with example songs.
 * Run: ./gradlew :phone:recordRoborazziDebug  →  phone/build/screenshots/
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-normal-long-notround-any-xxhdpi-keyshidden-nonav", application = Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val songs = listOf(
        Track("FGBhQbmPwH8", "One More Time", "Daft Punk", "Discovery", 320.0),
        Track("5NV6Rdv1a3I", "Get Lucky", "Daft Punk, Pharrell Williams", "Random Access Memories", 369.0),
        Track("gAjR4_CbPpQ", "Harder, Better, Faster, Stronger", "Daft Punk", "Discovery", 224.0),
        Track("yydNF8tuVmU", "Around the World", "Daft Punk", "Homework", 429.0),
        Track("QOngRDVtEQI", "Instant Crush", "Daft Punk, Julian Casablancas", "Random Access Memories", 337.0),
        Track("IluRBvnYMoY", "Digital Love", "Daft Punk", "Discovery", 301.0),
    )

    private val states = mapOf(
        songs[0].id to SendState("On your watch", 1f, done = true),
        songs[1].id to SendState("Downloading", 0.45f, title = songs[1].title),
        songs[2].id to SendState("Sending to watch", 1f),
    )

    private val gradients = listOf(
        0xFFFF5478 to 0xFF7A40FF, 0xFFFFB347 to 0xFFFF3B6B, 0xFF30D5C8 to 0xFF3A47D5,
        0xFF8E2DE2 to 0xFF4A00E0, 0xFFF7971E to 0xFFFFD200,
    )

    private fun art(track: Track): Painter {
        val (a, b) = gradients[abs(track.id.hashCode()) % gradients.size]
        return BrushPainter(Brush.linearGradient(listOf(Color(a), Color(b))))
    }

    @Before
    fun setUp() {
        PhonePrefs.init(ApplicationProvider.getApplicationContext<Context>())
        PhonePrefs.serverUrl = "http://192.168.1.20:8765"
        YtDlp.status.value = "yt-dlp 2026.08.19"
    }

    private fun shot(name: String, tab: Int, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalArtworkPreview provides ::art) {
                AriaPhoneTheme { PhoneUi(startTab = tab, states = states) { content() } }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/phone-$name.png")
    }

    @Test fun search() = shot("1-search", 0) { SearchTab(initialQuery = "daft punk", initialResults = songs, states = states) }

    @Test fun saved() = shot("2-saved", 1) { SavedTab(initialSaved = songs.take(4), states = states) }

    @Test fun settings() = shot("3-settings", 2) { SettingsTab(initialWatchStatus = "Connected to Galaxy Watch8 Classic") }
}
