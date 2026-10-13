package xyz.aezdek.aria.watch

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import xyz.aezdek.aria.core.Track
import java.util.concurrent.TimeUnit

object Downloads {
    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (Prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun download(context: Context, track: Track) {
        if (Library.has(track.id)) return
        Library.setProgress(track.id, 0f)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("track" to track.toJson().toString()))
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("download-${track.id}", ExistingWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Downloads one song from the Aria server, with its proper title, album art and skip segments. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private fun notification(title: String, percent: Int) =
        NotificationCompat.Builder(applicationContext, WatchApp.DOWNLOADS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build()

    override suspend fun getForegroundInfo() = ForegroundInfo(id.hashCode(), notification("Downloading", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun doWork(): Result {
        val track = Track.fromJson(JSONObject(inputData.getString("track") ?: return Result.failure())) ?: return Result.failure()
        val server = Prefs.server
        if (!server.configured) {
            Library.setProgress(track.id, null)
            return Result.failure()
        }
        runCatching { setForeground(ForegroundInfo(id.hashCode(), notification(track.title, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
        return try {
            val info = runCatching { server.trackInfo(track) }.getOrDefault(track)
            var lastPercent = -1
            server.download(track.id, Library.audioFile(track.id)) { done, total ->
                if (total <= 0) return@download
                val fraction = done.toFloat() / total
                Library.setProgress(track.id, fraction)
                val percent = (fraction * 100).toInt()
                if (percent / 10 != lastPercent / 10) {
                    lastPercent = percent
                    setProgressAsync(workDataOf("progress" to fraction))
                }
            }
            (info.artwork ?: server.artworkUrl(info))?.let { url ->
                runCatching { server.fetch(url, Library.artFile(track.id)) }
            }
            Library.add(info)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            Library.setProgress(track.id, null)
        }
    }
}

/** Pulls Saved songs and the accent color from the Aria website, and downloads new Saved songs. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val server = Prefs.server
        if (!server.configured) return Result.success()
        return try {
            val config = server.config()
            config.optJSONObject("settings")?.let { s ->
                s.optString("accent").takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }?.let { Prefs.accent = it }
                if (s.has("skipNonMusic")) Prefs.skipNonMusic = s.optBoolean("skipNonMusic", true)
            }
            val saved = Track.listFromJson(config.optJSONArray("saved"))
            Prefs.saved = saved
            if (Prefs.autoDownloadSaved) saved.filterNot { Library.has(it.id) }.forEach { Downloads.download(applicationContext, it) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
