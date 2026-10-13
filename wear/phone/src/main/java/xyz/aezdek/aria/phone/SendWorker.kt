package xyz.aezdek.aria.phone

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import xyz.aezdek.aria.core.Track
import java.io.File

/** Downloads a song (yt-dlp on the phone, or the Aria server) and hands it to the watch. */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private fun notification(title: String, text: String, percent: Int) =
        NotificationCompat.Builder(applicationContext, PhoneApp.TRANSFERS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .build()

    private suspend fun stage(track: Track, stage: String, progress: Float) {
        setProgress(workDataOf("id" to track.id, "title" to track.title, "stage" to stage, "progress" to progress))
        runCatching {
            setForeground(ForegroundInfo(id.hashCode(), notification(track.title, stage, (progress * 100).toInt()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }
    }

    override suspend fun getForegroundInfo() =
        ForegroundInfo(id.hashCode(), notification("Aria", "Sending to watch", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun doWork(): Result {
        var track = Track.fromJson(JSONObject(inputData.getString("track") ?: return Result.failure())) ?: return Result.failure()
        val outbox = File(applicationContext.cacheDir, "outbox").apply { mkdirs() }
        val server = PhonePrefs.server
        return try {
            stage(track, "Getting song info", 0f)
            if (server.configured) track = runCatching { server.trackInfo(track) }.getOrDefault(track)

            stage(track, "Downloading", 0f)
            val audio = File(outbox, "${track.id}.m4a")
            val viaPhone = PhonePrefs.usePhone
            try {
                if (viaPhone) YtDlp.download(track.id, outbox) { p -> setProgressAsync(workDataOf("id" to track.id, "title" to track.title, "stage" to "Downloading", "progress" to p)) }
                else server.download(track.id, audio) { done, total -> if (total > 0) setProgressAsync(workDataOf("id" to track.id, "title" to track.title, "stage" to "Downloading", "progress" to done.toFloat() / total)) }
            } catch (e: Exception) {
                // yt-dlp on the phone failed; the server's yt-dlp is often newer and has Deno for YouTube.
                if (!viaPhone || !server.configured) throw e
                stage(track, "Downloading through the server", 0f)
                server.download(track.id, audio)
            }

            val art = File(outbox, "${track.id}.jpg")
            val artUrl = track.artwork ?: server.artworkUrl(track)?.takeIf { server.configured } ?: "https://i.ytimg.com/vi/${track.id}/hqdefault.jpg"
            runCatching { server.fetch(artUrl, art) }

            stage(track, "Sending to watch", 1f)
            WatchLink.push(applicationContext, track.copy(artwork = null), audio, art.takeIf { it.exists() })
            Result.success(workDataOf("id" to track.id))
        } catch (e: Exception) {
            Result.failure(workDataOf("id" to track.id, "error" to (e.message ?: "Failed")))
        }
    }

    companion object {
        const val TAG = "send"

        fun enqueue(context: Context, track: Track) {
            PhonePrefs.received = PhonePrefs.received - track.id
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(workDataOf("track" to track.toJson().toString()))
                .addTag(TAG)
                .addTag("id:${track.id}")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("send-${track.id}", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
