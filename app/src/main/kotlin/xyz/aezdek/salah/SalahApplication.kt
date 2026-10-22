package xyz.aezdek.salah

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aezdek.salah.data.DataStoreSalahStore
import xyz.aezdek.salah.data.SalahStore
import xyz.aezdek.salah.reminder.Reminders

class SalahApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store: SalahStore by lazy { DataStoreSalahStore(this, CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
    val reminders by lazy { Reminders(this) }

    override fun onCreate() {
        super.onCreate()
        reminders.createChannel()
        // A force stop clears alarms, and a restored backup can switch the reminder on, so the alarm is set
        // from the stored setting whenever the app runs.
        scope.launch {
            store.data
                .map { if (it.settings.reminderEnabled) it.settings.reminderMinute else null }
                .distinctUntilChanged()
                .collect { reminders.schedule(it) }
        }
    }
}
