package xyz.aezdek.hush.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.ionspin.kotlin.crypto.util.LibsodiumUtil
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CountDownLatch

/** Loads libsodium once. Every public entry point of this module calls [init] first, so callers never have to. */
object Sodium {
    @Volatile
    private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            if (!LibsodiumInitializer.isInitialized()) {
                val loaded = CountDownLatch(1)
                LibsodiumInitializer.initializeWithCallback { loaded.countDown() }
                loaded.await()
            }
            ready = true
        }
    }

    fun random(size: Int): ByteArray {
        init()
        return LibsodiumRandom.buf(size).asByteArray()
    }

    /** Unkeyed (or keyed) BLAKE2b, `crypto_generichash`. */
    fun blake2b(size: Int, message: ByteArray, key: ByteArray? = null): ByteArray {
        init()
        return GenericHash.genericHash(message.asUByteArray(), size, key?.asUByteArray()).asByteArray()
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        init()
        return a.size == b.size && LibsodiumUtil.memcmp(a.asUByteArray(), b.asUByteArray())
    }
}

/** URL-safe base64 without padding: the one binary encoding used in every API payload and test vector. */
object B64 {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(text: String): ByteArray = decoder.decode(text)
}

internal fun utf8(text: String): ByteArray = text.encodeToByteArray()

internal fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

internal fun u64(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()
internal fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Thrown when a signature, commitment or ciphertext does not check out. Never carries secret material. */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
