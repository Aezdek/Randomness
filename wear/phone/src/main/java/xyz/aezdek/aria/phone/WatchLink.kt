package xyz.aezdek.aria.phone

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await
import xyz.aezdek.aria.core.Track
import xyz.aezdek.aria.core.WearPaths
import java.io.File

/** Talks to the Aria watch app over the Wear OS data link (Bluetooth, or Wi-Fi when the watch is on it). */
object WatchLink {
    /** The paired watch with Aria installed, if one is reachable now. */
    suspend fun findWatch(context: Context): Node? = runCatching {
        val nodes = Wearable.getCapabilityClient(context)
            .getCapability(WearPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }.getOrNull()

    /** Gives the watch the server address and key, so nobody types a URL on a watch. */
    suspend fun sendServer(context: Context, server: String, key: String) {
        val request = PutDataMapRequest.create(WearPaths.CONFIG).apply {
            dataMap.putString("server", server)
            dataMap.putString("key", key)
            dataMap.putLong("sent", System.currentTimeMillis()) // always counts as a change
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /**
     * Hands a song to the watch. The data link keeps it until the watch is in range and has copied it,
     * so this works even if the watch is asleep or away right now.
     */
    suspend fun push(context: Context, track: Track, audio: File, artwork: File?) {
        val request = PutDataMapRequest.create(WearPaths.PUSH_PREFIX + track.id).apply {
            dataMap.putString(WearPaths.PUSH_TRACK, track.toJson().toString())
            dataMap.putAsset(WearPaths.PUSH_AUDIO, Asset.createFromFd(ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)))
            artwork?.takeIf { it.exists() }?.let {
                dataMap.putAsset(WearPaths.PUSH_ARTWORK, Asset.createFromFd(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)))
            }
            dataMap.putLong("sent", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }
}

/** The watch says a song arrived: mark it, and drop the phone's copy. */
class WatchReplyService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.PUSH_RECEIVED) return
        val id = String(event.data)
        if (!Track.isVideoId(id)) return
        PhonePrefs.markReceived(id)
        File(cacheDir, "outbox/$id.m4a").delete()
        File(cacheDir, "outbox/$id.jpg").delete()
    }
}
