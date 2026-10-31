package xyz.aezdek.hush.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.ui.LocalNav
import xyz.aezdek.hush.ui.theme.Aez

/** The glass top bar every pushed screen uses: back, a title, and optional actions on the right. */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    back: Boolean = true,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalNav.current
    Column(modifier.fillMaxWidth().background(Aez.colors.glass).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back) IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "Back", { nav.back() })
            else Spacer(Modifier.width(12.dp))
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            actions()
        }
        Hairline()
    }
}

/** A bottom sheet of actions (the "…" menus). */
@Composable
fun ActionSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)) { content() }
    }
}

@Composable
fun SheetItem(text: String, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) Aez.colors.danger else Aez.colors.text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(text, color = if (danger) Aez.colors.danger else Aez.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Aez.colors.elev2,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, color = Aez.colors.text2) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (danger) Aez.colors.danger else Aez.colors.text, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Aez.colors.text2) } },
    )
}

@Composable
fun Badge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFF3040)).padding(horizontal = 5.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
        Text(if (count > 9) "9+" else count.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
