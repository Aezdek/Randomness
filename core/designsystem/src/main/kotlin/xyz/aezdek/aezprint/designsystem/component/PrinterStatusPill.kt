package xyz.aezdek.aezprint.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.aezdek.aezprint.domain.model.PrinterStatus

data class StatusStyle(val label: String, val icon: ImageVector, val container: Color, val content: Color)

@Composable
fun PrinterStatus.style(): StatusStyle {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        PrinterStatus.IDLE -> StatusStyle("Ready", Icons.Rounded.CheckCircle, scheme.secondaryContainer, scheme.onSecondaryContainer)
        PrinterStatus.PRINTING -> StatusStyle("Printing", Icons.Rounded.Print, scheme.primaryContainer, scheme.onPrimaryContainer)
        PrinterStatus.OFFLINE -> StatusStyle("Offline", Icons.Rounded.CloudOff, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        PrinterStatus.OUT_OF_PAPER -> StatusStyle("Out of paper", Icons.Rounded.Description, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        PrinterStatus.JAM -> StatusStyle("Paper jam", Icons.Rounded.ReportProblem, scheme.errorContainer, scheme.onErrorContainer)
        PrinterStatus.ERROR -> StatusStyle("Needs attention", Icons.Rounded.ErrorOutline, scheme.errorContainer, scheme.onErrorContainer)
    }
}

@Composable
fun PrinterStatusPill(status: PrinterStatus, modifier: Modifier = Modifier) {
    val style = status.style()
    val container by animateColorAsState(style.container, label = "statusContainer")
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = CircleShape,
        color = container,
        contentColor = style.content,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status == PrinterStatus.PRINTING) PulsingDot(style.content) else Icon(style.icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(style.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).alpha(alpha).background(color, CircleShape))
    }
}
