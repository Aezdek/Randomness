package xyz.aezdek.hush.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView

/**
 * Every emoji, by category, with recents and skin tones: Android's own picker (androidx.emoji2), the same set as the
 * keyboard, sized to sit in a sheet.
 */
@Composable
fun EmojiPicker(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val pick by rememberUpdatedState(onPick)
    AndroidView(
        factory = { context ->
            EmojiPickerView(context).apply {
                emojiGridColumns = 8
                setOnEmojiPickedListener { item -> pick(item.emoji) }
            }
        },
        modifier = modifier.fillMaxWidth().height(320.dp).nestedScroll(rememberNestedScrollInteropConnection()),
    )
}
