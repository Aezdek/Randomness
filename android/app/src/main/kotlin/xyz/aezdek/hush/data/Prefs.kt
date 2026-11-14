package xyz.aezdek.hush.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Device-only preferences (not synced): appearance and app lock. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("hush.prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme

    private val _appLock = MutableStateFlow(prefs.getBoolean("appLock", false))
    val appLock: StateFlow<Boolean> = _appLock

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _theme.value = mode
    }

    fun setAppLock(enabled: Boolean) {
        prefs.edit().putBoolean("appLock", enabled).apply()
        _appLock.value = enabled
    }

    /** Signed in with a password today's rules would refuse: the app asks for a better one until it changes. */
    private val _weakPassword = MutableStateFlow(prefs.getBoolean("weakPassword", false))
    val weakPassword: StateFlow<Boolean> = _weakPassword

    fun setWeakPassword(weak: Boolean) {
        prefs.edit().putBoolean("weakPassword", weak).apply()
        _weakPassword.value = weak
    }

    var lastActivitySeen: Long
        get() = prefs.getLong("activitySeen", 0)
        set(value) = prefs.edit().putLong("activitySeen", value).apply()

    /** Vanish mode is a per-chat, per-device switch (as on the web). */
    fun vanish(threadId: String): Boolean = prefs.getBoolean("vanish.$threadId", false)

    fun setVanish(threadId: String, on: Boolean) = prefs.edit().putBoolean("vanish.$threadId", on).apply()

    /** Samsung's "Never sleeping apps" can't be read back, so the readiness checklist remembers the user's tick. */
    var samsungNeverSleeping: Boolean
        get() = prefs.getBoolean("samsungNeverSleeping", false)
        set(value) = prefs.edit().putBoolean("samsungNeverSleeping", value).apply()

    /**
     * The Hush server this app talks to, when it was chosen on this phone: builds made for one server come with its
     * address, others ask for it on first start. Saved at once, because the app restarts right after it changes.
     */
    var serverUrl: String?
        get() = prefs.getString("serverUrl", null)
        set(value) {
            prefs.edit().putString("serverUrl", value).commit()
        }

    /** Install updates from the server by themselves (on by default). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(value) = prefs.edit().putBoolean("autoUpdate", value).apply()

    /** Compare updates, and the server's web app, with the public record of the build (on by default). */
    var verifyUpdates: Boolean
        get() = prefs.getBoolean("verifyUpdates", true)
        set(value) = prefs.edit().putBoolean("verifyUpdates", value).apply()

    /** The build number of an update that was refused, so its warning is shown once. */
    var refusedUpdate: Int
        get() = prefs.getInt("refusedUpdate", 0)
        set(value) = prefs.edit().putInt("refusedUpdate", value).apply()

    /** The last check of the server's web app: when, for which version, how it went (1 matches, 0 unknown, -1 differs), and the words. */
    var webCheckAt: Long
        get() = prefs.getLong("webCheckAt", 0)
        set(value) = prefs.edit().putLong("webCheckAt", value).apply()
    var webCheckVersion: String
        get() = prefs.getString("webCheckVersion", "").orEmpty()
        set(value) = prefs.edit().putString("webCheckVersion", value).apply()
    var webCheckResult: Int
        get() = prefs.getInt("webCheckResult", 0)
        set(value) = prefs.edit().putInt("webCheckResult", value).apply()
    var webCheckText: String
        get() = prefs.getString("webCheckText", "").orEmpty()
        set(value) = prefs.edit().putString("webCheckText", value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(value) = prefs.edit().putLong("lastUpdateCheck", value).apply()

    var notifiedUpTo: Long
        get() = prefs.getLong("notifiedUpTo", 0)
        set(value) = prefs.edit().putLong("notifiedUpTo", value).apply()
}
