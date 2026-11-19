package xyz.aezdek.daybook.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.ContinuousShape
import xyz.aezdek.daybook.ui.theme.GlassVariant
import xyz.aezdek.daybook.ui.theme.Motion
import xyz.aezdek.daybook.ui.theme.Radii
import xyz.aezdek.daybook.ui.theme.Symbols
import xyz.aezdek.daybook.ui.theme.glass
import kotlin.math.roundToInt

data class MenuItem(
    val title: String,
    val symbol: ImageVector? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** A context menu opened at [anchor], in root coordinates. Groups are separated by a thicker divider. */
data class MenuSpec(val anchor: Offset, val groups: List<List<MenuItem>>)

data class BannerSpec(val message: String, val actionLabel: String, val onAction: () -> Unit)

/** A sheet's content. It gets a dismiss function so its own Cancel and Done buttons can close it. */
class SheetSpec(val key: Any, val content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit)

/** What floats above the screens: one sheet, one menu and one banner at most. */
@Stable
class OverlayState {
    var sheet by mutableStateOf<SheetSpec?>(null)
        private set
    var menu by mutableStateOf<MenuSpec?>(null)
        private set
    var banner by mutableStateOf<BannerSpec?>(null)
        private set

    fun showSheet(key: Any, content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
        menu = null
        sheet = SheetSpec(key, content)
    }

    fun dismissSheet() {
        sheet = null
    }

    fun showMenu(anchor: Offset, groups: List<List<MenuItem>>) {
        menu = MenuSpec(anchor, groups.filter { it.isNotEmpty() })
    }

    fun dismissMenu() {
        menu = null
    }

    fun showBanner(spec: BannerSpec) {
        banner = spec
    }

    fun dismissBanner() {
        banner = null
    }

    /** Escape or the system back gesture: closes the topmost thing. Returns false when nothing was open. */
    fun dismissTop(): Boolean = when {
        menu != null -> { menu = null; true }
        sheet != null -> { sheet = null; true }
        else -> false
    }
}

val LocalOverlay = staticCompositionLocalOf<OverlayState> { error("No OverlayState") }

/**
 * The sheet layer. In compact width the sheet rises from the bottom edge and can be dragged down by its
 * grabber, tracking the finger, to dismiss; in regular width it appears centred, like a form sheet. Under
 * Reduce Motion both fade. The visible Cancel or Done button inside every sheet is the button alternative
 * to the drag.
 */
@Composable
fun SheetLayer(state: OverlayState, regular: Boolean) {
    val reduce = Apple.reduceMotion
    val colors = Apple.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val requested = state.sheet
    var shown by remember { mutableStateOf<SheetSpec?>(null) }
    val progress = remember { Animatable(0f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(requested) {
        if (requested != null) {
            shown = requested
            drag = 0f
            progress.animateTo(1f, Motion.standard(reduce))
        } else if (shown != null) {
            progress.animateTo(0f, Motion.standard(reduce))
            shown = null
            drag = 0f
        }
    }
    val current = shown ?: return
    val p = progress.value
    val dragFraction = if (sheetHeight > 0) (drag / sheetHeight).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = p * (1f - dragFraction) }
                .background(colors.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.dismissSheet() }
                .semantics { contentDescription = "Dismiss" },
        )
        val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) }
        val dragModifier = Modifier.draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                if (drag > sheetHeight * 0.25f || velocity > 1200f) {
                    scope.launch {
                        if (!reduce && !regular) {
                            animate(drag, sheetHeight.toFloat(), velocity, Motion.standard(false)) { v, _ -> drag = v }
                            progress.snapTo(0f)
                        }
                        state.dismissSheet()
                    }
                } else {
                    scope.launch { animate(drag, 0f, velocity, Motion.standard(reduce)) { v, _ -> drag = v } }
                }
            },
        )

        if (regular) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = maxHeight * 0.88f)
                    .offset { IntOffset(0, drag.roundToInt()) }
                    .graphicsLayer {
                        alpha = p
                        val s = if (reduce) 1f else 0.94f + 0.06f * p
                        scaleX = s
                        scaleY = s
                    }
                    .clip(ContinuousShape(Radii.sheet))
                    .background(colors.sheetBackground)
                    .imePadding(),
            ) {
                Box(Modifier.fillMaxWidth().then(dragModifier)) { Spacer(Modifier.height(8.dp)) }
                current.content(this) { state.dismissSheet() }
            }
        } else {
            val hiddenOffset = if (reduce) 0f else (1f - p) * (sheetHeight.takeIf { it > 0 } ?: with(density) { maxHeight.toPx() }.roundToInt())
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.94f)
                    .onSizeChanged { sheetHeight = it.height }
                    .offset { IntOffset(0, (hiddenOffset + drag).roundToInt()) }
                    .graphicsLayer { if (reduce) alpha = p }
                    .clip(ContinuousShape(Radii.sheet, Radii.sheet, 0.dp, 0.dp))
                    .background(colors.sheetBackground)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding(),
            ) {
                // Grabber
                Box(Modifier.fillMaxWidth().height(20.dp).then(dragModifier), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(50)).background(colors.fill))
                }
                current.content(this) { state.dismissSheet() }
            }
        }
    }
}

