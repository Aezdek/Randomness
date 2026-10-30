package xyz.aezdek.hush.client

import kotlinx.coroutines.delay
import kotlinx.serialization.serializer
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.MediaCipher
import xyz.aezdek.hush.crypto.Sodium
import xyz.aezdek.hush.protocol.BlobDto
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.StartUploadRequest
import xyz.aezdek.hush.protocol.UploadState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

/** A file the platform has already normalised to the shared media spec (JPEG ≤ 2048 px, H.264/AAC MP4, …). */
class PreparedMedia(
    val file: File,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
    val blurhash: String? = null,
    val poster: PreparedMedia? = null,
)

/**
 * Encrypted media: AGM1-encrypts while uploading in resumable pieces (memory stays bounded however large the file),
 * and decrypts whole files or arbitrary byte ranges for streaming playback.
 */
class MediaStore(private val api: Api) {

    /** Uploads [media] (and its poster) and returns the reference that goes inside an encrypted payload. */
    suspend fun upload(media: PreparedMedia, alt: String? = null, onProgress: (Float) -> Unit = {}): MediaRef {
        val poster = media.poster?.let { upload(it) }
        val size = media.file.length()
        val (blobId, key) = media.file.inputStream().use { uploadEncrypted(it, size, onProgress) }
        return MediaRef(
            blobId = blobId, key = B64.encode(key), mime = media.mime, size = size, width = media.width, height = media.height,
            durationMs = media.durationMs, blurhash = media.blurhash, poster = poster, alt = alt?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun uploadEncrypted(input: InputStream, plainLength: Long, onProgress: (Float) -> Unit = {}): Pair<String, ByteArray> {
        val key = MediaCipher.newFileKey()
        val total = MediaCipher.cipherLength(plainLength)
        val start: UploadState = api.post("/uploads", StartUploadRequest(total))
        val pieceTarget = minOf(start.chunkSize, 4 * 1024 * 1024)
        var sent = 0L
        val buffer = ByteArrayOutputStream(pieceTarget + MediaCipher.DEFAULT_CHUNK + 64)

        suspend fun flush() {
            val body = buffer.toByteArray()
            buffer.reset()
            var attempt = 0
            while (true) {
                try {
                    val state = api.putBytes("/uploads/${start.uploadId}?offset=$sent", body, serializer<UploadState>())
                    sent = state.received
                    break
                } catch (e: Exception) {
                    if (attempt >= 4) throw e
                    if (e is ApiException && e.code == "offset") {
                        val state: UploadState = api.get("/uploads/${start.uploadId}")
                        if (state.received == sent + body.size) {
                            sent = state.received
                            break
                        }
                    }
                    delay(1000L shl attempt)
                    attempt++
                }
            }
            onProgress(sent.toFloat() / total)
        }

        val cipher = ChunkSealer(plainLength, key)
        buffer.write(cipher.headerBytes)
        val plain = ByteArray(MediaCipher.DEFAULT_CHUNK)
        for (index in 0 until cipher.chunkCount) {
            val length = cipher.plainChunkLength(index)
            readFully(input, plain, length)
            buffer.write(cipher.seal(index, plain.copyOf(length)))
            if (buffer.size() >= pieceTarget) flush()
        }
        if (buffer.size() > 0) flush()
        val done: BlobDto = api.post("/uploads/${start.uploadId}/complete")
        return done.blobId to key
    }

    /** The whole decrypted file (images, voice notes, small videos). */
    suspend fun download(ref: MediaRef): ByteArray = MediaCipher.decrypt(api.blob(ref.blobId), B64.decode(ref.key))

    /** Opens [ref] for random access: the header is fetched once, then any plaintext range maps to whole chunks. */
    suspend fun openRanged(ref: MediaRef): RangedMedia {
        val header = MediaCipher.parseHeader(api.blob(ref.blobId, 0, MediaCipher.HEADER_BYTES.toLong()))
        return RangedMedia(api, ref.blobId, header, B64.decode(ref.key))
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw java.io.EOFException("file ended early")
            read += n
        }
    }
}

/** AGM1 sealing one chunk at a time, so the upload can suspend for network I/O between chunks. */
private class ChunkSealer(plainLength: Long, private val key: ByteArray) {
    val headerBytes: ByteArray
    private val header: MediaCipher.Header

    init {
        val noncePrefix = Sodium.random(16)
        headerBytes = ByteBuffer.allocate(MediaCipher.HEADER_BYTES)
            .put("AGM1".encodeToByteArray()).putInt(MediaCipher.DEFAULT_CHUNK).putLong(plainLength).put(noncePrefix).array()
        header = MediaCipher.Header(MediaCipher.DEFAULT_CHUNK, plainLength, noncePrefix, headerBytes)
    }

    val chunkCount: Long get() = header.chunkCount
    fun plainChunkLength(index: Long) = header.plainChunkLength(index)
    fun seal(index: Long, plain: ByteArray): ByteArray = MediaCipher.sealChunk(header, index, plain, key)
}

/** Random access into an encrypted blob, for ExoPlayer's DataSource: fetch the covering chunks, decrypt, slice. */
class RangedMedia(private val api: Api, private val blobId: String, val header: MediaCipher.Header, private val key: ByteArray) {
    val length: Long get() = header.plainLength

    /** Plaintext bytes [from, from + count) (clamped to the end of the file). */
    suspend fun read(from: Long, count: Int): ByteArray {
        if (from >= length || count <= 0) return ByteArray(0)
        val end = minOf(length, from + count)
        val first = from / header.chunkSize
        val last = (end - 1) / header.chunkSize
        val cipherStart = header.cipherRange(first).first
        val cipherEnd = header.cipherRange(last).last + 1
        val cipher = api.blob(blobId, cipherStart, cipherEnd)
        val out = ByteArrayOutputStream((end - from).toInt())
        for (index in first..last) {
            val range = header.cipherRange(index)
            val slice = cipher.copyOfRange((range.first - cipherStart).toInt(), (range.last + 1 - cipherStart).toInt())
            val plain = MediaCipher.openChunk(header, index, slice, key)
            val chunkStart = index * header.chunkSize
            val a = maxOf(from, chunkStart) - chunkStart
            val b = minOf(end, chunkStart + plain.size) - chunkStart
            out.write(plain, a.toInt(), (b - a).toInt())
        }
        return out.toByteArray()
    }
}
