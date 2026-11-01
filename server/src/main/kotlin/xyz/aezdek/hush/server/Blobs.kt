package xyz.aezdek.hush.server

import io.ktor.http.HttpStatusCode
import xyz.aezdek.hush.protocol.BlobDto
import xyz.aezdek.hush.protocol.UploadState
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Encrypted media on disk, content-addressed by the SHA-256 of the ciphertext. Uploads are resumable and arrive in
 * pieces of at most [CHUNK] bytes, well under Cloudflare's 100 MB request limit. The server never decodes media: it
 * cannot, everything here is AGM1 ciphertext.
 */
class Blobs(private val db: Db, private val clock: Clock, private val config: ServerConfig, private val objects: Objects) {

    companion object {
        /** The largest piece one PUT may carry. Big pieces mean fewer round trips through the tunnel (Cloudflare allows 100 MB). */
        const val CHUNK = 32 * 1024 * 1024
        private const val DAY = 24L * 60 * 60 * 1000
        private val BLOB_ID = Regex("^[0-9a-f]{64}$")
    }

    init {
        config.blobsDir.mkdirs()
        config.uploadsDir.mkdirs()
    }

    fun uploadsBlocked(): Boolean = config.dataDir.usableSpace < config.minFreeBytes

    fun start(userId: String, size: Long): UploadState {
        if (size <= 0 || size > config.maxUploadBytes) badRequest("Files can be up to ${config.maxUploadBytes / (1024 * 1024)} MB")
        if (config.dataDir.usableSpace - size < config.minFreeBytes) {
            throw ApiException(HttpStatusCode.InsufficientStorage, "disk_full", "The server is low on disk space; uploads are paused")
        }
        val id = Ids.random(16)
        db.tx { exec("INSERT INTO uploads (id, uploader_id, size, created_at) VALUES (?, ?, ?, ?)", id, userId, size, clock.now()) }
        partFile(id).createNewFile()
        return UploadState(id, size, 0, CHUNK)
    }

    fun state(userId: String, uploadId: String): UploadState = db.tx {
        one("SELECT size, received FROM uploads WHERE id = ? AND uploader_id = ?", uploadId, userId) {
            UploadState(uploadId, it.getLong("size"), it.getLong("received"), CHUNK)
        } ?: notFound("No such upload")
    }

    /** Appends one piece. [offset] must equal what the server already has, which makes retries safe. */
    fun append(userId: String, uploadId: String, offset: Long, body: InputStream): UploadState {
        val state = state(userId, uploadId)
        if (offset != state.received) conflict("Expected offset ${state.received}", "offset")
        val file = partFile(uploadId)
        var written = 0L
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(offset)
            raf.seek(offset)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = body.read(buffer)
                if (n < 0) break
                written += n
                if (written > CHUNK || offset + written > state.size) {
                    raf.setLength(offset)
                    badRequest("Piece too large")
                }
                raf.write(buffer, 0, n)
            }
        }
        val received = offset + written
        db.tx { exec("UPDATE uploads SET received = ? WHERE id = ?", received, uploadId) }
        return state.copy(received = received)
    }

    fun complete(userId: String, uploadId: String): BlobDto {
        val state = state(userId, uploadId)
        if (state.received != state.size) conflict("Upload incomplete: ${state.received} of ${state.size} bytes", "incomplete")
        val part = partFile(uploadId)
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val blobId = digest.digest().hex()
        val target = blobFile(blobId)
        target.parentFile.mkdirs()
        if (target.exists()) part.delete() else Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        db.tx {
            exec("INSERT OR IGNORE INTO blobs (id, size, uploader_id, created_at) VALUES (?, ?, ?, ?)", blobId, state.size, userId, clock.now())
            exec("DELETE FROM uploads WHERE id = ?", uploadId)
        }
        return BlobDto(blobId, state.size)
    }

    /** The ciphertext file, if [userId] may read it. */
    fun file(userId: String, blobId: String): File {
        if (!BLOB_ID.matches(blobId)) notFound()
        val allowed = db.tx { with(objects) { canReadBlob(userId, blobId) } }
        val file = blobFile(blobId)
        if (!allowed || !file.isFile) notFound()
        return file
    }

    /** Deletes media nothing refers to any more (a day's grace for uploads still being attached) and stale uploads. */
    fun gc(): Int {
        val cutoff = clock.now() - DAY
        val (orphans, stale) = db.tx {
            val orphans = strings(
                "SELECT id FROM blobs b WHERE created_at < ? AND NOT EXISTS (SELECT 1 FROM object_blobs ob WHERE ob.blob_id = b.id)", cutoff,
            )
            orphans.forEach { exec("DELETE FROM blobs WHERE id = ?", it) }
            val stale = strings("SELECT id FROM uploads WHERE created_at < ?", cutoff)
            stale.forEach { exec("DELETE FROM uploads WHERE id = ?", it) }
            orphans to stale
        }
        orphans.forEach { blobFile(it).delete() }
        stale.forEach { partFile(it).delete() }
        return orphans.size
    }

    fun dataBytes(): Long = config.dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun blobFile(id: String) = File(File(config.blobsDir, id.take(2)), id)
    private fun partFile(id: String) = File(config.uploadsDir, "$id.part")
}