/** The context menu layer: a glass popover that grows out of the point that was pressed. */
@Composable
fun MenuLayer(state: OverlayState) {
    val reduce = Apple.reduceMotion
    val colors = Apple.colors
    val density = LocalDensity.current
    val requested = state.menu
    var shown by remember { mutableStateOf<MenuSpec?>(null) }
    val progress = remember { Animatable(0f) }
    var menuSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(requested) {
        if (requested != null) {
            shown = requested
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.standard(reduce))
        } else if (shown != null) {
            progress.animateTo(0f, Motion.snappy(reduce))
            shown = null
        }
    }
    val current = shown ?: return
    val p = progress.value

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = p }
                .background(colors.scrim.copy(alpha = colors.scrim.alpha * 0.5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.dismissMenu() },
        )
        val w = menuSize.width.toFloat()
        val h = menuSize.height.toFloat()
        val opensUp = current.anchor.y + h + margin > height
        val x = (current.anchor.x - w * 0.15f).coerceIn(margin, (width - w - margin).coerceAtLeast(margin))
        val y = if (opensUp) (current.anchor.y - h).coerceAtLeast(margin) else current.anchor.y
        val originX = if (w > 0) ((current.anchor.x - x) / w).coerceIn(0f, 1f) else 0f
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(260.dp)
                .onSizeChanged { menuSize = it }
                .graphicsLayer {
                    alpha = p
                    if (!reduce) {
                        val s = 0.7f + 0.3f * p
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(originX, if (opensUp) 1f else 0f)
                    }
                }
                .glass(ContinuousShape(Radii.menu), GlassVariant.Regular),
        ) {
            current.groups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) Box(Modifier.fillMaxWidth().height(8.dp).background(colors.tertiaryFill))
                group.forEachIndexed { index, item ->
                    val tint = if (item.destructive) colors.red else colors.label
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                state.dismissMenu()
                                item.onClick()
                            }
                            .bottomSeparator(index < group.lastIndex, colors.separator, 16.dp)
                            .heightIn(min = 46.dp)
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppleText(item.title, Apple.type.body, Modifier.weight(1f), color = tint)
                        if (item.symbol != null) SymbolIcon(item.symbol, null, tint = tint, size = 20.dp)
                    }
                }
            }
        }
    }
}

/** A glass banner above the tab bar, for undo. It stays until acted on or closed: nothing here times out. */
@Composable
fun BannerLayer(state: OverlayState, bottomPadding: androidx.compose.ui.unit.Dp) {
    val spec = state.banner ?: return
    val colors = Apple.colors
    Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .padding(horizontal = Apple.margin)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(50))
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppleText(spec.message, Apple.type.subhead, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 2)
            TextButton(spec.actionLabel, onClick = {
                state.dismissBanner()
                spec.onAction()
            }, style = Apple.type.subheadSemibold, modifier = Modifier.padding(horizontal = 4.dp))
            IconButton(Symbols.xmark, "Close", onClick = { state.dismissBanner() }, tint = colors.secondaryLabel, size = 14.dp)
        }
    }
}

/** A standard sheet header: Cancel leading, title centred, the confirming action trailing in bold. */
@Composable
fun SheetHeader(
    title: String,
    onCancel: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 52.dp)) {
        TextButton("Cancel", onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp))
        AppleText(title, Apple.type.headline, Modifier.align(Alignment.Center))
        TextButton(
            confirmLabel,
            onClick = onConfirm,
            enabled = confirmEnabled,
            style = Apple.type.bodySemibold,
            modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = 8.dp),
        )
    }
}
