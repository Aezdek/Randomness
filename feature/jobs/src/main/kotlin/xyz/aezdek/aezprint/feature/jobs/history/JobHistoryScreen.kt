@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.aezdek.aezprint.feature.jobs.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.aezdek.aezprint.designsystem.component.EmptyState
import xyz.aezdek.aezprint.designsystem.component.LoadingState
import xyz.aezdek.aezprint.designsystem.format.Formatting
import xyz.aezdek.aezprint.feature.jobs.progress.JobEvent

@Composable
fun JobHistoryRoute(
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
    viewModel: JobHistoryViewModel = hiltViewModel(),
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
    JobHistoryScreen(state, snackbar, onBack, onOpenJob, viewModel::reprint)
}

@Composable
fun JobHistoryScreen(
    state: JobHistoryUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
    onReprint: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingState("Loading history…", Modifier.padding(padding))
            state.records.isEmpty() -> EmptyState(
                icon = Icons.Rounded.History,
                title = "No prints yet",
                message = "Everything you print shows up here, ready to print again.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(state.records, key = { it.localId }) { record ->
                    ListItem(
                        headlineContent = { Text(record.documentName, maxLines = 1) },
                        supportingContent = {
                            Text(
                                "${Formatting.status(record)} · ${Formatting.relativeTime(record.createdAt)}\n${Formatting.optionsSummary(record.options)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            FilledTonalIconButton(
                                onClick = { onReprint(record.localId) },
                                modifier = Modifier.testTag("reprint-${record.localId}"),
                            ) { Icon(Icons.Rounded.Refresh, contentDescription = "Print ${record.documentName} again") }
                        },
                        modifier = Modifier.animateItem().clickable { onOpenJob(record.localId) },
                    )
                }
            }
        }
    }
}

