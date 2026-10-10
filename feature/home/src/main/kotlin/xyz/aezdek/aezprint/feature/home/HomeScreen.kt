@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package xyz.aezdek.aezprint.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aezdek.aezprint.designsystem.component.EmptyState
import xyz.aezdek.aezprint.designsystem.component.OfflineBanner
import xyz.aezdek.aezprint.designsystem.component.PrinterStatusPill
import xyz.aezdek.aezprint.designsystem.component.rememberFileBitmap
import xyz.aezdek.aezprint.designsystem.format.Formatting
import xyz.aezdek.aezprint.designsystem.motion.sharedDocument
import xyz.aezdek.aezprint.designsystem.theme.PaperWhite
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.PrintRecord

@Composable
fun HomeRoute(
    onOpenUri: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenUri(uri.toString())
    }
    HomeScreen(
        state = state,
        onPickFile = { picker.launch(arrayOf("application/pdf")) },
        onOpenDocument = onOpenDocument,
        onRemoveRecent = viewModel::removeRecent,
        onOpenJob = onOpenJob,
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
        onRetryPrinter = viewModel::retryPrinter,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onPickFile: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryPrinter: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("AezPrint") },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Rounded.History, contentDescription = "Print history") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPickFile,
                icon = { Icon(Icons.Rounded.NoteAdd, null) },
                text = { Text("Print a PDF") },
                expanded = scrollBehavior.state.collapsedFraction < 0.5f,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            fullWidth { OfflineBanner(!state.online, Modifier.padding(bottom = 4.dp)) }
            fullWidth { PrinterCard(state, onRetryPrinter, onOpenSettings) }
            if (state.activeJobs.isNotEmpty()) {
                fullWidth { SectionTitle("In progress") }
                state.activeJobs.forEach { record -> fullWidth { ActiveJobCard(record, onOpenJob) } }
            }
            fullWidth { SectionTitle("Recent documents") }
            if (state.recents.isEmpty() && !state.loading) {
                fullWidth {
                    EmptyState(
                        icon = Icons.Rounded.Description,
                        title = "No documents yet",
                        message = "Pick a PDF, share one from another app, or print from any app's print menu.",
                        action = { Button(onClick = onPickFile) { Text("Choose a PDF") } },
                    )
                }
            }
            items(state.recents, key = { it.id }) { document ->
                RecentDocumentCard(document, onOpenDocument, onRemoveRecent, Modifier.animateItem())
            }
            if (state.recentJobs.isNotEmpty()) {
                fullWidth {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Recent prints", Modifier.weight(1f))
                        TextButton(onClick = onOpenHistory) { Text("See all") }
                    }
                }
                state.recentJobs.forEach { record -> fullWidth { RecentJobRow(record, onOpenJob) } }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun PrinterCard(state: HomeUiState, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = state.printer,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "printerCard",
        ) { printer ->
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Print, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (printer) {
                        PrinterCardState.Loading -> {
                            Text("Checking printer…", style = MaterialTheme.typography.titleMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
                        }
                        is PrinterCardState.Ready -> {
                            Text(printer.printer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PrinterStatusPill(printer.printer.status)
                                if (state.usingFakeServer) FakeChip()
                            }
                            statusHint(printer.printer.status)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is PrinterCardState.Unreachable -> {
                            Text(if (printer.needsSetup) "Connect your server" else "Printer unreachable", style = MaterialTheme.typography.titleMedium)
                            Text(printer.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (printer.needsSetup) {
                                    Button(onClick = onOpenSettings) {
                                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Set up")
                                    }
                                }
                                OutlinedButton(onClick = onRetry) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FakeChip() {
    AssistChip(
        onClick = {},
        label = { Text("Fake server") },
        leadingIcon = { Icon(Icons.Rounded.Science, null, Modifier.size(16.dp)) },
        modifier = Modifier.semantics { contentDescription = "Using the built-in fake server" },
    )
}

private fun statusHint(status: PrinterStatus): String? = when (status) {
    PrinterStatus.OUT_OF_PAPER -> "Load paper in the tray; queued jobs will continue."
    PrinterStatus.JAM -> "Open the printer and clear the jam."
    PrinterStatus.OFFLINE -> "Check the printer is switched on and connected to the server."
    PrinterStatus.ERROR -> "The printer reported a problem."
    else -> null
}

@Composable
private fun ActiveJobCard(record: PrintRecord, onOpenJob: (String) -> Unit) {
    Card(
        onClick = { onOpenJob(record.localId) },
        colors = CardDefaults.cardColors(
            containerColor = if (record.awaitingFlip) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(record.documentName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (record.awaitingFlip) "Flip the stack to continue" else Formatting.status(record),
                    style = MaterialTheme.typography.bodySmall,
                )
                val progress = if (record.sheetsTotal > 0) record.sheetsCompleted.toFloat() / record.sheetsTotal else null
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Open job")
        }
    }
}

@Composable
private fun RecentDocumentCard(
    document: LocalDocument,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val thumbnail by rememberFileBitmap(document.thumbnailPath)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = "Print",
                onLongClickLabel = "More options",
                onClick = { onOpen(document.id) },
                onLongClick = { menu = true },
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = PaperWhite,
                shadowElevation = 3.dp,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.sharedDocument(document.id).fillMaxSize(),
            ) {
                thumbnail?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    document.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${document.displayName}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Remove from recents") }, onClick = {
                            menu = false
                            onRemove(document.id)
                        })
                    }
                }
            }
            Text(
                "${document.pageCount} ${if (document.pageCount == 1) "page" else "pages"} · ${Formatting.relativeTime(document.lastOpenedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentJobRow(record: PrintRecord, onOpenJob: (String) -> Unit) {
    Card(onClick = { onOpenJob(record.localId) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.documentName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Formatting.status(record)} · ${Formatting.relativeTime(record.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
