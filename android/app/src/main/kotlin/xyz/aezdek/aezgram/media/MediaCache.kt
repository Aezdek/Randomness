package xyz.aezdek.aezgram.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.aezdek.aezgram.client.AezClient
import xyz.aezdek.aezgram.client.Blurhash
import xyz.aezdek.aezgram.client.RangedMedia
import xyz.aezdek.aezgram.protocol.MediaRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Decrypted images live only in memory: an LRU of bitmaps sized by bytes. The server and the disk only ever hold
 * ciphertext. Concurrent requests for the same blob share one download.
 */
class MediaCache(private val client: AezClient) {
    private val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val blurs = object : LruCache<String, Bitmap>(64) {}
    private val ranged = ConcurrentHashMap<String, RangedMedia>()
    /** Media the video player may open, by blob id (the data source only gets a URI). */
    val refs = ConcurrentHashMap<String, MediaRef>()

    fun cached(ref: MediaRef, maxEdge: Int): Bitmap? = bitmaps.get(key(ref, maxEdge))

    private fun key(ref: MediaRef, maxEdge: Int) = "${ref.blobId}@$maxEdge"

    suspend fun bitmap(ref: MediaRef, maxEdge: Int = 1440): Bitmap = withContext(Dispatchers.IO) {
        val key = key(ref, maxEdge)
        bitmaps.get(key)?.let { return@withContext it }
        locks.getOrPut(key) { Mutex() }.withLock {
            bitmaps.get(key)?.let { return@withLock it }
            val bytes = client.media.download(ref)
            val bitmap = decodeSampled(bytes, maxEdge) ?: throw IllegalStateException("Unreadable image")
            bitmaps.put(key, bitmap)
            bitmap
        }
    }

    /** The decrypted bytes (voice notes, files). */
    suspend fun bytes(ref: MediaRef): ByteArray = withContext(Dispatchers.IO) { client.media.download(ref) }

    suspend fun ranged(ref: MediaRef): RangedMedia = ranged[ref.blobId] ?: client.media.openRanged(ref).also { ranged[ref.blobId] = it }

    fun blur(hash: String?): Bitmap? {
        if (hash.isNullOrBlank()) return null
        blurs.get(hash)?.let { return it }
        val pixels = Blurhash.decode(hash, 32, 32) ?: return null
        val bitmap = Bitmap.createBitmap(pixels, 32, 32, Bitmap.Config.ARGB_8888)
        blurs.put(hash, bitmap)
        return bitmap
    }

    companion object {
        fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
