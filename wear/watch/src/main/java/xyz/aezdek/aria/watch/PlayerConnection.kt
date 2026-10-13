package xyz.aezdek.aria.watch

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import xyz.aezdek.aria.core.Track

/** The UI's handle on PlaybackService. */
object PlayerConnection {
    var controller by mutableStateOf<MediaController?>(null)
        private set
    private var connecting = false

    fun connect(context: Context) {
        if (controller != null || connecting) return
        connecting = true
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            connecting = false
            controller = runCatching { future.get() }.getOrNull()
        }, ContextCompat.getMainExecutor(app))
    }

    fun play(tracks: List<Track>, index: Int) {
        val c = controller ?: return
        val items = tracks.mapNotNull(Items::from)
        if (items.isEmpty()) return
        val start = items.indexOfFirst { it.mediaId == tracks.getOrNull(index)?.id }.coerceAtLeast(0)
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }
}
