package xyz.aezdek.salah.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.aezdek.salah.SalahApplication
import java.time.LocalDate

/** Shows the reminder when the alarm fires, then sets the next one. Boot and clock changes only set the alarm again. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SalahApplication
        val pending = goAsync()
        app.scope.launch {
            try {
                val data = app.store.data.first()
                if (intent.action == Reminders.ACTION_REMIND && data.settings.reminderEnabled) {
                    app.reminders.notifyIfUnlogged(data, LocalDate.now())
                }
                app.reminders.schedule(data.settings.reminderMinute.takeIf { data.settings.reminderEnabled })
            } finally {
                pending.finish()
            }
        }
    }
}
