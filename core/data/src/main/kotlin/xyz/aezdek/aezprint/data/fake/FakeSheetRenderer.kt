package xyz.aezdek.aezprint.data.fake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.aezdek.aezprint.domain.layout.Face
import xyz.aezdek.aezprint.domain.layout.Placement
import xyz.aezdek.aezprint.domain.layout.Rect
import xyz.aezdek.aezprint.domain.model.PrintOptions
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Draws imposed faces the way the server would, so the fake preview behaves like the real one. */
@Singleton
class FakeSheetRenderer @Inject constructor() {

    private val mutex = Mutex()

    suspend fun render(pdf: File, face: Face, options: PrintOptions, widthPx: Int): ByteArray = mutex.withLock {
        val width = widthPx.coerceIn(64, MAX_WIDTH)
        val scale = width / face.widthPt
        val height = (face.heightPt * scale).toInt().coerceAtLeast(1)
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(Color.WHITE)
        val canvas = Canvas(sheet)

        val rendered = runCatching {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (placement in face.placements) {
                        if (placement.page - 1 !in 0 until renderer.pageCount) continue
                        renderer.openPage(placement.page - 1).use { page ->
                            drawPage(sheet, page, placement.frame, placement.clip, scale)
                        }
                    }
                }
            }
        }
        // Environments without a native PDF renderer (Robolectric) still get a faithful layout.
        if (rendered.isFailure) face.placements.forEach { drawPlaceholder(canvas, it, scale) }

        val stroke = (scale * 0.75).toFloat().coerceAtLeast(1f)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        face.borders.forEach { canvas.drawRect(it.toPx(scale), ink) }
        face.cutMarks?.let { drawCutMarks(canvas, it.toPx(scale), ink, (scale * 10).toFloat()) }
        face.label?.let { label ->
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = (scale * 7).toFloat().coerceAtLeast(8f)
            }
            canvas.drawText(label, (scale * 14).toFloat(), height - (scale * 3).toFloat(), text)
        }

        val output = monochrome(sheet, lighten = options.tonerSave)
        sheet.recycle()
        ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.PNG, 100, stream)
            output.recycle()
            stream.toByteArray()
        }
    }

    private fun drawPage(sheet: Bitmap, page: PdfRenderer.Page, frame: Rect, clip: Rect?, scale: Double) {
        val frameClip = clip?.let(frame::intersect) ?: frame
        val bounds = Rect(0.0, 0.0, sheet.width / scale, sheet.height / scale)
        val visible = frameClip.intersect(bounds) ?: return

        val canvas = Canvas(sheet)
        canvas.drawRect(visible.toPx(scale), Paint().apply { color = Color.WHITE })

        val pageScale = (frame.width / page.width) * scale
        val matrix = Matrix().apply {
            setScale(pageScale.toFloat(), pageScale.toFloat())
            postTranslate((frame.left * scale).toFloat(), (frame.top * scale).toFloat())
        }
        val destClip = visible.toPx(scale).let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt().coerceAtMost(sheet.width), it.bottom.toInt().coerceAtMost(sheet.height))
        }
        if (destClip.isEmpty) return
        page.render(sheet, destClip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    }

    private fun drawPlaceholder(canvas: Canvas, placement: Placement, scale: Double) {
        val area = (placement.clip?.let(placement.frame::intersect) ?: placement.frame).toPx(scale)
        canvas.drawRect(area, Paint().apply { color = Color.rgb(236, 236, 236) })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = (area.height() / 6).coerceIn(10f, 120f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(placement.page.toString(), area.centerX(), area.centerY() + text.textSize / 3, text)
    }

    private fun drawCutMarks(canvas: Canvas, area: RectF, paint: Paint, length: Float) {
        val corners = listOf(area.left to area.top, area.right to area.top, area.left to area.bottom, area.right to area.bottom)
        for ((x, y) in corners) {
            val dx = if (x == area.left) -1 else 1
            val dy = if (y == area.top) -1 else 1
            canvas.drawLine(x, y, x + dx * length, y, paint)
            canvas.drawLine(x, y, x, y + dy * length, paint)
        }
    }

    private fun monochrome(source: Bitmap, lighten: Boolean): Bitmap {
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        if (lighten) {
            // Toner save prints roughly half density: pull every channel halfway towards white.
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.5f, 0f, 0f, 0f, 127f,
                        0f, 0.5f, 0f, 0f, 127f,
                        0f, 0f, 0.5f, 0f, 127f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return result
    }

    private fun Rect.toPx(scale: Double) =
        RectF((left * scale).toFloat(), (top * scale).toFloat(), (right * scale).toFloat(), (bottom * scale).toFloat())

    private companion object {
        const val MAX_WIDTH = 2400
    }
}
