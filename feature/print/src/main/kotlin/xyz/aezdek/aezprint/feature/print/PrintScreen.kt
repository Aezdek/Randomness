@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.aezdek.aezprint.feature.print

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.designsystem.component.ErrorState
import xyz.aezdek.aezprint.designsystem.component.LoadingState
import xyz.aezdek.aezprint.designsystem.component.OfflineBanner
import xyz.aezdek.aezprint.designsystem.component.PrinterStatusPill
import xyz.aezdek.aezprint.designsystem.motion.sharedDocument
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.SizingMode
import xyz.aezdek.aezprint.feature.print.options.OptionsPanel
import xyz.aezdek.aezprint.feature.print.options.OptionsPanelActions
import xyz.aezdek.aezprint.feature.print.options.OptionsPanelState
import xyz.aezdek.aezprint.feature.print.preview.PreviewPane

@Composable
fun PrintRoute(
    sharedDocumentId: String?,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PrintViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PrintEvent.OpenJob -> onOpenJob(event.localId)
                is PrintEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }
    val actions = remember(viewModel) {
        OptionsPanelActions(
            onChange = viewModel::updateOptions,
            onPageSelection = viewModel::setPageSelection,
            onApplyPreset = viewModel::applyPreset,
            onSavePreset = viewModel::savePreset,
            onDeletePreset = viewModel::deletePreset,
            onSetDefaultPreset = viewModel::setDefaultPreset,
        )
    }
    PrintScreen(
        state = state,
        sharedDocumentId = sharedDocumentId ?: state.document?.id,
        actions = actions,
        snackbar = snackbar,
        loadImage = viewModel::sheetImage,
        onSelectFace = viewModel::selectFace,
        onTapPage = viewModel::tapPreview,
        onRetry = viewModel::retryPreview,
        onPrint = viewModel::print,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun PrintScreen(
    state: PrintUiState,
    sharedDocumentId: String?,
    actions: OptionsPanelActions,
    snackbar: SnackbarHostState,
    loadImage: suspend (String, Int, Int) -> ImageBitmap?,
    onSelectFace: (Int) -> Unit,
    onTapPage: (Int, Float, Float) -> Unit,
    onRetry: () -> Unit,
    onPrint: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val topBar = @Composable { PrintTopBar(state, onBack) }
    val panelState = state.document?.let { document ->
        OptionsPanelState(
            options = state.options,
            pageCount = document.pageCount,
            currentPage = state.currentPage,
            printer = state.printer,
            errors = state.errors,
            presets = state.presets,
            activePresetId = state.activePresetId,
        )
    }

    when {
        state.documentError != null -> Scaffold(topBar = topBar) { padding ->
            ErrorState(
                title = "Can't open this document",
                message = state.documentError,
                icon = Icons.Rounded.ErrorOutline,
                actionLabel = "Go back",
                onAction = onBack,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        }
        panelState == null -> Scaffold(topBar = topBar) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().then(sharedDocumentId?.let { Modifier.sharedDocument(it) } ?: Modifier)) {
                LoadingState("Opening document…")
            }
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val preview = @Composable { modifier: Modifier ->
                Column(modifier) {
                    OfflineBanner(!state.online)
                    PreviewPane(
                        state = state,
                        loadImage = loadImage,
                        onSelectFace = onSelectFace,
                        onTapPage = onTapPage,
                        onRetry = onRetry,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .weight(1f)
                            .then(sharedDocumentId?.let { Modifier.sharedDocument(it) } ?: Modifier),
                    )
                }
            }
            val panel = @Composable {
                OptionsPanel(
                    state = panelState,
                    actions = actions,
                    generalError = state.serverErrors[PrintViewModel.GENERAL_ERROR],
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }

            if (maxWidth >= 720.dp) {
                Scaffold(topBar = topBar, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Row(Modifier.padding(padding).fillMaxSize()) {
                        preview(Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider()
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(420.dp).fillMaxHeight()) {
                            Column {
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { panel() }
                                PrintBar(state, onPrint, Modifier.navigationBarsPadding())
                            }
                        }
                    }
                }
            } else {
                val scaffoldState = rememberBottomSheetScaffoldState()
                BottomSheetScaffold(
                    scaffoldState = scaffoldState,
                    topBar = topBar,
                    snackbarHost = { SnackbarHost(snackbar) },
                    sheetPeekHeight = 148.dp,
                    sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    sheetContent = {
                        PrintBar(state, onPrint)
                        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) { panel() }
                    },
                ) { padding ->
                    preview(Modifier.padding(padding).padding(bottom = 132.dp).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PrintTopBar(state: PrintUiState, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    state.document?.displayName ?: "Print",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                state.document?.let {
                    Text(
                        "${it.pageCount} ${if (it.pageCount == 1) "page" else "pages"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            state.printer?.let { PrinterStatusPill(it.status, Modifier.padding(end = 12.dp)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun PrintBar(state: PrintUiState, onPrint: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            AnimatedContent(targetState = state.totalSheets, label = "sheetTotal") { sheets ->
                Text(
                    if (sheets == 1) "1 sheet" else "$sheets sheets",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("sheetTotal"),
                )
            }
            Text(
                summary(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = onPrint,
            enabled = state.canPrint,
            modifier = Modifier.height(56.dp).testTag("printButton"),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Rounded.Print, null)
            }
            Spacer(Modifier.width(10.dp))
            Text("Print")
        }
    }
}

private fun summary(state: PrintUiState): String = buildList {
    if (state.options.copies > 1) add("${state.options.copies} copies")
    add(
        when {
            !state.options.isTwoSided -> "One-sided"
            state.options.duplex == Duplex.SHORT_EDGE || state.options.sizing.mode == SizingMode.BOOKLET -> "Two-sided, short edge"
            else -> "Two-sided, long edge"
        },
    )
    state.printer?.media?.firstOrNull { it.id == state.options.media }?.let { add(it.displayName) }
}.joinToString(" · ")
