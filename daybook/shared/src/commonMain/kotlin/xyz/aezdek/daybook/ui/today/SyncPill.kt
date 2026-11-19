package xyz.aezdek.daybook.ui.today

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.aezdek.daybook.sync.SyncStatus
import xyz.aezdek.daybook.ui.components.AppleText
import xyz.aezdek.daybook.ui.components.SymbolIcon
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.AppleColors
import xyz.aezdek.daybook.ui.theme.Symbols
import xyz.aezdek.daybook.ui.theme.glass

data class SyncLook(val label: String, val symbol: ImageVector, val tint: Color, val spinning: Boolean)

/** [pending] is the number of local edits not yet accepted by the server. */
fun SyncStatus.look(colors: AppleColors, pending: Int = 0): SyncLook = when (this) {
    SyncStatus.NotConfigured -> SyncLook("Set Up Sync", Symbols.cloud, colors.accent, false)
    is SyncStatus.Synced ->
        if (pending > 0) SyncLook("Syncing", Symbols.syncArrows, colors.secondaryLabel, true)
        else SyncLook("Synced", Symbols.cloudCheck, colors.greenFill, false)
    is SyncStatus.Syncing -> SyncLook("Syncing", Symbols.syncArrows, colors.secondaryLabel, true)
    is SyncStatus.Offline -> SyncLook("Offline", Symbols.cloudSlash, colors.secondaryLabel, false)
    is SyncStatus.Failed -> SyncLook("Sync Error", Symbols.warning, colors.red, false)
}

/** Sync state in the toolbar: an icon and a word, never colour alone. Tapping it opens Settings. */
@Composable
fun SyncPill(status: SyncStatus, pending: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = status.look(Apple.colors, pending)
    val reduce = Apple.reduceMotion
    val rotation = if (look.spinning && !reduce) {
        val transition = rememberInfiniteTransition()
        val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart))
        angle
    } else 0f
    Row(
        modifier
            .height(44.dp)
            .glass(RoundedCornerShape(50))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Sync: ${look.label}. Opens sync settings." }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymbolIcon(look.symbol, null, Modifier.graphicsLayer { rotationZ = rotation }, tint = look.tint, size = 18.dp)
        AppleText(look.label, Apple.type.subheadSemibold, maxLines = 1)
    }
}

/** The same status as a plain row, for places that are already glass (the sidebar). */
@Composable
fun SyncStatusRow(status: SyncStatus, pending: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = status.look(Apple.colors, pending)
    Row(
        modifier
            .height(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Sync: ${look.label}. Opens sync settings." }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SymbolIcon(look.symbol, null, tint = look.tint, size = 18.dp)
        AppleText(look.label, Apple.type.subhead, color = Apple.colors.secondaryLabel, maxLines = 1)
    }
}
