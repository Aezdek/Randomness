package xyz.aezdek.hush.client

import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.crypto.PasswordKeys
import xyz.aezdek.hush.crypto.Sodium

// The Android app can pass for another app on the home screen (its disguise package). Opened, it shows that app
// "crashing"; whoever knows the secret move is asked for a PIN or password instead, and the right one opens Hush.
// This file is the part with nothing of Android in it, so it is tested here: which moves count, what makes a PIN good
// enough, how the PIN is kept, and how long wrong guesses make you wait.

/** The secret moves on the "keeps stopping" screen. The person picks one; nothing on screen says which. */
enum class DisguiseTrigger(val id: String, val title: String, val hint: String) {
    TAP_MESSAGE("tap", "Tap the message 4 times", "Quickly tap the words \"… keeps stopping\" four times."),
    HOLD_CLOSE("hold", "Hold \"Close app\" for 3 seconds", "A quick tap closes it, as anyone would expect. Keep holding to get in."),
    CORNER("corner", "Tap the top-right corner 3 times", "Three quick taps on the empty screen at the top right, above the message."),
    VOLUME("volume", "Press volume down, down, up", "While the message shows: volume down twice, then volume up."),
    ;

    companion object {
        val DEFAULT = TAP_MESSAGE

        fun of(id: String?): DisguiseTrigger = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** What the "keeps stopping" screen passes on to the [TriggerDetector]. */
enum class DecoyInput { MESSAGE, CORNER, VOLUME_DOWN, VOLUME_UP }

/**
 * Watches the decoy screen for the chosen move. Anything else, or the right move done too slowly, does nothing at all:
 * no sound, no shake, nothing to say there was something to find.
 */
class TriggerDetector(private val trigger: DisguiseTrigger) {
    private val pattern: List<DecoyInput> = when (trigger) {
        DisguiseTrigger.TAP_MESSAGE -> List(4) { DecoyInput.MESSAGE }
        DisguiseTrigger.CORNER -> List(3) { DecoyInput.CORNER }
        DisguiseTrigger.VOLUME -> listOf(DecoyInput.VOLUME_DOWN, DecoyInput.VOLUME_DOWN, DecoyInput.VOLUME_UP)
        DisguiseTrigger.HOLD_CLOSE -> emptyList()
    }
    private val recent = ArrayDeque<Pair<DecoyInput, Long>>()

    /** One input at [at] (milliseconds, on a clock that only goes forward). True if it completes the move. */
    fun feed(input: DecoyInput, at: Long): Boolean {
        // Inputs that are no part of this move are ignored rather than counted against it.
        if (input !in pattern) return false
        val last = recent.lastOrNull()
        if (last != null && (at - last.second > MAX_GAP_MS || at < last.second)) recent.clear()
        recent.addLast(input to at)
        while (recent.size > pattern.size) recent.removeFirst()
        if (recent.map { it.first } != pattern) return false
        recent.clear()
        return true
    }

    /** Whether holding "Close app" for [ms] gets in. */
    fun held(ms: Long): Boolean = trigger == DisguiseTrigger.HOLD_CLOSE && ms >= HOLD_MS

    companion object {
        /** The longest pause between two taps or presses of one move. */
        const val MAX_GAP_MS = 1_200L
        const val HOLD_MS = 3_000L
    }
}

/** The PIN or password that opens a disguised Hush, kept on the phone only as an Argon2id hash. */
object DisguiseSecret {
    /**
     * Lighter than the account password's cost, as it is typed every time Hush opens; still a fifth of a second or so
     * per guess on a phone, before the waits after wrong ones ([DisguiseThrottle]).
     */
    val PARAMS = KdfParams(opsLimit = 2, memLimitBytes = 32 * 1024 * 1024)
    const val MIN_PIN = 4
    const val MAX_PIN = 12
    const val MIN_PASSWORD = 6
    const val MAX_PASSWORD = 128
    private const val SCHEME = "a2id"

    /** Why [secret] won't do as a PIN (when [pin]) or a password, or null if it will. */
    fun problem(secret: String, pin: Boolean): String? = if (pin) {
        when {
            secret.isEmpty() -> "Choose a PIN."
            secret.any { it !in '0'..'9' } -> "A PIN is digits only."
            secret.length < MIN_PIN -> "Use at least $MIN_PIN digits."
            secret.length > MAX_PIN -> "Use at most $MAX_PIN digits."
            secret.toSet().size == 1 || isRun(secret) -> "Too easy to guess. Avoid repeats and runs like 1234."
            else -> null
        }
    } else {
        when {
            secret.isBlank() -> "Choose a password."
            secret.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters."
            secret.length > MAX_PASSWORD -> "Use at most $MAX_PASSWORD characters."
            else -> null
        }
    }

    /** 0123…, 9876…: every step one up, or every step one down. */
    private fun isRun(digits: String): Boolean {
        val steps = digits.zipWithNext { a, b -> b - a }.toSet()
        return steps == setOf(1) || steps == setOf(-1)
    }

    /** The kept form, `a2id:ops:mem:salt:hash`, so the cost can be raised later without breaking a PIN already set. */
    fun hash(secret: String, params: KdfParams = PARAMS): String {
        val salt = PasswordKeys.newSalt()
        val hash = PasswordKeys.stretch(secret, salt, params)
        return listOf(SCHEME, params.opsLimit, params.memLimitBytes, B64.encode(salt), B64.encode(hash)).joinToString(":")
    }

    /** Whether [secret] is the one [kept] was made from. Anything malformed in [kept] is a no. */
    fun verify(secret: String, kept: String): Boolean {
        val parts = kept.split(':')
        if (parts.size != 5 || parts[0] != SCHEME) return false
        val ops = parts[1].toLongOrNull() ?: return false
        val mem = parts[2].toIntOrNull() ?: return false
        // A damaged value must not make every unlock take minutes, or fail to allocate.
        if (ops !in 1L..10L || mem !in 8 * 1024 * 1024..256 * 1024 * 1024) return false
        val salt = runCatching { B64.decode(parts[3]) }.getOrNull() ?: return false
        val expected = runCatching { B64.decode(parts[4]) }.getOrNull() ?: return false
        if (salt.size != PasswordKeys.SALT_BYTES || expected.size != 32) return false
        return Sodium.constantTimeEquals(PasswordKeys.stretch(secret, salt, KdfParams(ops, mem)), expected)
    }
}

/** How long wrong PINs make you wait: a few free tries, then twice as long after each one. */
object DisguiseThrottle {
    const val FREE_TRIES = 5
    private const val FIRST_WAIT_MS = 30_000L
    private const val LONGEST_WAIT_MS = 15 * 60_000L

    /** The wait after [fails] wrong tries in a row. */
    fun waitAfter(fails: Int): Long =
        if (fails < FREE_TRIES) 0 else minOf(FIRST_WAIT_MS shl (fails - FREE_TRIES).coerceAtMost(10), LONGEST_WAIT_MS)

    /**
     * How much of that wait is left at [now], the last wrong try having been at [lastFailAt] (wall clock). A clock
     * set back makes the whole wait start again rather than end it.
     */
    fun remaining(fails: Int, lastFailAt: Long, now: Long): Long {
        val wait = waitAfter(fails)
        if (wait == 0L) return 0
        val since = now - lastFailAt
        if (since < 0) return wait
        return (wait - since).coerceAtLeast(0)
    }
}
