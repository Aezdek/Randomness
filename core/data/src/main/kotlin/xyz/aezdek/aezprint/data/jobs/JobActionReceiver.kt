package xyz.aezdek.aezprint.data.jobs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

class JobActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val localId = intent.getStringExtra(EXTRA_LOCAL_ID) ?: return
        val action = when (intent.action) {
            ACTION_CONTINUE -> JobActionWorker.ACTION_CONTINUE
            ACTION_CANCEL -> JobActionWorker.ACTION_CANCEL
            else -> return
        }
        WorkManager.getInstance(context).enqueue(WorkManagerPrintQueue.actionRequest(localId, action))
    }

    companion object {
        const val ACTION_CONTINUE = "xyz.aezdek.aezprint.action.CONTINUE"
        const val ACTION_CANCEL = "xyz.aezdek.aezprint.action.CANCEL"
        const val EXTRA_LOCAL_ID = "localId"
    }
}
