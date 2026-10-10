package xyz.aezdek.aezprint.feature.print.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.designsystem.component.EmptyState
import xyz.aezdek.aezprint.designsystem.component.ErrorState
import xyz.aezdek.aezprint.feature.print.PrintUiState
import xyz.aezdek.aezprint.feature.print.RenderState
import xyz.aezdek.aezprint.feature.print.UploadState
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt

@Composable
fun PreviewPane(
    state: PrintUiState,
    loadImage: suspend (renderId: String, face: Int, widthPx: Int) -> ImageBitmap?,
    onSelectFace: (Int) -> Unit,
    onTapPage: (face: Int, fx: Float, fy: Float) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Column(modifier.padding(contentPadding)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.faceCount == 0 && state.document != null && state.localErrors.isNotEmpty() -> EmptyState(
                    icon = Icons.Rounded.CloudUpload,
                    title = "Nothing to print",
                    message = state.localErrors.first().message,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.faceCount == 0 -> Unit
                else -> SheetPager(state, loadImage, onSelectFace, onTapPage)
            }
            UploadOverlay(state.upload, onRetry, onOpenSettings, Modifier.align(Alignment.BottomCenter))
            val failed = state.render as? RenderState.Failed
            if (failed != null && state.shownRender == null && state.upload is UploadState.Uploaded) {
                ErrorState(
                    title = "Preview unavailable",
                    message = failed.message,
                    onAction = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        SheetScrubber(state, onSelectFace)
    }
}

@Composable
private fun SheetPager(
    state: PrintUiState,
    loadImage: suspend (String, Int, Int) -> ImageBitmap?,
    onSelectFace: (Int) -> Unit,
    onTapPage: (Int, Float, Float) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = state.currentFace) { state.faceCount }
    var zoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(state.currentFace, state.faceCount) {
        if (state.currentFace != pagerState.currentPage && state.currentFace < state.faceCount) {
            pagerState.animateScrollToPage(state.currentFace)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page -> onSelectFace(page) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        pageSpacing = 20.dp,
        userScrollEnabled = zoom <= 1.01f,
        key = { it },
    ) { index ->
        val face = state.faceGeometry(index)
        val shown = state.shownRender
        SheetView(
            index = index,
            face = face,
            renderId = shown?.render?.renderId?.takeIf { index < shown.render.faceCount },
            stale = !state.renderIsCurrent,
            loadImage = loadImage,
            onTap = { fx, fy -> onTapPage(index, fx, fy) },
            onZoomChange = { if (index == pagerState.currentPage) zoom = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SheetScrubber(state: PrintUiState, onSelectFace: (Int) -> Unit) {
    val count = state.faceCount
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        val face = state.faceGeometry(state.currentFace)
        val sheetNumber = face?.sheetNumber ?: 1
        val side = if (state.sides == 2 && face != null) if (face.isBack) " · back" else " · front" else ""
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = if (count == 0) "No sheets" else "Sheet $sheetNumber of ${state.sheetCount}$side",
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "sheetLabel",
            ) { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                paperSummary(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 1) {
            Slider(
                value = state.currentFace.toFloat(),
                onValueChange = { onSelectFace(it.roundToInt()) },
                valueRange = 0f..(count - 1).toFloat(),
                steps = (count - 2).coerceIn(0, 200),
                modifier = Modifier.semantics {
                    contentDescription = "Preview sheet"
                    stateDescription = "Side ${state.currentFace + 1} of $count"
                },
            )
        }
    }
}

private fun paperSummary(state: PrintUiState): String {
    val sheets = state.totalSheets
    val noun = if (sheets == 1) "sheet" else "sheets"
    return if (state.options.copies > 1) "$sheets $noun for ${state.options.copies} copies" else "$sheets $noun of paper"
}

@Composable
private fun UploadOverlay(upload: UploadState, onRetry: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(upload is UploadState.Uploading || upload is UploadState.Failed, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (upload) {
                    is UploadState.Uploading -> {
                        Text("Preparing preview…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is UploadState.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (upload.needsSetup) Icons.Rounded.Key else Icons.Rounded.CloudUpload, null, Modifier.size(20.dp))
                            Text(
                                "Live preview unavailable: ${upload.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                            )
                        }
                        Text(
                            "You can still print; the job will upload when the server is reachable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            if (upload.needsSetup) TextButton(onClick = onOpenSettings) { Text("Settings") }
                            Button(onClick = onRetry) { Text("Retry") }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
