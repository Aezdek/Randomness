@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.aezdek.aezprint.feature.jobs.progress

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.designsystem.component.EmptyState
import xyz.aezdek.aezprint.designsystem.component.FlipIllustration
import xyz.aezdek.aezprint.designsystem.component.LoadingState
import xyz.aezdek.aezprint.designsystem.component.OfflineBanner
import xyz.aezdek.aezprint.designsystem.component.PaperFeedAnimation
import xyz.aezdek.aezprint.designsystem.format.Formatting
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.PrintPhase
import xyz.aezdek.aezprint.domain.model.PrintRecord

private enum class Stage { UPLOADING, SENDING, QUEUED, PRINTING, FLIP, DONE, CANCELED, FAILED }

private val PrintRecord.stage: Stage
    get() = when {
        phase == PrintPhase.FAILED || jobState == JobState.FAILED -> Stage.FAILED
        phase == PrintPhase.CANCELED || jobState == JobState.CANCELED -> Stage.CANCELED
        phase == PrintPhase.UPLOADING -> Stage.UPLOADING
        phase == PrintPhase.SUBMITTING -> Stage.SENDING
        jobState == JobState.AWAITING_FLIP -> Stage.FLIP
        jobState == JobState.PROCESSING -> Stage.PRINTING
        jobState == JobState.COMPLETED -> Stage.DONE
        else -> Stage.QUEUED
    }

@Composable
fun JobProgressRoute(
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
    viewModel: JobProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is JobEvent.OpenJob -> onOpenJob(event.localId)
                is JobEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }
    JobProgressScreen(state, snackbar, onBack, viewModel::cancel, viewModel::continueAfterFlip, viewModel::reprint)
}

@Composable
fun JobProgressScreen(
    state: JobProgressUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onReprint: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.record?.documentName ?: "Print job", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val record = state.record
        when {
            state.loading -> LoadingState("Loading job…", Modifier.padding(padding))
            record == null -> EmptyState(
                icon = Icons.Rounded.ErrorOutline,
                title = "Job not found",
                message = "It may have been cleared from history.",
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OfflineBanner(!state.online && record.isActive)
                AnimatedContent(
                    targetState = record.stage,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                    label = "jobStage",
                    modifier = Modifier.widthIn(max = 560.dp).padding(24.dp),
                ) { stage ->
                    when (stage) {
                        Stage.FLIP -> FlipStep(record, state.busy, onContinue, onCancel)
                        Stage.DONE, Stage.CANCELED, Stage.FAILED -> Finished(record, stage, state.busy, onReprint)
                        else -> InProgress(record, stage, state.busy, onCancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun InProgress(record: PrintRecord, stage: Stage, busy: Boolean, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val progress = when (stage) {
            Stage.UPLOADING -> record.uploadProgress * 0.15f
            Stage.PRINTING -> if (record.sheetsTotal > 0) record.sheetsCompleted.toFloat() / record.sheetsTotal else 0f
            else -> 0f
        }
        PaperFeedAnimation(active = stage == Stage.PRINTING, progress = progress, modifier = Modifier.fillMaxWidth(0.8f))
        Text(
            when (stage) {
                Stage.UPLOADING -> "Uploading…"
                Stage.SENDING -> "Sending to the printer…"
                Stage.QUEUED -> "Waiting for the printer…"
                else -> "Printing"
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        val animated by animateFloatAsState(progress, label = "jobProgress")
        when (stage) {
            Stage.UPLOADING -> LinearProgressIndicator(progress = { record.uploadProgress }, modifier = Modifier.fillMaxWidth())
            Stage.PRINTING -> LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().testTag("jobProgress"))
            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val unit = if (record.options.isTwoSided) "sides" else "sheets"
        Text(
            when {
                stage == Stage.UPLOADING -> "${(record.uploadProgress * 100).toInt()}%"
                record.sheetsTotal > 0 -> "${record.sheetsCompleted} of ${record.sheetsTotal} $unit"
                else -> Formatting.optionsSummary(record.options)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        record.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.height(48.dp).testTag("cancelJob")) {
            Icon(Icons.Rounded.Close, null)
            Spacer(Modifier.size(8.dp))
            Text("Cancel print")
        }
    }
}

@Composable
private fun FlipStep(record: PrintRecord, busy: Boolean, onContinue: () -> Unit, onCancel: () -> Unit) {
    val edge = Formatting.flipEdge(record.options)
    val edgeName = if (edge == Duplex.SHORT_EDGE) "short" else "long"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Time to flip the stack",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Assertive },
        )
        Text(
            "The fronts are done (${record.sheetsCompleted} of ${record.sheetsTotal} sides). The printer is waiting for you.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            FlipIllustration(duplex = edge, modifier = Modifier.padding(16.dp))
        }
        listOf(
            "Take the printed stack out of the output tray. Keep the pages in order.",
            "Turn the whole stack over along its $edgeName edge, like the animation.",
            "Put it back in the paper tray and press Continue.",
        ).forEachIndexed { index, text -> Step(index + 1, text) }
        record.message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Button(onClick = onContinue, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("continueAfterFlip")) {
            Text("I've flipped it, continue")
        }
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Cancel print")
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
            Text(
                "$number",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Finished(record: PrintRecord, stage: Stage, busy: Boolean, onReprint: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val (icon, title, tint) = when (stage) {
            Stage.DONE -> Triple(Icons.Rounded.CheckCircle, "Printed", MaterialTheme.colorScheme.primary)
            Stage.CANCELED -> Triple(Icons.Rounded.Close, "Canceled", MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Triple(Icons.Rounded.ErrorOutline, "Didn't print", MaterialTheme.colorScheme.error)
        }
        val scale by animateFloatAsState(1f, label = "doneScale")
        Icon(icon, null, tint = tint, modifier = Modifier.size((88 * scale).dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }.testTag("jobOutcome"),
        )
        Text(Formatting.optionsSummary(record.options), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        record.message?.takeIf { stage != Stage.DONE }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Button(onClick = onReprint, enabled = !busy, modifier = Modifier.height(48.dp).testTag("reprint")) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.size(8.dp))
            Text(if (stage == Stage.DONE) "Print again" else "Try again")
        }
    }
}
