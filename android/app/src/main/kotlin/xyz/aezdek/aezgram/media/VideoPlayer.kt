package xyz.aezdek.aezgram.media

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import xyz.aezdek.aezgram.protocol.MediaRef

/** Plays an encrypted video, streamed and decrypted on the fly. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    ref: MediaRef,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    muted: Boolean = false,
    loop: Boolean = true,
    fill: Boolean = true,
    controls: Boolean = false,
    onProgress: ((Float) -> Unit)? = null,
    onEnded: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val player = remember(ref.blobId) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(EncryptedDataSource.Factory()))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(EncryptedDataSource.uriOf(ref)))
                repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) ready = true
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    if (onProgress != null) {
        LaunchedEffect(player, ready) {
            while (true) {
                val duration = player.duration
                if (duration > 0) onProgress((player.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
                kotlinx.coroutines.delay(100)
            }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = controls
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
        },
    )
}
