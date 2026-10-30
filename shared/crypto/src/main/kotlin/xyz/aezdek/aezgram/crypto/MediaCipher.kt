package xyz.aezdek.aezgram.crypto

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Chunked media encryption ("AGM1"): random access, so video can stream and seek without decrypting from the start.
 *
 * ```
 * header  = "AGM1" || u32 chunkSize || u64 plainLength || noncePrefix(16)         (32 bytes)
 * chunk i = XChaCha20-Poly1305(plain_i, nonce = noncePrefix || u64 i,
 *                              ad = header || u64 i || u8 isFinal, key = fileKey)  (plain_i + 16 bytes)
 * ```
 * The header (with the total length) is in every chunk's associated data, so truncation, reordering and splicing
 * chunks between files are all detected. An empty file is one empty, final chunk.
 */
object MediaCipher {
    const val HEADER_BYTES = 32
    const val DEFAULT_CHUNK = 64 * 1024
    const val TAG_BYTES = 16
    private val MAGIC = utf8("AGM1")

    class Header(val chunkSize: Int, val plainLength: Long, val noncePrefix: ByteArray, val bytes: ByteArray) {
        val chunkCount: Long get() = chunkCount(plainLength, chunkSize)

        fun plainChunkLength(index: Long): Int =
            if (index == chunkCount - 1) (plainLength - index * chunkSize).toInt() else chunkSize

        /** Where chunk [index] starts in the ciphertext, and how long it is there. */
        fun cipherRange(index: Long): LongRange {
            val start = HEADER_BYTES + index * (chunkSize + TAG_BYTES)
            return start until start + plainChunkLength(index) + TAG_BYTES
        }
    }

    fun newFileKey(): ByteArray = Sodium.random(32)

    fun chunkCount(plainLength: Long, chunkSize: Int = DEFAULT_CHUNK): Long =
        if (plainLength == 0L) 1 else (plainLength + chunkSize - 1) / chunkSize

    fun cipherLength(plainLength: Long, chunkSize: Int = DEFAULT_CHUNK): Long =
        HEADER_BYTES + plainLength + chunkCount(plainLength, chunkSize) * TAG_BYTES

    fun parseHeader(bytes: ByteArray): Header {
        if (bytes.size < HEADER_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw CryptoException("not an AGM1 file")
        }
        val buffer = ByteBuffer.wrap(bytes, 4, 12)
        val chunkSize = buffer.int
        val plainLength = buffer.long
        if (chunkSize <= 0 || plainLength < 0) throw CryptoException("bad AGM1 header")
        return Header(chunkSize, plainLength, bytes.copyOfRange(16, 32), bytes.copyOfRange(0, HEADER_BYTES))
    }

    /** Streams [plainLength] bytes from [input] to [output] as AGM1. */
    fun encrypt(
        input: InputStream,
        plainLength: Long,
        fileKey: ByteArray,
        output: OutputStream,
        chunkSize: Int = DEFAULT_CHUNK,
    ) {
        Sodium.init()
        val noncePrefix = Sodium.random(16)
        val headerBytes = concat(MAGIC, u32(chunkSize), u64(plainLength), noncePrefix)
        val header = Header(chunkSize, plainLength, noncePrefix, headerBytes)
        output.write(headerBytes)
        val buffer = ByteArray(chunkSize)
        for (index in 0 until header.chunkCount) {
            val length = header.plainChunkLength(index)
            readFully(input, buffer, length)
            output.write(sealChunk(header, index, buffer.copyOf(length), fileKey))
        }
    }

    fun encrypt(plain: ByteArray, fileKey: ByteArray, chunkSize: Int = DEFAULT_CHUNK): ByteArray {
        val out = java.io.ByteArrayOutputStream(cipherLength(plain.size.toLong(), chunkSize).toInt())
        encrypt(plain.inputStream(), plain.size.toLong(), fileKey, out, chunkSize)
        return out.toByteArray()
    }

    fun decrypt(cipher: ByteArray, fileKey: ByteArray): ByteArray {
        val header = parseHeader(cipher)
        if (cipher.size.toLong() != cipherLength(header.plainLength, header.chunkSize)) {
            throw CryptoException("AGM1 file has the wrong length")
        }
        val out = java.io.ByteArrayOutputStream(header.plainLength.toInt())
        for (index in 0 until header.chunkCount) {
            val range = header.cipherRange(index)
            out.write(openChunk(header, index, cipher.copyOfRange(range.first.toInt(), range.last.toInt() + 1), fileKey))
        }
        return out.toByteArray()
    }

    fun sealChunk(header: Header, index: Long, plain: ByteArray, fileKey: ByteArray): ByteArray {
        Sodium.init()
        return AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            plain.asUByteArray(), chunkAd(header, index).asUByteArray(), nonce(header, index).asUByteArray(),
            fileKey.asUByteArray(),
        ).asByteArray()
    }

    fun openChunk(header: Header, index: Long, cipherChunk: ByteArray, fileKey: ByteArray): ByteArray {
        Sodium.init()
        if (index !in 0 until header.chunkCount) throw CryptoException("chunk $index out of range")
        if (cipherChunk.size != header.plainChunkLength(index) + TAG_BYTES) throw CryptoException("chunk $index has the wrong length")
        return try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                cipherChunk.asUByteArray(), chunkAd(header, index).asUByteArray(), nonce(header, index).asUByteArray(),
                fileKey.asUByteArray(),
            ).asByteArray()
        } catch (e: Exception) {
            throw CryptoException("chunk $index did not decrypt", e)
        }
    }

    private fun nonce(header: Header, index: Long) = concat(header.noncePrefix, u64(index))

    private fun chunkAd(header: Header, index: Long): ByteArray {
        val isFinal: Byte = if (index == header.chunkCount - 1) 1 else 0
        return concat(header.bytes, u64(index), byteArrayOf(isFinal))
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw CryptoException("input ended after ${read} of $length bytes in a chunk")
            read += n
        }
    }
}
