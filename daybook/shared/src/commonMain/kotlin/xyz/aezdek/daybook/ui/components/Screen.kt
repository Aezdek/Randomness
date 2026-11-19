package xyz.aezdek.daybook.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import xyz.aezdek.daybook.ui.theme.Apple
import xyz.aezdek.daybook.ui.theme.LocalHazeState
import xyz.aezdek.daybook.ui.theme.Motion
import xyz.aezdek.daybook.ui.theme.scrollEdge

/** Content wider than this is hard to read; wide windows centre it. */
val ReadableWidth: Dp = 720.dp

fun Modifier.readable(): Modifier = widthIn(max = ReadableWidth).fillMaxWidth()

private val ToolbarHeight = 56.dp

/**
 * A root screen: content runs edge to edge under floating glass controls. The large title sits at the
 * top of the content and, once it scrolls away, an inline title fades in over the scroll edge effect.
 *
 * [chrome] is the space the app's own floating chrome takes: the sidebar on the start side in regular
 * width, the tab bar at the bottom in compact width.
 */
@Composable
fun AppleScreen(
    title: String,
    listState: LazyListState,
    chrome: PaddingValues,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val start = chrome.calculateStartPadding(layoutDirection)
    val bottom = chrome.calculateBottomPadding()
    val titleHeightPx = with(density) { 40.dp.toPx() }

    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 2 } }
    val pastTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > titleHeightPx }
    }
    val reduce = Apple.reduceMotion
    val edgeAlpha by animateFloatAsState(if (scrolled) 1f else 0f, Motion.standard(reduce))
    val inlineAlpha by animateFloatAsState(if (pastTitle) 1f else 0f, Motion.standard(reduce))

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(LocalHazeState.current),
            contentPadding = PaddingValues(start = start, top = topInset + ToolbarHeight, bottom = bottom + 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "large-title") {
                Column(Modifier.readable().padding(horizontal = Apple.margin).padding(top = 4.dp, bottom = 8.dp)) {
                    AppleText(title, Apple.type.largeTitle, Modifier.semantics { heading() })
                    if (subtitle != null) {
                        AppleText(subtitle, Apple.type.subhead, Modifier.padding(top = 2.dp), color = Apple.colors.secondaryLabel)
                    }
                }
            }
            content()
        }

        // Scroll edge effect behind the floating toolbar, only once something has scrolled under it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(topInset + ToolbarHeight + 20.dp)
                .graphicsLayer { alpha = edgeAlpha }
                .scrollEdge(top = true),
        )
        AppleText(
            title,
            Apple.type.headline,
            Modifier
                .align(Alignment.TopCenter)
                .padding(start = start, top = topInset + 17.dp)
                .graphicsLayer { alpha = inlineAlpha },
            maxLines = 1,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .height(ToolbarHeight)
                .padding(start = start + Apple.margin, end = Apple.margin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}
