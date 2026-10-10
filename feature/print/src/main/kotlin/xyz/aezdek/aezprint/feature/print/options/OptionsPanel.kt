package xyz.aezdek.aezprint.feature.print.options

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PresetLibrary
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer

/** Everything the panel needs to render; shared by the print screen and the PrintService advanced options. */
data class OptionsPanelState(
    val options: PrintOptions,
    val pageCount: Int,
    val currentPage: Int,
    val printer: Printer?,
    val errors: Map<String, String>,
    val presets: PresetLibrary,
    val activePresetId: String?,
)

class OptionsPanelActions(
    val onChange: ((PrintOptions) -> PrintOptions) -> Unit,
    val onPageSelection: (PageSelection) -> Unit,
    val onApplyPreset: (String) -> Unit,
    val onSavePreset: (name: String, makeDefault: Boolean) -> Unit,
    val onDeletePreset: (String) -> Unit,
    val onSetDefaultPreset: (String?) -> Unit,
)

@Composable
fun OptionsPanel(
    state: OptionsPanelState,
    actions: OptionsPanelActions,
    modifier: Modifier = Modifier,
    generalError: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).animateContentSize()) {
        if (generalError != null) {
            ListItem(
                headlineContent = { Text(generalError) },
                leadingContent = { Icon(Icons.Rounded.Error, null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.errorContainer),
            )
        }
        PresetSection(state, actions)
        Divider()
        CopiesSection(state.options, state.errors, actions.onChange)
        Divider()
        PagesSection(state, actions)
        Divider()
        SizingSection(state, actions.onChange)
        Divider()
        OrientationSection(state.options, state.errors, actions.onChange)
        Divider()
        PaperSection(state, actions.onChange)
        Divider()
        DuplexSection(state, actions.onChange)
        Divider()
        ContentSection(state.options, state.errors, actions.onChange)
        Divider()
        QualitySection(state, actions.onChange)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
