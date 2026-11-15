package xyz.aezdek.hush.disguise

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.HushActivity
import xyz.aezdek.hush.R
import xyz.aezdek.hush.calls.CallStatus
import xyz.aezdek.hush.client.DisguiseTrigger
import xyz.aezdek.hush.service.ConnectionService
import java.security.SecureRandom

/**
 * A look Hush can wear on the home screen. Each is an `<activity-alias>` in the manifest with its own name and icon;
 * exactly one is enabled at a time. [HUSH] is Hush itself; the others open the "keeps stopping" screen
 * ([DecoyActivity]) instead of the app.
 */
enum class Look(
    val id: String,
    /** The alias's name in the manifest (after the package). */
    val alias: String,
    /** The name under the icon. */
    val label: String,
    /** What Hush's own screens call it. */
    val title: String,
    @DrawableRes val background: Int,
    @DrawableRes val foreground: Int,
    /** The status bar icon of a notification while disguised. */
    @DrawableRes val small: Int,
    /** The whole icon (a mipmap), for the recent apps list. */
    val icon: Int,
) {
    HUSH("", "MainActivity", "Hush", "Hush", R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground, R.drawable.ic_notification, R.mipmap.ic_launcher),
    VIDEOS("videos", "DisguiseVideos", "Videos", "Videos", R.drawable.disguise_videos_bg, R.drawable.disguise_videos_fg, R.drawable.disguise_videos_small, R.mipmap.ic_disguise_videos),
    CALCULATOR("calculator", "DisguiseCalculator", "Calculator", "Calculator", R.drawable.disguise_calculator_bg, R.drawable.disguise_calculator_fg, R.drawable.disguise_calculator_small, R.mipmap.ic_disguise_calculator),
    WEATHER("weather", "DisguiseWeather", "Weather", "Weather", R.drawable.disguise_weather_bg, R.drawable.disguise_weather_fg, R.drawable.disguise_weather_small, R.mipmap.ic_disguise_weather),
    NOTES("notes", "DisguiseNotes", "Notes", "Notes", R.drawable.disguise_notes_bg, R.drawable.disguise_notes_fg, R.drawable.disguise_notes_small, R.mipmap.ic_disguise_notes),
    // No picture and no name ("⠀" shows as nothing): an empty spot on the home screen that still opens.
    BLANK("blank", "DisguiseBlank", "⠀", "Blank", 0, 0, R.drawable.disguise_blank_small, R.mipmap.ic_disguise_blank),
    ;

    /** Its name in a "keeps stopping" message: a blank name would read oddly there. */
    val crashName: String get() = if (this == BLANK) "This app" else label

    /** The title of a notification while disguised (none for the blank one). */
    val notificationTitle: String? get() = if (this == BLANK) null else label

    companion object {
        val DISGUISES = entries.filter { it != HUSH }

        fun of(id: String?): Look = DISGUISES.firstOrNull { it.id == id } ?: HUSH
    }
}

/**
 * Hush, disguised as another app. Its icon and name on the home screen are another app's (a [Look]); opening it shows
 * that app "keeps stopping". The chosen secret move there ([DisguiseTrigger]) brings up a PIN or password prompt
 * instead, and the right one opens Hush. Whenever Hush then leaves the screen, it closes and the disguise is back.
 *
 * What Android itself still shows, which no app can change: Hush's real name in Settings → Apps, and at the top of
 * any notification. So notifications are off while disguised unless the person turns them on.
 */
object Disguise {
    private const val NAMESPACE = "xyz.aezdek.hush"

    /** How long Hush stays open behind a photo picker, the camera or the like, which it expects to come back from. */
    private const val AWAY_FOR_RESULT_MS = 5 * 60_000L

    val look: Look get() = Look.of(Graph.prefs.disguiseLook.value)
    val isOn: Boolean get() = look != Look.HUSH
    val trigger: DisguiseTrigger get() = DisguiseTrigger.of(Graph.prefs.disguiseTrigger.value)

    private val _revealed = MutableStateFlow(false)

    /** True once the PIN was given, until Hush next leaves the screen. Only kept in memory. */
    val revealed: StateFlow<Boolean> = _revealed

    /** Disguised and not opened with the PIN: nothing of Hush may show. */
    fun locked(): Boolean = isOn && !_revealed.value

    fun reveal() {
        _revealed.value = true
    }

    fun lock() {
        lockPending = false
        _revealed.value = false
    }

    @Volatile
    private var awaySince = 0L

    @Volatile
    private var lockPending = false

    /** Hush is handing over to a picker, the camera or another screen it expects back: it stays open meanwhile. */
    fun expectReturn() {
        awaySince = SystemClock.elapsedRealtime()
    }

    /** Hush is back on top (some pickers only cover part of it, so it never left the screen to come back from). */
    fun returned() {
        awaySince = 0L
    }

    private fun callGoing(): Boolean = runCatching { Graph.calls.state.value.status != CallStatus.IDLE }.getOrDefault(false)

