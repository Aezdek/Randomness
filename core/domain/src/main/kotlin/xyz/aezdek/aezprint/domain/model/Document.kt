package xyz.aezdek.aezprint.domain.model

import kotlinx.serialization.Serializable

/** A PDF copied into app storage, so it can be re-uploaded for reprints after the server copy expires. */
@Serializable
data class LocalDocument(
    val id: String,
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val pages: List<SerializablePageSize> = emptyList(),
    val thumbnailPath: String? = null,
    val lastOpenedAt: Long = 0,
    val hiddenFromRecents: Boolean = false,
)

@Serializable
data class SerializablePageSize(val widthPt: Double, val heightPt: Double) {
    fun toPageSize() = PageSize(widthPt, heightPt)
}

data class RemoteDocument(
    val documentId: String,
    val pageCount: Int,
    val pages: List<PageSize>,
    val expiresAt: Long,
)

data class Render(
    val renderId: String,
    val sheetCount: Int,
    val sides: Int,
) {
    /** Sheet images are addressed per face: front, back, front, back… */
    val faceCount: Int get() = sheetCount * sides
}
