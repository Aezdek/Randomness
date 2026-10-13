package xyz.aezdek.aria.watch

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
 * Screenshots of the real watch screens, drawn by Robolectric at Galaxy Watch size with example songs.
 * Run: ./gradlew :watch:recordRoborazziDebug  →  watch/build/screenshots/
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav", application = Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val songs = listOf(
        Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", "After Hours", 200.0),
        Track("FGBhQbmPwH8", "One More Time", "Daft Punk", "Discovery", 320.0),
        Track("tQ0yjYUFKAE", "Midnight City", "M83", "Hurry Up, We're Dreaming", 244.0),
        Track("5anLPw0Efmo", "Electric Feel", "MGMT", "Oracular Spectacular", 229.0),
        Track("lp-EO5I60KA", "Sunflower", "Post Malone, Swae Lee", "", 158.0),
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.init(context)
        Library.init(context)
        Prefs.serverUrl = "http://192.168.1.20:8765"
        Prefs.saved = songs
        songs.take(3).reversed().forEach(Library::add)
        Library.setProgress(songs[3].id, 0.62f)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalArtworkPreview provides ::art) { AriaWatchTheme(content) }
        }
        compose.onRoot().captureRoboImage("build/screenshots/watch-$name.png")
    }

    @Test fun home() = shot("1-home") { HomeScreen(onOpen = {}) }

    @Test fun player() = shot("2-player") {
        PlayerContent(
            track = songs[0], playing = true, buffering = false, positionMs = 83_000, durationMs = 200_000,
            volume = null, hasHeadphones = true, onPrevious = {}, onToggle = {}, onNext = {}, onEarbuds = {},
        )
    }

    @Test fun downloads() = shot("3-downloads") { DownloadsScreen(onPlayer = {}) }

    @Test fun saved() = shot("4-saved") { SavedScreen(onTrack = {}) }

    @Test fun search() = shot("5-search") { SearchScreen(onTrack = {}, initialQuery = "daft punk", initialResults = songs.drop(1)) }

    @Test fun song() = shot("6-song") { TrackScreen(songs[3], onPlayer = {}, onRemoved = {}) }

    @Test fun settings() = shot("7-settings") { SettingsScreen() }
}
