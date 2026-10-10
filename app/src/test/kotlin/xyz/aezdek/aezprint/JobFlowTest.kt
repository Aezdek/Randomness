package xyz.aezdek.aezprint

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aezdek.aezprint.designsystem.theme.AezPrintTheme
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.feature.jobs.history.JobHistoryRoute
import xyz.aezdek.aezprint.feature.jobs.progress.JobProgressRoute

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class JobFlowTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var env: TestEnvironment
    private var opened: String? = null

    @Before
    fun setUp() {
        env = TestEnvironment(temp.newFolder())
    }

    @After
    fun tearDown() = env.close()

    private fun enqueue(options: PrintOptions): String =
        runBlocking { env.queue.enqueue(env.document, options) }.also { watched = it }

    private fun state(localId: String) = env.records.all.value.first { it.localId == localId }

    private fun showJob(localId: String) {
        val viewModel = env.jobViewModel(localId)
        compose.setContent {
            AezPrintTheme(dynamicColor = false) { JobProgressRoute(onBack = {}, onOpenJob = { opened = it }, viewModel = viewModel) }
        }
    }

    private var watched: String? = null

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) =
        compose.awaitReal(timeout, { "Record: ${watched?.let(::state)}" }, condition)

    private fun waitForTag(tag: String, timeout: Long = 15_000) =
        waitFor(timeout) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun manualDuplexPausesForTheFlipAndResumesOnContinue() {
        val localId = enqueue(PrintOptions(duplex = Duplex.LONG_EDGE))
        showJob(localId)

        waitForTag("continueAfterFlip")
        assertEquals(JobState.AWAITING_FLIP, state(localId).jobState)
        compose.onNodeWithText("Time to flip the stack").assertExists()

        compose.onNodeWithTag("continueAfterFlip").performClick()
        waitFor(15_000) { compose.onAllNodes(hasTestTag("jobOutcome").and(hasText("Printed"))).fetchSemanticsNodes().isNotEmpty() }
        val finished = state(localId)
        assertEquals(JobState.COMPLETED, finished.jobState)
        assertEquals(finished.sheetsTotal, finished.sheetsCompleted)
    }

    @Test
    fun cancelStopsAJobInProgress() {
        runBlocking { env.settings.updateFakeServer { it.copy(secondsPerSheet = 2.0) } }
        val localId = enqueue(PrintOptions(copies = 5))
        showJob(localId)

        waitFor(15_000) { state(localId).jobId != null }
        waitForTag("cancelJob")
        compose.onNodeWithTag("cancelJob").performClick()
        waitFor(15_000) { compose.onAllNodes(hasTestTag("jobOutcome").and(hasText("Canceled"))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(JobState.CANCELED, state(localId).jobState)
    }

    @Test
    fun historyReprintsWithOneTap() {
        val first = enqueue(PrintOptions(copies = 2))
        waitFor(15_000) { state(first).jobState == JobState.COMPLETED }

        val viewModel = env.historyViewModel()
        compose.setContent {
            AezPrintTheme(dynamicColor = false) { JobHistoryRoute(onBack = {}, onOpenJob = { opened = it }, viewModel = viewModel) }
        }
        waitForTag("reprint-$first")
        compose.onNodeWithTag("reprint-$first").performClick()
        waitFor(5_000) { opened != null }

        val reprint = checkNotNull(opened).also { watched = it }
        assertNotEquals(first, reprint)
        assertEquals(state(first).options, state(reprint).options)
        waitFor(15_000) { state(reprint).jobState == JobState.COMPLETED }
    }
}
