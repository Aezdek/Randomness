package xyz.aezdek.aezprint.data.jobs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.aezdek.aezprint.data.R
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.PrintPhase
import xyz.aezdek.aezprint.domain.model.PrintRecord
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_JOBS, context.getString(R.string.channel_jobs), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_jobs_description)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_attention_description)
            },
        )
    }

    fun foregroundInfo(record: PrintRecord): ForegroundInfo =
        ForegroundInfo(notificationId(record.localId), progress(record), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun update(record: PrintRecord) {
        val notification = when {
            record.awaitingFlip -> flipPrompt(record)
            record.phase == PrintPhase.FAILED || record.jobState == JobState.FAILED -> finished(record, R.string.notif_failed)
            record.phase == PrintPhase.CANCELED || record.jobState == JobState.CANCELED -> finished(record, R.string.notif_canceled)
            record.jobState == JobState.COMPLETED -> finished(record, R.string.notif_done)
            else -> progress(record)
        }
        notify(if (record.isActive && !record.awaitingFlip) notificationId(record.localId) else attentionId(record.localId), notification)
        if (record.isActive && !record.awaitingFlip) manager.cancel(attentionId(record.localId))
    }

    private fun notify(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(id, notification)
        }
    }

    private fun progress(record: PrintRecord): Notification {
        val title = when (record.phase) {
            PrintPhase.UPLOADING -> context.getString(R.string.notif_uploading, record.documentName)
            PrintPhase.SUBMITTING -> context.getString(R.string.notif_submitting, record.documentName)
            else -> context.getString(R.string.notif_printing, record.documentName)
        }
        val builder = base(record, CHANNEL_JOBS)
            .setContentTitle(title)
            .setContentText(record.message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.notif_cancel_action), action(record.localId, JobActionReceiver.ACTION_CANCEL))
        when {
            record.phase == PrintPhase.UPLOADING -> builder.setProgress(100, (record.uploadProgress * 100).toInt(), record.uploadProgress <= 0f)
            record.sheetsTotal > 0 -> builder.setProgress(record.sheetsTotal, record.sheetsCompleted, false)
            else -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun flipPrompt(record: PrintRecord): Notification =
        base(record, CHANNEL_ATTENTION)
            .setContentTitle(context.getString(R.string.notif_flip_title, record.documentName))
            .setContentText(context.getString(R.string.notif_flip_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_flip_body)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.notif_flip_action), action(record.localId, JobActionReceiver.ACTION_CONTINUE))
            .addAction(0, context.getString(R.string.notif_cancel_action), action(record.localId, JobActionReceiver.ACTION_CANCEL))
            .build()

    private fun finished(record: PrintRecord, title: Int): Notification =
        base(record, CHANNEL_JOBS)
            .setContentTitle(context.getString(title, record.documentName))
            .setContentText(record.message)
            .setAutoCancel(true)
            .build()

    private fun base(record: PrintRecord, channel: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentIntent(openJob(record.localId))
            .setSilent(channel == CHANNEL_JOBS)

    private fun openJob(localId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$DEEP_LINK_JOB$localId"))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, localId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(localId: String, action: String): PendingIntent {
        val intent = Intent(context, JobActionReceiver::class.java)
            .setAction(action)
            .putExtra(JobActionReceiver.EXTRA_LOCAL_ID, localId)
        return PendingIntent.getBroadcast(context, (localId + action).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notificationId(localId: String) = localId.hashCode()
    private fun attentionId(localId: String) = localId.hashCode() xor ATTENTION_MASK

    companion object {
        const val CHANNEL_JOBS = "jobs"
        const val CHANNEL_ATTENTION = "attention"
        const val DEEP_LINK_JOB = "aezprint://job/"
        private const val ATTENTION_MASK = 0x5A5A5A5A
    }
}
