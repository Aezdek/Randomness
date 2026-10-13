package xyz.aezdek.aria.watch

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import xyz.aezdek.aria.core.Track
import xyz.aezdek.aria.core.WearPaths
import java.io.File

object PhoneLink {
    /** Imports songs the phone sent. Safe to call any time: it only does work when something is waiting. */
    fun importPending(context: Context) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("import", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** Hears from the phone app over the Wear OS data link, even when Aria isn't open. */
class PhoneLinkService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        var pushed = false
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            when {
                path == WearPaths.CONFIG -> {
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    Prefs.serverUrl = map.getString("server", "")
                    Prefs.key = map.getString("key", "")
                    Downloads.syncNow(this)
                }
                path.startsWith(WearPaths.PUSH_PREFIX) -> pushed = true
            }
        }
        if (pushed) PhoneLink.importPending(this)
    }
}

/** Copies songs the phone sent into the library, then removes them from the data link to free space. */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dataClient = Wearable.getDataClient(applicationContext)
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WearPaths.PUSH_PREFIX).build()
        val items = dataClient.getDataItems(uri, DataClient.FILTER_PREFIX).await()
        var failed = false
        try {
            for (item in items) {
                val map = DataMapItem.fromDataItem(item).dataMap
                val track = map.getString(WearPaths.PUSH_TRACK)?.let { runCatching { Track.fromJson(JSONObject(it)) }.getOrNull() }
                val audio = map.getAsset(WearPaths.PUSH_AUDIO)
                if (track == null || audio == null) {
                    dataClient.deleteDataItems(item.uri).await()
                    continue
                }
                try {
                    copy(dataClient, audio, Library.audioFile(track.id))
                    map.getAsset(WearPaths.PUSH_ARTWORK)?.let { art -> runCatching { copy(dataClient, art, Library.artFile(track.id)) } }
                    Library.add(track)
                    dataClient.deleteDataItems(item.uri).await()
                    item.uri.host?.let { phone ->
                        runCatching { Wearable.getMessageClient(applicationContext).sendMessage(phone, WearPaths.PUSH_RECEIVED, track.id.toByteArray()).await() }
                    }
                } catch (e: Exception) {
                    failed = true // the asset may still be on its way; try again later
                }
            }
        } finally {
            items.release()
        }
        return if (failed && runAttemptCount < 5) Result.retry() else Result.success()
    }

    private suspend fun copy(client: DataClient, asset: Asset, dest: File) {
        val response = client.getFdForAsset(asset).await()
        val part = File(dest.path + ".part")
        response.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(dest)) throw IllegalStateException("couldn't save ${dest.name}")
    }
}
