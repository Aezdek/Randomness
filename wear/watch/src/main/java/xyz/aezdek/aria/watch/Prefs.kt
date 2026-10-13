package xyz.aezdek.aria.watch

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.aezdek.aria.core.AriaServer
import xyz.aezdek.aria.core.Track

/** Watch settings. The server address usually arrives from the phone app, so nobody types a URL on a watch. */
object Prefs {
    private lateinit var prefs: SharedPreferences
    private val _changes = MutableStateFlow(0)
    /** Bumps whenever a setting changes, for Compose to recompose. */
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

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", false)
        set(v) = edit { putBoolean("wifiOnly", v) }

    var autoDownloadSaved: Boolean
        get() = prefs.getBoolean("autoSaved", true)
        set(v) = edit { putBoolean("autoSaved", v) }

    var skipNonMusic: Boolean
        get() = prefs.getBoolean("skip", true)
        set(v) = edit { putBoolean("skip", v) }

    /** Accent color from the Aria website (#RRGGBB). */
    var accent: String
        get() = prefs.getString("accent", "#FF3B6B") ?: "#FF3B6B"
        set(v) = edit { putString("accent", v) }

    /** Saved songs from the website, cached for offline browsing. */
    var saved: List<Track>
        get() = runCatching { Track.listFromJson(org.json.JSONArray(prefs.getString("saved", "[]"))) }.getOrDefault(emptyList())
        set(v) = edit { putString("saved", Track.listToJson(v).toString()) }

    val server get() = AriaServer(serverUrl, key)

    val accentColor: Color
        get() = runCatching { Color(android.graphics.Color.parseColor(accent)) }.getOrDefault(Color(0xFFFF3B6B))
}
