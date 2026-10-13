package xyz.aezdek.aria.phone

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.aezdek.aria.core.AriaServer

object PhonePrefs {
    private lateinit var prefs: SharedPreferences
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aria", Context.MODE_PRIVATE)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _changes.value++
    }

    var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = edit { putString("server", AriaServer.normalize(v) ?: "") }

    var key: String
        get() = prefs.getString("key", "") ?: ""
        set(v) = edit { putString("key", v.trim()) }

    /** Download songs with yt-dlp on this phone (true), or through the Aria server (false). */
    var usePhone: Boolean
        get() = prefs.getBoolean("usePhone", true)
        set(v) = edit { putBoolean("usePhone", v) }

    /** Songs the watch has confirmed it received. */
    var received: Set<String>
        get() = prefs.getStringSet("received", emptySet()) ?: emptySet()
        set(v) = edit { putStringSet("received", v) }

    fun markReceived(id: String) {
        received = received + id
    }

    val server get() = AriaServer(serverUrl, key)
}
