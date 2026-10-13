package xyz.aezdek.aria.watch

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import xyz.aezdek.aria.core.Track

/** Builds the MediaItem for a song: the downloaded file if there is one, otherwise the server's stream. */
object Items {
    fun from(track: Track): MediaItem? {
        val local = Library.audioFile(track.id).takeIf { it.exists() }
        val uri = local?.let { Uri.fromFile(it) } ?: Prefs.server.streamUrl(track.id)?.let(Uri::parse) ?: return null
        val art = Library.artFile(track.id).takeIf { it.exists() }?.let { Uri.fromFile(it) }
            ?: Prefs.server.artworkUrl(track)?.let(Uri::parse)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .setArtworkUri(art)
            .setExtras(Bundle().apply { putString("track", track.toJson().toString()) })
            .build()
        return MediaItem.Builder().setMediaId(track.id).setUri(uri).setMediaMetadata(metadata).build()
    }

    fun track(item: MediaItem?): Track? =
        item?.mediaMetadata?.extras?.getString("track")?.let { runCatching { Track.fromJson(JSONObject(it)) }.getOrNull() }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    /** Skips sponsor reads and non-music intros and outros (SponsorBlock segments). */
    private val skipper = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 500)
            if (!Prefs.skipNonMusic || !player.isPlaying) return
            val track = Items.track(player.currentMediaItem) ?: return
            val t = player.currentPosition / 1000.0
            val duration = player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: track.duration
            val segment = track.skip.firstOrNull { (start, end) -> t >= start && t < end - 0.5 } ?: return
            if (duration > 0 && segment.second >= duration - 1) {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
            } else {
                player.seekTo((segment.second * 1000).toLong())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when earbuds disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("open", "player"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Controllers don't send the file or stream address; rebuild it from the song.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.mapNotNull { item -> Items.track(item)?.let(Items::from) ?: item.takeIf { it.localConfiguration != null } }.toMutableList())
            })
            .build()
        handler.post(skipper)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(skipper)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
