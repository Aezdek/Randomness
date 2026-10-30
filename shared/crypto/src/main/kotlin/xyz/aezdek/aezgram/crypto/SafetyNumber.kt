package xyz.aezdek.aezgram.crypto

/**
 * Safety numbers: 30 digits per identity key, 60 for a pair. Two people who see the same 60 digits (or scan each
 * other's profile QR) know the server has not swapped anyone's keys.
 */
object SafetyNumber {

    /** Six groups of five digits, from BLAKE2b-240 of the identity key. */
    fun code(signPk: ByteArray): String {
        val hash = Sodium.blake2b(30, concat(utf8("aezgram/v1/safety\n"), signPk))
        return (0 until 6).joinToString("") { group ->
            var value = 0L
            for (i in 0 until 5) value = (value shl 8) or (hash[group * 5 + i].toLong() and 0xff)
            (value % 100_000).toString().padStart(5, '0')
        }
    }

    /** Both codes, lower one first, so both sides compute the same 60 digits. */
    fun pair(a: ByteArray, b: ByteArray): String = listOf(code(a), code(b)).sorted().joinToString("")

    fun grouped(digits: String): List<String> = digits.chunked(5)

    /** Profile QR payload. Scanning one compares the key itself, not just the digits. */
    fun qrPayload(userId: String, signPk: ByteArray): String = "aezgram:verify:$userId:${B64.encode(signPk)}"

    fun parseQr(payload: String): Pair<String, ByteArray>? {
        val parts = payload.split(":")
        if (parts.size != 4 || parts[0] != "aezgram" || parts[1] != "verify") return null
        return runCatching { parts[2] to B64.decode(parts[3]) }.getOrNull()?.takeIf { it.second.size == 32 }
    }
}
