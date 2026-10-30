package xyz.aezdek.hush.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.RangedMedia
import xyz.aezdek.hush.protocol.MediaRef
import java.io.IOException

/**
 * ExoPlayer reads `hush://blob/<id>` through this: each read fetches the AGM1 chunks covering it (HTTP Range),
 * decrypts and returns plaintext, so videos start at once and seek anywhere without ever touching the disk.
 */
@OptIn(UnstableApi::class)
class EncryptedDataSource : BaseDataSource(true) {
    private var media: RangedMedia? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var window = ByteArray(0)
    private var windowStart = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val ref = refOf(dataSpec.uri) ?: throw IOException("Unknown media ${dataSpec.uri}")
        val media = try {
            runBlocking { Graph.media.ranged(ref) }
        } catch (e: Exception) {
            throw IOException("Media not available", e)
        }
        this.media = media
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else media.length - position
        if (remaining < 0) throw IOException("Range outside the file")
        window = ByteArray(0)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val media = media ?: throw IOException("Not open")
        if (position < windowStart || position >= windowStart + window.size) {
            val want = minOf(WINDOW.toLong(), media.length - position).toInt()
            window = try {
                runBlocking { media.read(position, want) }
            } catch (e: Exception) {
                throw IOException("Could not read media", e)
            }
            windowStart = position
            if (window.isEmpty()) return C.RESULT_END_OF_INPUT
        }
        val available = (windowStart + window.size - position).toInt()
        val count = minOf(length.toLong(), available.toLong(), remaining).toInt()
        System.arraycopy(window, (position - windowStart).toInt(), buffer, offset, count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        media = null
        window = ByteArray(0)
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedDataSource()
    }

    companion object {
        /** 1 MiB per fetch: 16 chunks, few round trips, bounded memory. */
        private const val WINDOW = 1 shl 20

        fun uriOf(ref: MediaRef): Uri {
            Graph.media.refs[ref.blobId] = ref
            return Uri.parse("hush://blob/${ref.blobId}")
        }

        private fun refOf(uri: Uri): MediaRef? = uri.lastPathSegment?.let { Graph.media.refs[it] }
    }
}