    fun init(app: Application) {
        // Leaving the screen closes Hush, however it was left: home, another app, the screen going off.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (!isOn) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> when {
                    awaySince != 0L -> Unit
                    // A call keeps its screen; Hush closes once the call is over and it is next left or opened.
                    callGoing() -> lockPending = true
                    else -> lock()
                }
                Lifecycle.Event.ON_START -> {
                    val away = awaySince
                    awaySince = 0L
                    val tooLong = away != 0L && SystemClock.elapsedRealtime() - away > AWAY_FOR_RESULT_MS
                    if (tooLong || (lockPending && !callGoing())) lock()
                }
                else -> Unit
            }
        })
        runCatching { syncIcon(app) }
    }

    // ------------------------------------------------------------------------------------------------- home screen

    private fun component(context: Context, look: Look) = ComponentName(context.packageName, "$NAMESPACE.${look.alias}")

    /** Shows [target] on the home screen and hides the others. The new one goes on first, so there is always one. */
    fun showIcon(context: Context, target: Look) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(component(context, target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (look in Look.entries) {
            if (look == target) continue
            pm.setComponentEnabledSetting(component(context, look), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    /**
     * Makes the home screen match the saved choice, if it doesn't: after a change cut short, or after Hush's storage
     * was cleared (which forgets the disguise but not which icon Android shows).
     */
    fun syncIcon(context: Context) {
        val pm = context.packageManager
        val want = look
        val wrong = Look.entries.any { look ->
            val enabled = when (pm.getComponentEnabledSetting(component(context, look))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> look == Look.HUSH
                else -> false
            }
            enabled != (look == want)
        }
        if (wrong) showIcon(context, want)
    }

    /**
     * Puts on [to], or changes to it, then closes Hush: the new icon is on the home screen, and the next time in is
     * through it. The PIN must be set first.
     */
    fun turnOn(activity: Activity, to: Look) {
        require(to != Look.HUSH)
        check(Graph.prefs.disguiseSecret.value.isNotEmpty()) { "Set a PIN first" }
        Graph.prefs.setDisguiseLook(to.id)
        refreshService(activity)
        showIcon(activity, to)
        activity.finishAndRemoveTask()
        lock()
    }

    /** Hush is itself again on the home screen. It stays open. */
    fun turnOff(context: Context) {
        Graph.prefs.setDisguiseLook(Look.HUSH.id)
        showIcon(context, Look.HUSH)
        refreshService(context)
    }

    // ----------------------------------------------------------------------------------------------- notifications

    /** The background connection runs unless disguised with notifications off: it would need a notification of its own. */
    fun wantsService(): Boolean = !isOn || Graph.prefs.disguiseNotify.value

    /** Whether a notification may show now: disguised with notifications off, only while Hush is open on screen. */
    fun allowsNotifications(): Boolean = !isOn || Graph.prefs.disguiseNotify.value || (_revealed.value && Graph.visible.value)

    fun refreshService(context: Context) {
        if (!Graph.client.signedIn) return
        when {
            !wantsService() -> ConnectionService.stop(context)
            ConnectionService.running -> Graph.notifier.refreshConnection()
            else -> ConnectionService.start(context)
        }
    }

    /**
     * Before Android 13 an app can't keep its screen out of the recent apps list except by marking it secure, which
     * blocks screenshots too; so a disguised Hush does that there.
     */
    fun wantsSecureWindow(): Boolean = isOn && Build.VERSION.SDK_INT < 33

    // ---------------------------------------------------------------------------------------------------- the way in

    /** The "keeps stopping" screen, then (once in) [open] in Hush. */
    fun decoyIntent(context: Context, open: String? = null): Intent =
        Intent(context, DecoyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            if (open != null) putExtra(HushActivity.EXTRA_OPEN, open)
        }

    /** Hush itself, at [open] (a notification's target) if given. */
    fun hushIntent(context: Context, open: String? = null): Intent =
        Intent(context, HushActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            if (open != null) putExtra(HushActivity.EXTRA_OPEN, open)
        }

    private const val EXTRA_PRACTICE = "practice"
    private const val EXTRA_LOOK = "look"

    @Volatile
    private var practiceToken: String? = null

    /**
     * "Try it" in Settings: the same screen for [look], with a line saying what to do. The token proves the request
     * came from Hush's own settings: other apps can open the screen too, and must not be shown the move.
     */
    fun practiceIntent(context: Context, look: Look): Intent {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        practiceToken = token
        return Intent(context, DecoyActivity::class.java).putExtra(EXTRA_PRACTICE, token).putExtra(EXTRA_LOOK, look.id)
    }

    /** The look a practice run shows (only read once [isPractice] said yes). */
    fun practiceLook(intent: Intent?): Look = Look.of(intent?.getStringExtra(EXTRA_LOOK)).takeIf { it != Look.HUSH } ?: Look.CALCULATOR

    /** How long before the next PIN may be tried, after wrong ones. */
    fun waitLeft(): Long = xyz.aezdek.hush.client.DisguiseThrottle.remaining(
        Graph.prefs.disguiseFails, Graph.prefs.disguiseLastFail, System.currentTimeMillis(),
    )

    fun isPractice(intent: Intent?): Boolean {
        val token = intent?.getStringExtra(EXTRA_PRACTICE) ?: return false
        val expected = practiceToken ?: return false
        if (token != expected) return false
        practiceToken = null
        return true
    }
}
