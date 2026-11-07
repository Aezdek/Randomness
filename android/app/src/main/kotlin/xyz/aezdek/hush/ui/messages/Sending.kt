package xyz.aezdek.hush.ui.messages

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.hush.ui.theme.Aez

/** A text you sent that the server doesn't have yet: shown straight away, as on Instagram. */
@Stable
class PendingText(val id: String, val text: String, val replyTo: String?, val audience: String) {
    var failed by mutableStateOf(false)
}

/** The paper plane beside something still sending (drifting gently), or a red mark if it didn't go. */
@Composable
fun SendStatus(failed: Boolean) {
    if (failed) {
        Icon(Icons.Outlined.ErrorOutline, "Not sent", tint = Aez.colors.danger, modifier = Modifier.size(17.dp))
        return
    }
    val drift = rememberInfiniteTransition(label = "sending")
    val t by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "plane")
    Icon(
        Icons.AutoMirrored.Outlined.Send, "Sending", tint = Aez.colors.text3,
        modifier = Modifier.size(14.dp).offset(x = (2 * t).dp, y = (-2 * t).dp).alpha(0.55f + 0.45f * t),
    )
}

@Composable
fun PendingBubble(item: PendingText, brush: Brush, textColor: Color, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.padding(bottom = 8.dp)) { SendStatus(item.failed) }
            Spacer(Modifier.width(6.dp))
            Text(
                item.text, fontSize = 15.sp, lineHeight = 20.sp, color = textColor,
                modifier = Modifier.widthIn(max = 280.dp).alpha(0.72f).clip(RoundedCornerShape(20.dp)).background(brush)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        if (item.failed) {
            Text(
                "Not sent. Tap to retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Aez.colors.danger,
                modifier = Modifier.padding(top = 3.dp).clickable(onClick = onRetry),
            )
        }
    }
}

/** A photo, video or voice note on its way: the paper plane, and how far along it is. */
@Composable
fun UploadingRow(label: String, accent: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.padding(bottom = 10.dp)) { SendStatus(false) }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.width(220.dp).clip(RoundedCornerShape(16.dp)).background(Aez.colors.elev2).padding(12.dp)) {
            Text(label, fontSize = 13.sp, color = Aez.colors.text2)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = accent, trackColor = Aez.colors.elev3)
        }
    }
}
