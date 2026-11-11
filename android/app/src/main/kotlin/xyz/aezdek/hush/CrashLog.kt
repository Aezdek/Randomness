package xyz.aezdek.hush

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the details of the last crash on the phone, so the next start can show them and they can be passed on.
 * Only the app's own code paths and error messages are written: never messages, keys or anything people sent.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val TAG = "Hush"
    private lateinit var dir: File

    fun install(context: Context) {
        dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("Crash on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * For background work: a failure there is written down and the app carries on, rather than closing in the
     * middle of whatever the person is doing.
     */
    val handler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Background work failed", error)
        runCatching { write("Background failure (the app kept running)", error) }
    }

    fun pending(): String? = runCatching { File(dir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()

    fun clear() {
        runCatching { File(dir, FILE).delete() }
    }

    private fun write(what: String, error: Throwable) {
        if (!::dir.isInitialized) return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val text = buildString {
            appendLine("$what, $stamp")
            appendLine("Hush ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(trace.take(16_000))
        }
        // A crash wins over a background failure that happened earlier.
        val file = File(dir, FILE)
        if (what.startsWith("Background") && file.exists() && file.readText().startsWith("Crash")) return
        file.writeText(text)
    }
}
