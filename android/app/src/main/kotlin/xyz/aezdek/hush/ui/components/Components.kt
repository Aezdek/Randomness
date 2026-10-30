package xyz.aezdek.hush.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.ui.theme.Aez
import xyz.aezdek.hush.ui.theme.Brand

// --------------------------------------------------------------------------------------------------------- media

/** A decrypted image: the blurhash shows at once, then the real image cross-fades in. */
@Composable
fun MediaImage(
    ref: MediaRef?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxEdge: Int = 1440,
    contentDescription: String? = ref?.alt,
) {
    val bitmap by produceState<Bitmap?>(initialValue = ref?.let { Graph.media.cached(it, maxEdge) }, ref?.blobId, maxEdge) {
        if (ref != null && value == null) value = runCatching { Graph.media.bitmap(ref, maxEdge) }.getOrNull()
    }
    Box(modifier.background(Aez.colors.elev2)) {
        val blur = remember(ref?.blurhash) { Graph.media.blur(ref?.blurhash) }
        if (blur != null) {
            Image(blur.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Crossfade(targetState = bitmap, animationSpec = tween(260), label = "media") { image ->
            if (image != null) Image(image.asImageBitmap(), contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

@Composable
fun BlurhashBox(hash: String?, modifier: Modifier = Modifier) {
    val blur = remember(hash) { Graph.media.blur(hash) }
    Box(modifier.background(Aez.colors.elev2)) {
        if (blur != null) Image(blur.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

// -------------------------------------------------------------------------------------------------------- avatars

enum class Ring { NONE, UNSEEN, SEEN, CLOSE_FRIENDS, LIVE }

/** A profile photo (decrypted), or initials on the brand gradient; with a story ring when there is one. */
@Composable
fun Avatar(
    userId: String?,
    size: Dp,
    index: Index,
    username: String? = null,
    ring: Ring = Ring.NONE,
    online: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val avatar = index.profile(userId)?.avatar
    val ringWidth = if (size > 60.dp) 3.dp else 2.dp
    val gap = if (ring == Ring.NONE) 0.dp else ringWidth + 2.dp
    val ringBrush: Brush? = when (ring) {
        Ring.NONE -> null
        Ring.UNSEEN -> Brand.gradient
        Ring.SEEN -> SolidColor(Aez.colors.borderStrong)
        Ring.CLOSE_FRIENDS -> SolidColor(Brand.closeFriends)
        Ring.LIVE -> Brush.linearGradient(listOf(Brand.live, Brand.pink))
    }
    Box(
        Modifier
            .size(size)
            .then(if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (ringBrush != null) Box(Modifier.fillMaxSize().border(ringWidth, ringBrush, CircleShape))
        val inner = size - gap * 2
        if (avatar != null) {
            MediaImage(avatar, Modifier.size(inner).clip(CircleShape), maxEdge = 320)
        } else {
            Box(
                Modifier.size(inner).clip(CircleShape).background(Brush.linearGradient(listOf(Brand.violet.copy(alpha = 0.85f), Brand.pink.copy(alpha = 0.85f)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (username ?: "?").take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (inner.value * 0.42f).sp,
                )
            }
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size((size.value * 0.26f).dp.coerceAtLeast(10.dp))
                    .background(Aez.colors.bg, CircleShape).padding(2.dp).background(Brand.closeFriends, CircleShape),
            )
        }
        if (ring == Ring.LIVE) {
            Text(
                "LIVE",
                Modifier.align(Alignment.BottomCenter).background(Brand.live, RoundedCornerShape(4.dp)).border(2.dp, Aez.colors.bg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun VerifiedBadge(size: Dp = 14.dp) {
    Icon(Icons.Filled.Verified, "Verified", tint = Color(0xFF4C9BFF), modifier = Modifier.size(size))
}

// -------------------------------------------------------------------------------------------------------- buttons

enum class ButtonStyle { PRIMARY, SECONDARY, BRAND, DANGER, GHOST }

@Composable
fun AezButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    enabled: Boolean = true,
    busy: Boolean = false,
    small: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = Aez.colors
    val (bg, fg) = when (style) {
        ButtonStyle.PRIMARY -> c.primary to c.onPrimary
        ButtonStyle.SECONDARY -> c.elev2 to c.text
        ButtonStyle.BRAND -> Color.Transparent to Color.White
        ButtonStyle.DANGER -> c.danger to Color.White
        ButtonStyle.GHOST -> Color.Transparent to c.text
    }
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "enabled")
    val shape = RoundedCornerShape(if (small) 10.dp else 12.dp)
    Box(
        modifier
            .heightIn(min = if (small) 34.dp else 46.dp)
            .clip(shape)
            .then(if (style == ButtonStyle.BRAND) Modifier.background(Brand.gradient, shape) else Modifier.background(bg, shape))
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg.copy(alpha = alpha), strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(18.dp))
                Text(text, color = fg.copy(alpha = alpha), fontWeight = FontWeight.SemiBold, fontSize = if (small) 13.sp else 15.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Aez.colors.text, size: Dp = 26.dp) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size))
    }
}

// ---------------------------------------------------------------------------------------------------------- input

@Composable
fun AezField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    supporting: String? = null,
) {
    val c = Aez.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        shape = RoundedCornerShape(14.dp),
        supportingText = supporting?.let { { Text(it) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.text2,
            unfocusedBorderColor = c.border,
            focusedContainerColor = c.elev1,
            unfocusedContainerColor = c.elev1,
            focusedLabelColor = c.text2,
            unfocusedLabelColor = c.text3,
            cursorColor = Brand.pink,
        ),
    )
}

// ----------------------------------------------------------------------------------------------------------- text

private val TOKENS = Regex("(@[a-z0-9._]{2,30})|(#[\\p{L}\\p{N}_]{2,30})|(https?://\\S+)", RegexOption.IGNORE_CASE)

/** Text with tappable @mentions, #hashtags and links. */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    prefix: AnnotatedString? = null,
    maxLines: Int = Int.MAX_VALUE,
    onMention: (String) -> Unit = {},
    onTag: (String) -> Unit = {},
    onLink: (String) -> Unit = {},
) {
    val linkColor = Aez.colors.link
    val annotated = remember(text, prefix, linkColor) {
        buildAnnotatedString {
            prefix?.let { append(it) }
            var last = 0
            for (match in TOKENS.findAll(text)) {
                append(text.substring(last, match.range.first))
                val token = match.value
                val styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium))
                withLink(LinkAnnotation.Clickable(token, styles) {
                    when {
                        token.startsWith("@") -> onMention(token.drop(1).lowercase())
                        token.startsWith("#") -> onTag(token.drop(1))
                        else -> onLink(token)
                    }
                }) { append(token) }
                last = match.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(annotated, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

fun boldPrefix(name: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
    append(" ")
}

// ---------------------------------------------------------------------------------------------------------- misc

fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - at).coerceAtLeast(0) / 1000
    return when {
        s < 45 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        s < 604_800 -> "${s / 86_400}d"
        s < 31_536_000 -> "${s / 604_800}w"
        else -> "${s / 31_536_000}y"
    }
}

fun compact(n: Int): String = when {
    n < 10_000 -> n.toString()
    n < 1_000_000 -> "${n / 1000}K"
    else -> "${n / 1_000_000}M"
}

fun plural(n: Int, one: String, many: String = one + "s") = "${compact(n)} ${if (n == 1) one else many}"

@Composable
fun Empty(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).border(2.dp, Aez.colors.text, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Aez.colors.text, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Aez.colors.text2, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(Aez.colors.border))
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Aez.colors
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (selected) c.primary else c.elev2)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) c.onPrimary else c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The bottom-of-screen toast, fed by [Graph.toasts]. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<xyz.aezdek.hush.Toast?>(null) }
    LaunchedEffect(Unit) {
        Graph.toasts.collect {
            current = it
            delay(2800)
            current = null
        }
    }
    Box(modifier.fillMaxWidth().padding(PaddingValues(16.dp)), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(current != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            val toast = current
            Box(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (toast?.error == true) Aez.colors.danger else Aez.colors.elev3)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(toast?.text.orEmpty(), color = if (toast?.error == true) Color.White else Aez.colors.text, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(size), color = Aez.colors.text2, strokeWidth = 2.5.dp)
    }
}

@Composable
fun noRipple(): MutableInteractionSource = remember { MutableInteractionSource() }

@Suppress("unused")
private val unusedWidth = Modifier.width(0.dp)
