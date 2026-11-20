package xyz.aezdek.daybook.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import xyz.aezdek.daybook.Daybook
import xyz.aezdek.daybook.data.SqliteLocalStore
import xyz.aezdek.daybook.core.SyncResponse
import xyz.aezdek.daybook.data.LocalStore
import kotlin.time.Clock
import xyz.aezdek.daybook.core.Subject
import xyz.aezdek.daybook.data.TaskDraft
import xyz.aezdek.daybook.domain.plusDays
import xyz.aezdek.daybook.domain.today
import xyz.aezdek.daybook.sync.Appearance
import xyz.aezdek.daybook.sync.ConfigStore
import xyz.aezdek.daybook.sync.SyncConfig
import xyz.aezdek.daybook.ui.AppCommand
import xyz.aezdek.daybook.ui.DaybookApp
import xyz.aezdek.daybook.ui.Tab
import java.io.File

/**
 * Renders the app offscreen with sample tasks, for design review without a display:
 * `./gradlew :desktopApp:screenshots`. Nothing here touches the real database.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots").also { it.mkdirs() }
    val app = runBlocking { sampleApp() }

    shot(out, "phone-today-light", 393, 852, 2f) { DaybookApp(app, systemDark = false, reduceMotion = false) }
    shot(out, "phone-today-dark", 393, 852, 2f) { DaybookApp(app, systemDark = true, reduceMotion = false) }
    shot(out, "phone-progress-light", 393, 852, 2f) { DaybookApp(app, systemDark = false, reduceMotion = false, initialTab = Tab.Progress) }
    shot(out, "phone-settings-dark", 393, 852, 2f) { DaybookApp(app, systemDark = true, reduceMotion = false, initialTab = Tab.Settings) }
    val sheet = MutableSharedFlow<AppCommand>(replay = 1).also { it.tryEmit(AppCommand.NewTask) }
    shot(out, "phone-new-task-light", 393, 852, 2f) { DaybookApp(app, systemDark = false, reduceMotion = false, commands = sheet) }
    shot(out, "phone-tomorrow-light", 393, 852, 2f) {
        DaybookApp(app, systemDark = false, reduceMotion = false, initialDate = today().plusDays(1))
    }
    shot(out, "desktop-today-light", 1180, 800, 1.5f) { DaybookApp(app, systemDark = false, reduceMotion = false) }
    shot(out, "desktop-progress-dark", 1180, 800, 1.5f) { DaybookApp(app, systemDark = true, reduceMotion = false, initialTab = Tab.Progress) }
    println("Screenshots in ${out.absolutePath}")
    kotlin.system.exitProcess(0)
}

private fun shot(dir: File, name: String, widthDp: Int, heightDp: Int, scale: Float, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(
        width = (widthDp * scale).toInt(),
        height = (heightDp * scale).toInt(),
        density = Density(scale),
        content = content,
    )
    // Give fonts, the database and the springs time to settle.
    // State written by background coroutines (database reads) reaches the scene only once snapshot
    // changes are flushed; a windowed app's frame loop does this, an offscreen scene does not.
    var image = scene.render(0)
    for (frame in 1..240) {
        Thread.sleep(12)
        Snapshot.sendApplyNotifications()
        image = scene.render(frame * 16_000_000L)
    }
    val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
    File(dir, "$name.png").writeBytes(png.bytes)
    scene.close()
    System.gc()
    println("wrote $name.png")
}

private class SampleConfig : ConfigStore {
    override fun loadSync() = SyncConfig(serverUrl = "https://daybook.example.com", token = "sample")
    override fun saveSync(config: SyncConfig) = Unit
    override fun loadAppearance() = Appearance.System
    override fun saveAppearance(appearance: Appearance) = Unit
}

private suspend fun sampleApp(): Daybook {
    val app = Daybook.open(
        store = SqliteLocalStore.open(BundledSQLiteDriver(), ":memory:", Dispatchers.IO.limitedParallelism(1)),
        http = HttpClient(OkHttp),
        configStore = SampleConfig(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val t = today()
    val tasks = app.tasks
    suspend fun add(title: String, subject: Subject, paper: String, minutes: Int, day: Int, done: Boolean = false) {
        val id = tasks.add(TaskDraft(title, subject, paper, minutes, t.plusDays(day)))
        if (done) tasks.setDone(id, true)
    }
    add("Write a linked list class in Python", Subject.CS, "P4", 60, 0)
    add("Flashcards: logic gates and Boolean algebra", Subject.CS, "P1", 30, -1)
    add("10 topical questions on integration by parts", Subject.MATHS, "P3", 45, 0, done = true)
    add("Vectors: lines and planes, Q1 to Q8", Subject.MATHS, "P3", 60, 0)
    add("Binomial distribution exercise", Subject.MATHS, "P5", 45, -1, done = true)
    add("Past paper 42, timed, then mark it", Subject.PHYSICS, "P4", 120, 0)
    add("Kinematics recap notes", Subject.PHYSICS, "P2", 30, 1)
    for (back in 2..40) {
        if (back % 6 == 0) continue
        add("Revision ${back}a", Subject.entries[back % 3], "", 30, -back, done = true)
        if (back % 2 == 0) add("Revision ${back}b", Subject.entries[(back + 1) % 3], "", 45, -back, done = true)
        if (back % 5 == 0) add("Revision ${back}c", Subject.entries[(back + 2) % 3], "", 20, -back, done = true)
    }
    tasks.setExamDate(t.plusDays(167))
    // Show the state after a successful sync: everything confirmed, last synced just now.
    val pendingTasks = app.store.pendingTasks(10_000)
    val pendingSettings = app.store.pendingSettings()
    app.store.applySync(SyncResponse(rev = 1, tasks = pendingTasks, settings = pendingSettings), pendingTasks, pendingSettings)
    app.store.setMeta(LocalStore.META_LAST_SYNC, (Clock.System.now().toEpochMilliseconds() - 60_000).toString())
    app.sync.loadStatus()
    return app
}
