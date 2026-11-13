package xyz.aezdek.hush.client

import java.util.Locale

/**
 * What makes a password good enough for Hush. Anyone holding a copy of the server's data can guess at a password
 * offline for as long as they like, so the ones attackers try first are refused. The web app has the same rules
 * (web/src/core/passwordRules.ts); crypto-vectors/password-rules.json keeps the two giving identical answers.
 *
 * The list of common passwords is SecLists' 10,000 most common (MIT licence, Daniel Miessler), each written the way
 * [core] reads a password.
 */
object PasswordRules {
    const val MIN_LENGTH = 12

    enum class Problem(val message: String) {
        TOO_SHORT("Use at least $MIN_LENGTH characters. A few unrelated words make a good one."),
        HAS_USERNAME("Leave your username out of your password."),
        REPETITIVE("Use more different characters. Repeats are easy to guess."),
        ONLY_DIGITS("Mix in some letters. Numbers alone are easy to guess."),
        PREDICTABLE("Avoid runs like abcd, 1234 or qwerty."),
        COMMON("That password, or one very like it, is on the lists attackers try first."),
    }

    private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b', '9' to 'g',
        '@' to 'a', '$' to 's', '!' to 'i', '|' to 'i', '+' to 't')
    private val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")

    private val common: Set<String> by lazy {
        val stream = PasswordRules::class.java.getResourceAsStream("common-passwords.txt") ?: return@lazy emptySet()
        stream.bufferedReader().useLines { lines -> lines.filter { it.isNotEmpty() }.toHashSet() }
    }

    /** Reads the list of common passwords now (it is otherwise read on first use), off the main thread. */
    fun preload() {
        common.size
    }

    /** What is wrong with [password], or null if it will do. */
    fun check(password: String, username: String = ""): Problem? {
        val points = password.codePoints().toArray()
        val lower = password.lowercase(Locale.ROOT)
        if (points.size < MIN_LENGTH) return Problem.TOO_SHORT
        val name = username.trim().lowercase(Locale.ROOT)
        if (name.length >= 3 && name in lower) return Problem.HAS_USERNAME
        if (points.distinct().size < 6) return Problem.REPETITIVE
        if (points.all { it in '0'.code..'9'.code }) return Problem.ONLY_DIGITS
        val lowerPoints = lower.codePoints().toArray()
        if (predictable(lowerPoints) * 2 >= lowerPoints.size) return Problem.PREDICTABLE
        if (isCommon(core(password)) || isCommon(letters(password))) return Problem.COMMON
        return null
    }

    /** Lower case, without the digits and symbols people tack on at either end. */
    private fun trimmed(password: String): String {
        val s = password.lowercase(Locale.ROOT)
        var i = 0
        var j = s.length
        while (i < j && s[i] !in 'a'..'z') i++
        while (j > i && s[j - 1] !in 'a'..'z') j--
        return s.substring(i, j)
    }

    /** The trimmed password with look-alike characters read as letters (p@ssw0rd is password). */
    private fun core(password: String): String = trimmed(password).map { LEET[it] ?: it }.joinToString("")

    /** Just its letters a to z (letmein!letmein is letmeinletmein). */
    private fun letters(password: String): String = trimmed(password).filter { it in 'a'..'z' }

    /** On the list, or two words from it run together. */
    private fun isCommon(word: String): Boolean {
        if (word.isEmpty()) return false
        if (word in common) return true
        val points = word.codePoints().toArray()
        for (i in 3 until points.size - 2) {
            if (String(points, 0, i) in common && String(points, i, points.size - i) in common) return true
        }
        return false
    }

    /** How many characters sit in runs of three or more: repeats, alphabet or number order, or keyboard rows. */
    private fun predictable(points: IntArray): Int {
        var covered = 0
        var start = 0
        for (k in 1..points.size) {
            if (k == points.size || !adjacent(points[k - 1], points[k])) {
                if (k - start >= 3) covered += k - start
                start = k
            }
        }
        return covered
    }

    private fun adjacent(a: Int, b: Int): Boolean {
        if (a == b || kotlin.math.abs(a - b) == 1) return true
        for (row in ROWS) {
            val ia = row.indexOf(a.toChar()).takeIf { a < 0x10000 } ?: -1
            val ib = row.indexOf(b.toChar()).takeIf { b < 0x10000 } ?: -1
            if (ia >= 0 && ib >= 0 && kotlin.math.abs(ia - ib) == 1) return true
        }
        return false
    }
}
