package xyz.aezdek.aezprint

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aezdek.aezprint.designsystem.theme.AezPrintTheme
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.feature.print.PrintRoute
import xyz.aezdek.aezprint.feature.print.PrintViewModel
import xyz.aezdek.aezprint.feature.print.RenderState

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1000dp-h1400dp-xlarge-port")
class PrintFlowTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var env: TestEnvironment
    private lateinit var viewModel: PrintViewModel
    private var openedJob: String? = null

    private fun launch() {
        env = TestEnvironment(temp.newFolder())
        viewModel = env.printViewModel()
        compose.setContent {
            AezPrintTheme(dynamicColor = false) {
                PrintRoute(sharedDocumentId = null, onBack = {}, onOpenJob = { openedJob = it }, onOpenSettings = {}, viewModel = viewModel)
            }
        }
        waitForCurrentRender()
    }

    @After
    fun tearDown() = env.close()

    private fun waitForCurrentRender() {
        compose.awaitReal(10_000, { describe() }) {
            val state = viewModel.state.value
            state.printer != null && (state.render as? RenderState.Ready)?.options == state.options
        }
    }

    private fun sheetTotal(text: String) {
        compose.awaitReal(10_000, { describe() }) {
            compose.onAllNodes(hasTestTag("sheetTotal").and(hasText(text))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Activates a control the way TalkBack does, independent of where the long panel is scrolled. */
    private fun SemanticsNodeInteraction.activate() {
        performScrollTo()
        performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun describe(): String {
        val state = viewModel.state.value
        return "options=${state.options} render=${state.render} sheets=${state.sheetCount} errors=${state.errors}"
    }

    @Test
    fun previewRendersFromTheServerAndShowsPaperUsage() {
        launch()
        sheetTotal("3 sheets")
        val render = (viewModel.state.value.render as RenderState.Ready).render
        assertEquals(3, render.sheetCount)
        compose.onNodeWithTag("printButton").assertIsEnabled()
    }

    @Test
    fun everyOptionGroupUpdatesThePreview() {
        launch()

        compose.onNodeWithText("Multiple").activate()
        waitForCurrentRender()
        sheetTotal("2 sheets")

        compose.onNodeWithText("Booklet").activate()
        waitForCurrentRender()
        sheetTotal("1 sheet")
        assertEquals(Duplex.SHORT_EDGE, viewModel.state.value.options.duplex)

        compose.onNodeWithText("Poster").activate()
        waitForCurrentRender()
        assertTrue(viewModel.state.value.sheetCount >= 3)

        compose.onNodeWithText("Fit").activate()
        compose.onNodeWithText("Custom").activate()
        compose.onNodeWithTag("pageRangeField").performScrollTo().performTextReplacement("1-2")
        compose.onNodeWithTag("pageRangeField").performImeAction()
        waitForCurrentRender()
        sheetTotal("2 sheets")
        assertEquals(PageSelection.Custom("1-2"), viewModel.state.value.options.pages)

        compose.waitForIdle()
        compose.onNodeWithText("Flip on long edge").activate()
        compose.awaitReal(5_000, { describe() }) { viewModel.state.value.options.duplex == Duplex.LONG_EDGE }
        waitForCurrentRender()
        sheetTotal("1 sheet")

        compose.onNodeWithText("Landscape").activate()
        compose.onNodeWithText("Letter").activate()
        compose.onNodeWithText("Form fields only").activate()
        compose.onNodeWithText("Save toner").activate()
        compose.onNodeWithText("Print as image").activate()
        compose.onNodeWithText("1200 dpi").activate()
        compose.onNodeWithText("Reverse pages").activate()
        compose.onNode(hasContentDescription("More copies")).activate()
        waitForCurrentRender()

        val options = viewModel.state.value.options
        assertEquals("na_letter_8.5x11in", options.media)
        assertTrue(options.tonerSave && options.printAsImage && options.reverse)
        assertEquals(1200, options.dpi)
        assertEquals(2, options.copies)
        assertEquals(SizingMode.FIT, options.sizing.mode)
        sheetTotal("2 sheets")
    }

    @Test
    fun invalidPageRangeIsFlaggedInlineAndBlocksPrinting() {
        launch()
        compose.onNodeWithText("Custom").performScrollTo().performClick()
        compose.onNodeWithTag("pageRangeField").performScrollTo().performTextReplacement("2-9")
        compose.awaitReal(5_000) { viewModel.state.value.errors.isNotEmpty() }
        compose.onNodeWithTag("pageRangeField").assert(hasText("Page 9 doesn't exist; this document has 3 pages"))
        compose.onNodeWithTag("printButton").assertExists()
        assertTrue(!viewModel.state.value.canPrint)
    }

    @Test
    fun serverRejectionsAppearNextToTheControl() {
        launch()
        runBlocking { env.settings.updateFakeServer { it.copy(rejectPosterOptions = true) } }
        compose.onNodeWithText("Poster").performScrollTo().performClick()
        compose.awaitReal(10_000) { viewModel.state.value.serverErrors.isNotEmpty() }
        compose.onAllNodesWithText("Poster tiling isn't supported for this paper").onFirst().performScrollTo().assertExists()
    }

    @Test
    fun printingQueuesAJobThatRunsToCompletion() {
        launch()
        compose.onNodeWithTag("printButton").performClick()
        compose.awaitReal(5_000) { openedJob != null }
        val localId = checkNotNull(openedJob)
        compose.awaitReal(15_000, { "${env.records.all.value}" }) {
            env.records.all.value.firstOrNull { it.localId == localId }?.jobState == JobState.COMPLETED
        }
    }
}
