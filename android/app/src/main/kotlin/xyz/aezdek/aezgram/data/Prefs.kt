package xyz.aezdek.aezgram.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Device-only preferences (not synced): appearance and app lock. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("aezgram.prefs", Context.MODE_PRIVATE)

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

    var notifiedUpTo: Long
        get() = prefs.getLong("notifiedUpTo", 0)
        set(value) = prefs.edit().putLong("notifiedUpTo", value).apply()
}
