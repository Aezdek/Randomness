package xyz.aezdek.aria.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Updates the app from the aria-wear-latest GitHub release, which CI refreshes on every build:
 * version.json plus aria-watch.apk and aria-phone.apk, all signed with the same key.
 */
object Updater {
    private const val RELEASE = "https://github.com/Aezdek/Randomness/releases/download/aria-wear-latest/"
    private const val CHANNEL = "updates"

    data class Update(val versionCode: Long, val versionName: String, val apkUrl: String)

    /** What the updater is doing, for the settings screen. */
    val status = MutableStateFlow<String?>(null)

    fun installedVersion(context: Context): Pair<Long, String> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return code to (info.versionName ?: "?")
    }

    /** The newer version for this app ("aria-watch.apk" or "aria-phone.apk"), or null when up to date. */
    suspend fun check(context: Context, apkName: String): Update? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(RELEASE + "version.json").header("Cache-Control", "no-cache").build()
        val json = AriaServer.http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("GitHub answered ${res.code}")
            JSONObject(res.body!!.string())
        }
        val latest = json.optLong("versionCode")
        val (installed, _) = installedVersion(context)
        if (latest > installed) Update(latest, json.optString("versionName", latest.toString()), RELEASE + apkName) else null
    }

    /** Downloads the APK and hands it to Android's package installer. Android asks the user to confirm when it must. */
    suspend fun install(context: Context, update: Update) {
        val app = context.applicationContext
        status.value = "Downloading ${update.versionName}…"
        val apk = File(app.cacheDir, "update.apk")
        withContext(Dispatchers.IO) {
            AriaServer.http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Download failed (${res.code})")
                apk.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
            }
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                // Android 12+ installs silently once Aria is the app that installed itself.
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("aria.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                session.commit(callback.intentSender)
            }
        }
        status.value = "Installing ${update.versionName}…"
    }

    /** Checks once a day (and now), and installs any newer version. */
    fun schedule(context: Context, apkName: String) {
        val wm = WorkManager.getInstance(context)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            "update", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(net).setInputData(workDataOf("apk" to apkName)).build(),
        )
        checkNow(context, apkName)
    }

    fun checkNow(context: Context, apkName: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "update-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("apk" to apkName)).build(),
        )
    }

    internal fun notify(context: Context, title: String, text: String, intent: PendingIntent?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { intent?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(7001, n) }
    }
}

/** Hears back from the package installer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a tap to confirm. Open it now, and leave a notification in case the app is in the background.
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                val tap = PendingIntent.getActivity(context, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Updater.notify(context, "Aria update ready", "Tap to install", tap)
                Updater.status.value = "Waiting for you to confirm the update"
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status.value = "Updated"
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $code"
                Updater.status.value = "Update failed: $message"
                Updater.notify(context, "Aria update failed", message, null)
            }
        }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val apk = inputData.getString("apk") ?: return Result.failure()
        return try {
            Updater.status.value = "Checking for updates…"
            val update = Updater.check(applicationContext, apk)
            if (update == null) {
                Updater.status.value = "Up to date"
            } else {
                Updater.install(applicationContext, update)
            }
            Result.success()
        } catch (e: Exception) {
            Updater.status.value = "Couldn't check for updates: ${e.message}"
            Result.retry()
        }
    }
}
