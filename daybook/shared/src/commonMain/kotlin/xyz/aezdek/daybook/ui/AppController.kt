package xyz.aezdek.daybook.ui

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.data.Task
import xyz.aezdek.daybook.data.TaskDraft
import xyz.aezdek.daybook.ui.components.BannerSpec
import xyz.aezdek.daybook.ui.components.OverlayState

/** Things the user can do from the keyboard on desktop. */
enum class AppCommand { NewTask, GoToToday, NextDay, PreviousDay, ShowToday, ShowProgress, ShowSettings, SyncNow, Dismiss }

enum class Tab(val title: String) { Today("Today"), Progress("Progress"), Settings("Settings") }

/** The screens' actions, run on the app's scope so they finish even if the screen goes away. */
@Stable
class AppController(val app: Daybook, private val overlay: OverlayState, private val scope: CoroutineScope) {

    fun add(draft: TaskDraft) {
        scope.launch { app.tasks.add(draft) }
    }

    fun edit(task: Task, draft: TaskDraft) {
        scope.launch { app.tasks.edit(task.id, draft) }
    }

    fun setDone(task: Task, done: Boolean) {
        scope.launch { app.tasks.setDone(task.id, done) }
    }

    fun move(task: Task, date: LocalDate) {
        scope.launch { app.tasks.move(task.id, date) }
    }

    /** Deletes straight away and offers Undo in a banner, rather than asking first. */
    fun delete(task: Task) {
        scope.launch { app.tasks.delete(task.id) }
        overlay.showBanner(
            BannerSpec(message = "Deleted “${task.title}”", actionLabel = "Undo") {
                scope.launch { app.tasks.restore(task.id) }
            },
        )
    }

    fun setExamDate(date: LocalDate?) {
        scope.launch { app.tasks.setExamDate(date) }
    }

    fun syncNow() {
        app.sync.requestSync()
    }
}
