package xyz.aezdek.aezprint.data.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import xyz.aezdek.aezprint.data.di.IoDispatcher
import xyz.aezdek.aezprint.data.store.StoredDocuments
import xyz.aezdek.aezprint.data.store.StoredRecords
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DataStore<StoredDocuments>,
    private val records: DataStore<StoredRecords>,
    private val inspector: PdfInspector,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DocumentRepository {

    private val documentsDir get() = File(context.filesDir, "documents")
    private val thumbnailsDir get() = File(context.filesDir, "thumbnails")

    override val recents: Flow<List<LocalDocument>> = store.data.map { state ->
        state.documents.filterNot { it.hiddenFromRecents }.sortedByDescending { it.lastOpenedAt }.take(MAX_RECENTS)
    }

    override suspend fun import(uri: String): LocalDocument = withContext(io) {
        val parsed = Uri.parse(uri)
        val resolver = context.contentResolver
        val displayName = queryName(parsed) ?: parsed.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
        val id = UUID.randomUUID().toString()
        val target = File(documentsDir, "$id.pdf").apply { parentFile?.mkdirs() }

        val input = if (parsed.scheme == "file") File(requireNotNull(parsed.path)).inputStream() else resolver.openInputStream(parsed)
        (input ?: throw FileNotFoundException("Couldn't open $displayName")).use { stream ->
            target.outputStream().use { stream.copyTo(it) }
        }
        val header = target.inputStream().use { stream -> ByteArray(5).also { stream.read(it) } }
        if (!header.decodeToString().startsWith("%PDF")) {
            target.delete()
            throw UnreadablePdfException("$displayName isn't a PDF")
        }

        val existing = store.data.first().documents.firstOrNull {
            it.displayName == displayName && it.sizeBytes == target.length() && File(it.path).exists()
        }
        if (existing != null) {
            target.delete()
            touch(existing.id)
            return@withContext existing.copy(lastOpenedAt = System.currentTimeMillis(), hiddenFromRecents = false)
        }

        val thumbnail = File(thumbnailsDir, "$id.png")
        val info = try {
            inspector.inspect(target, thumbnail)
        } catch (e: UnreadablePdfException) {
            target.delete()
            throw e
        }
        val document = LocalDocument(
            id = id,
            displayName = displayName.let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" },
            path = target.absolutePath,
            sizeBytes = target.length(),
            pageCount = info.pages.size,
            pages = info.pages,
            thumbnailPath = thumbnail.takeIf { it.exists() }?.absolutePath,
            lastOpenedAt = System.currentTimeMillis(),
        )
        store.updateData { it.copy(documents = it.documents + document) }
        prune()
        document
    }

    override suspend fun get(id: String): LocalDocument? =
        store.data.first().documents.firstOrNull { it.id == id }?.takeIf { File(it.path).exists() }

    override suspend fun touch(id: String) {
        store.updateData { state ->
            state.copy(
                documents = state.documents.map {
                    if (it.id == id) it.copy(lastOpenedAt = System.currentTimeMillis(), hiddenFromRecents = false) else it
                },
            )
        }
    }

    override suspend fun remove(id: String) {
        store.updateData { state ->
            state.copy(documents = state.documents.map { if (it.id == id) it.copy(hiddenFromRecents = true) else it })
        }
        prune()
    }

    /** Deletes files no longer reachable from recents or from print history (which needs them to reprint). */
    private suspend fun prune() = withContext(io) {
        val referenced = records.data.first().records.map { it.documentId }.toSet()
        store.updateData { state ->
            val visible = state.documents.filterNot { it.hiddenFromRecents }
                .sortedByDescending { it.lastOpenedAt }
                .take(MAX_RECENTS)
                .map { it.id }
                .toSet()
            val (keep, drop) = state.documents.partition { it.id in visible || it.id in referenced }
            drop.forEach { document ->
                File(document.path).delete()
                document.thumbnailPath?.let { File(it).delete() }
            }
            state.copy(documents = keep.map { if (it.id in visible) it else it.copy(hiddenFromRecents = true) })
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val MAX_RECENTS = 20
    }
}
