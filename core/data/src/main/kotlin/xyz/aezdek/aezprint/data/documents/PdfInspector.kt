package xyz.aezdek.aezprint.data.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import xyz.aezdek.aezprint.domain.model.SerializablePageSize
import java.io.File
import javax.inject.Inject

class UnreadablePdfException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads page geometry and a first-page thumbnail. Printing output always comes from the server. */
class PdfInspector @Inject constructor() {

    data class Info(val pages: List<SerializablePageSize>)

    fun inspect(file: File, thumbnail: File?, thumbnailWidth: Int = 480): Info {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            descriptor.close()
            throw UnreadablePdfException("This PDF is password protected", e)
        } catch (e: Exception) {
            descriptor.close()
            throw UnreadablePdfException("This file isn't a readable PDF", e)
        }
        return renderer.use {
            val pages = (0 until renderer.pageCount).map { index ->
                renderer.openPage(index).use { page -> SerializablePageSize(page.width.toDouble(), page.height.toDouble()) }
            }
            if (thumbnail != null && renderer.pageCount > 0) writeThumbnail(renderer, thumbnail, thumbnailWidth)
            Info(pages)
        }
    }

    private fun writeThumbnail(renderer: PdfRenderer, target: File, width: Int) {
        renderer.openPage(0).use { page ->
            val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
