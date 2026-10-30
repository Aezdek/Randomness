package xyz.aezdek.hush.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.aezdek.hush.client.AezClient
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.data.ThemeMode
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.ui.auth.Welcome
import xyz.aezdek.hush.ui.create.CreateNote
import xyz.aezdek.hush.ui.settings.Readiness
import xyz.aezdek.hush.ui.settings.Settings
import xyz.aezdek.hush.ui.shell.Shell
import xyz.aezdek.hush.ui.stories.StoryViewer
import xyz.aezdek.hush.ui.theme.AezTheme

/**
 * Renders the main screens with realistic content into build/screenshots/ (uploaded by CI) so the Android UI gets the
 * same visual review as the web app's Playwright screenshots. Records only; nothing is compared.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var client: AezClient

    @Before
    fun setUp() {
        client = Fixtures.install(context)
    }

    private fun shot(name: String, screen: Screen? = null, mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit = { Shell() }) {
        val nav = Navigator()
        if (screen != null) nav.root(screen)
        captureRoboImage(filePath = "build/screenshots/$name.png") {
            AezTheme(mode) {
                CompositionLocalProvider(LocalNav provides nav) { content() }
            }
        }
    }

    private fun postBy(author: String) = client.index.value.posts.first { it.dto.authorId == author && it.dto.kind == Kind.POST }.id

    @Test
    fun welcome() {
        client.state.value = AppState()
        shot("01-welcome") { Welcome() }
    }

    @Test
    fun home() = shot("02-home")

    @Test
    fun homeLight() = shot("02-home-light", mode = ThemeMode.LIGHT)

    @Test
    fun post() = shot("03-post", Screen.Post(postBy(Fixtures.MAYA)))

    @Test
    fun profile() = shot("04-profile", Screen.Profile(Fixtures.ME))

    @Test
    fun profileLight() = shot("04-profile-light", Screen.Profile(Fixtures.ME), ThemeMode.LIGHT)

    @Test
    fun otherProfile() = shot("05-profile-other", Screen.Profile(Fixtures.MAYA))

    @Test
    fun explore() = shot("06-explore", Screen.Explore())

    @Test
    fun activity() = shot("07-activity", Screen.Activity)

    @Test
    fun inbox() = shot("08-inbox", Screen.Inbox)

    @Test
    fun chat() = shot("09-chat", Screen.Chat(Fixtures.DM))

    @Test
    fun chatLight() = shot("09-chat-light", Screen.Chat(Fixtures.DM), ThemeMode.LIGHT)

    @Test
    fun story() = shot("10-story") { StoryViewer(listOf(Fixtures.MAYA), 0) }

    @Test
    fun note() = shot("11-note") { CreateNote() }

    @Test
    fun settings() = shot("12-settings") { Settings(null) }

    @Test
    fun readiness() = shot("13-readiness") { Readiness() }
}
