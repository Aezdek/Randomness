package xyz.aezdek.aezprint.domain.repository

import xyz.aezdek.aezprint.domain.model.Job
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.RemoteDocument
import xyz.aezdek.aezprint.domain.model.Render

/** The print API (v1). Implemented by the real HTTP client and by the in-app fake server. */
interface PrintServer {
    suspend fun printer(): Printer

    suspend fun upload(document: LocalDocument, onProgress: (Float) -> Unit = {}): RemoteDocument

    suspend fun render(documentId: String, options: PrintOptions): Render

    /** PNG bytes for one face of the rendered output; [face] is 1-based. */
    suspend fun sheet(renderId: String, face: Int, widthPx: Int): ByteArray

    suspend fun submit(documentId: String, options: PrintOptions): String

    suspend fun job(jobId: String): Job

    suspend fun continueJob(jobId: String)

    suspend fun cancel(jobId: String)

    suspend fun jobs(limit: Int): List<Job>
}

sealed class PrintServerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline(cause: Throwable) : PrintServerException("Can't reach the print server", cause)
    class NotConfigured : PrintServerException("Add your server credentials in Settings")
    class Unauthorized(message: String) : PrintServerException(message)
    class NotFound(message: String) : PrintServerException(message)

    /** HTTP 422: [fields] are option paths such as `poster.overlapMm`. */
    class Unsupported(message: String, val fields: List<String>) : PrintServerException(message)
    class Server(val status: Int, val error: String, message: String) : PrintServerException(message)
}
