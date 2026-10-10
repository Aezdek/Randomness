package xyz.aezdek.aezprint.designsystem.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

fun documentSharedKey(documentId: String) = "document-$documentId"

/** Morphs a document thumbnail into the print preview. A no-op outside a shared transition (tests, previews). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedDocument(documentId: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedDocument.sharedBounds(
            sharedContentState = rememberSharedContentState(documentSharedKey(documentId)),
            animatedVisibilityScope = visibility,
        )
    }
}
