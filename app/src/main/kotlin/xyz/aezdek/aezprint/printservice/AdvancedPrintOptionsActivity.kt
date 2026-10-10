@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.aezdek.aezprint.printservice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.print.PrintDocumentInfo
import android.print.PrintJobInfo
import android.printservice.PrintService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.aezdek.aezprint.designsystem.component.LoadingState
import xyz.aezdek.aezprint.designsystem.theme.AezPrintTheme
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.feature.print.options.OptionsPanel
import xyz.aezdek.aezprint.feature.print.options.OptionsPanelActions
import xyz.aezdek.aezprint.feature.print.options.OptionsPanelState

/** Opened from "More options" in the system print dialog; exposes the full AezPrint option set. */
@AndroidEntryPoint
class AdvancedPrintOptionsActivity : ComponentActivity() {

    private val viewModel: AdvancedOptionsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val jobInfo = IntentCompat.getParcelableExtra(intent, PrintService.EXTRA_PRINT_JOB_INFO, PrintJobInfo::class.java)
        val documentInfo = IntentCompat.getParcelableExtra(intent, PrintService.EXTRA_PRINT_DOCUMENT_INFO, PrintDocumentInfo::class.java)
        val pageCount = documentInfo?.pageCount?.takeIf { it != PrintDocumentInfo.PAGE_COUNT_UNKNOWN } ?: UNKNOWN_PAGE_COUNT
        viewModel.initialize(jobInfo?.getAdvancedStringOption(SystemPrintMapping.OPTIONS_KEY), pageCount)

        val actions = OptionsPanelActions(
            onChange = viewModel::update,
            onPageSelection = { selection -> viewModel.update { it.copy(pages = selection) } },
            onApplyPreset = viewModel::applyPreset,
            onSavePreset = viewModel::savePreset,
            onDeletePreset = viewModel::deletePreset,
            onSetDefaultPreset = viewModel::setDefaultPreset,
        )

        setContent {
            AezPrintTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("AezPrint options") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) { Icon(Icons.Rounded.Close, contentDescription = "Discard changes") }
                            },
                            actions = { TextButton(onClick = { finishWith(jobInfo) }, enabled = state.errors.isEmpty()) { Text("Done") } },
                        )
                    },
                ) { padding ->
                    if (!state.ready) {
                        LoadingState("Loading options…", Modifier.padding(padding))
                    } else {
                        OptionsPanel(
                            state = OptionsPanelState(
                                options = state.options,
                                pageCount = state.pageCount,
                                currentPage = (state.options.pages as? PageSelection.Current)?.page ?: 1,
                                printer = state.printer,
                                errors = state.errors,
                                presets = state.presets,
                                activePresetId = state.activePresetId,
                            ),
                            actions = actions,
                            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }

    private fun finishWith(original: PrintJobInfo?) {
        if (original == null) {
            finish()
            return
        }
        val options = viewModel.state.value.options
        val updated = PrintJobInfo.Builder(original)
            .putAdvancedOption(SystemPrintMapping.OPTIONS_KEY, viewModel.encoded())
            .apply { setCopies(options.copies) }
            .build()
        setResult(Activity.RESULT_OK, Intent().putExtra(PrintService.EXTRA_PRINT_JOB_INFO, updated))
        finish()
    }

    private companion object {
        /** The spooler may not know the page count yet; validate ranges against a generous ceiling. */
        const val UNKNOWN_PAGE_COUNT = 9_999
    }
}
