package xyz.aezdek.aezprint.data.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File

class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {

    override fun contentType() = contentType

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().coerceAtLeast(1)
        var written = 0L
        file.source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, SEGMENT)
                if (read == -1L) break
                sink.write(buffer, read)
                written += read
                onProgress(written.toFloat() / total)
            }
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
