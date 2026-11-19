package xyz.aezdek.daybook.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.roundToInt

data class SwipeAction(
    val label: String,
    val symbol: ImageVector,
    val background: Color,
    val onTrigger: () -> Unit,
)

/**
 * Row swipe actions, as in iOS lists. The row tracks the finger 1:1; on release it springs open to
 * reveal the actions, closes, or — past the full-swipe point — runs the outermost action. A haptic marks
 * the full-swipe point. Every action is also offered as an accessibility action, and the screens offer
 * the same actions as buttons in the context menu and edit sheet.
 */
@Composable
fun SwipeActionsBox(
    leading: SwipeAction?,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val reduce = Apple.reduceMotion
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val actionWidth = with(density) { 78.dp.toPx() }

    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var settle: Job? by remember { mutableStateOf(null) }
    var pastFullSwipe by remember { mutableStateOf(false) }

    val maxLeading = if (leading != null) actionWidth else 0f
    val maxTrailing = actionWidth * trailing.size
    val fullSwipe = width * 0.55f

    fun animateTo(target: Float, velocity: Float = 0f, then: (() -> Unit)? = null) {
        settle?.cancel()
        settle = scope.launch {
            animate(offset, target, velocity, Motion.standard(reduce)) { value, _ -> offset = value }
            then?.invoke()
        }
    }

    fun close() = animateTo(0f)

    val dragState = rememberDraggableState { delta ->
        // Reverse in RTL so "trailing" stays at the end of the reading direction.
        val d = if (rtl) -delta else delta
        val min = if (trailing.isEmpty()) 0f else -width.toFloat()
        val max = if (leading == null) 0f else width.toFloat()
        offset = (offset + d).coerceIn(min, max)
        val beyond = abs(offset) > fullSwipe
        if (beyond != pastFullSwipe) {
            pastFullSwipe = beyond
            if (beyond) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = listOfNotNull(leading).plus(trailing).map { action ->
                    CustomAccessibilityAction(action.label) { action.onTrigger(); true }
                }
            },
    ) {
        // Revealed actions sit behind the row.
        if (offset < 0f && trailing.isNotEmpty()) {
            val revealed = -offset
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                trailing.forEachIndexed { index, action ->
                    val isLast = index == trailing.lastIndex
                    val share = if (pastFullSwipe) (if (isLast) revealed else 0f) else revealed / trailing.size
                    if (share > 0.5f) {
                        ActionTile(action, with(density) { share.toDp() }) {
                            close()
                            action.onTrigger()
                        }
                    }
                }
            }
        }
        if (offset > 0f && leading != null) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.Start) {
                ActionTile(leading, with(density) { offset.toDp() }) {
                    close()
                    leading.onTrigger()
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset((if (rtl) -offset else offset).roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { settle?.cancel() },
                    onDragStopped = { rawVelocity ->
                        val v = if (rtl) -rawVelocity else rawVelocity
                        when {
                            offset < 0f && (-offset > fullSwipe || (v < -2600f && -offset > maxTrailing)) -> {
                                val action = trailing.last()
                                animateTo(-width.toFloat(), v) {
                                    action.onTrigger()
                                    offset = 0f
                                }
                            }
                            offset < 0f && (-offset > maxTrailing / 2 || v < -700f) && v < 700f -> animateTo(-maxTrailing, v)
                            offset > 0f && leading != null && (offset > fullSwipe * 0.7f || v > 2000f) -> {
                                animateTo(0f, v)
                                leading.onTrigger()
                            }
                            offset > 0f && (offset > maxLeading / 2 || v > 700f) && v > -700f -> animateTo(maxLeading, v)
                            else -> animateTo(0f, v)
                        }
                        pastFullSwipe = false
                    },
                ),
        ) {
            content()
            // While actions are showing, a tap on the row closes them instead of opening it.
            if (offset != 0f) {
                Box(Modifier.matchParentSize().clickable(indication = null, interactionSource = null) { close() })
            }
        }
    }
}

@Composable
private fun ActionTile(action: SwipeAction, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(action.background)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (width > 44.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SymbolIcon(action.symbol, null, tint = Color.White, size = 20.dp)
                AppleText(action.label, Apple.type.caption1Semibold, color = Color.White, maxLines = 1)
            }
        }
    }
}
