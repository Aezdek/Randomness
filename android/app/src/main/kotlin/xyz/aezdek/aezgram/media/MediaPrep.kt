package xyz.aezdek.aezgram.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import xyz.aezdek.aezgram.client.Blurhash
import xyz.aezdek.aezgram.client.PreparedMedia
import xyz.aezdek.aezgram.client.UserFacingException
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

enum class MediaKind(val imageEdge: Int, val videoEdge: Int, val bitrate: Int, val maxSeconds: Int) {
    POST(2048, 1920, 5_000_000, 600),
    REEL(1440, 1920, 5_000_000, 180),
    STORY(1440, 1920, 3_500_000, 60),
    MESSAGE(2048, 1280, 2_500_000, 600),
    AVATAR(640, 640, 1_000_000, 10),
}

/** Photo edits applied while re-encoding: an optional crop (fractions of the image) and a colour filter. */
data class ImageEdit(val crop: RectF? = null, val filter: ColorMatrix? = null, val vignette: Float = 0f)

/**
 * The media pipeline, mirroring web/src/core/media.ts: the server can't transcode ciphertext, so everything is
 * normalised here before it is encrypted. Photos become JPEG ≤ 2048 px (which also drops EXIF and GPS); videos become
 * H.264/AAC MP4 at ≤ 1080p, with HDR from Samsung cameras tone-mapped to SDR so browsers don't show it washed out.
 */
object MediaPrep {
    private fun outFile(context: Context, ext: String): File =
        File(context.cacheDir, "prepared").apply { mkdirs() }.let { File(it, "${UUID.randomUUID()}.$ext") }

    fun blurhashOf(bitmap: Bitmap): String {
        val w = 32
        val h = (32f * bitmap.height / bitmap.width).roundToInt().coerceIn(1, 64)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        return Blurhash.encode(pixels, w, h)
    }

    /** Decodes honouring EXIF orientation (HEIC included), at a size just above what we need. */
    fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (edge / (sample * 2) >= maxEdge) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: Exception) {
        throw UserFacingException("That photo can't be opened. Try a JPEG, PNG or HEIC.")
    }

    suspend fun prepareImage(context: Context, uri: Uri, kind: MediaKind, edit: ImageEdit = ImageEdit()): PreparedMedia =
        withContext(Dispatchers.Default) {
            val source = decode(context, uri, kind.imageEdge * 2)
            try {
                prepareBitmap(context, source, kind, edit)
            } finally {
                source.recycle()
            }
        }

    fun prepareBitmap(context: Context, source: Bitmap, kind: MediaKind, edit: ImageEdit = ImageEdit()): PreparedMedia {
        val crop = edit.crop?.let {
            Rect((it.left * source.width).roundToInt(), (it.top * source.height).roundToInt(),
                (it.right * source.width).roundToInt(), (it.bottom * source.height).roundToInt())
        } ?: Rect(0, 0, source.width, source.height)
        val scale = minOf(1f, kind.imageEdge.toFloat() / maxOf(crop.width(), crop.height()))
        val width = (crop.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (crop.height() * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        edit.filter?.let { paint.colorFilter = ColorMatrixColorFilter(it) }
        canvas.drawBitmap(source, crop, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        if (edit.vignette > 0f) {
            val shader = android.graphics.RadialGradient(width / 2f, height / 2f, maxOf(width, height) * 0.75f,
                intArrayOf(0, android.graphics.Color.argb((165 * edit.vignette).roundToInt(), 0, 0, 0)), floatArrayOf(0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
        }
        val file = outFile(context, "jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val hash = blurhashOf(out)
        out.recycle()
        return PreparedMedia(file, "image/jpeg", width, height, blurhash = hash)
    }

    /** Transcodes with Media3 Transformer in one pass: trim, downscale, HDR→SDR, H.264/AAC. */
    @OptIn(UnstableApi::class)
    suspend fun prepareVideo(
        context: Context,
        uri: Uri,
        kind: MediaKind,
        trimMs: LongRange? = null,
        onProgress: (Float) -> Unit = {},
    ): PreparedMedia {
        val info = withContext(Dispatchers.IO) { probe(context, uri) }
        val maxMs = kind.maxSeconds * 1000L
        val start = trimMs?.first ?: 0L
        val end = minOf(trimMs?.last ?: info.durationMs, start + maxMs, info.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val scale = minOf(1f, kind.videoEdge.toFloat() / maxOf(info.width, info.height).coerceAtLeast(1))
        val width = ((info.width * scale) / 2).roundToInt() * 2
        val height = ((info.height * scale) / 2).roundToInt() * 2
        val output = outFile(context, "mp4")

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val item = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build())
                    .build()
                val edited = EditedMediaItem.Builder(item)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                val composition = Composition.Builder(EditedMediaItemSequence(edited))
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build()
                val handler = Handler(Looper.getMainLooper())
                lateinit var transformer: Transformer
                val poll = object : Runnable {
                    val holder = ProgressHolder()
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        handler.postDelayed(this, 250)
                    }
                }
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(kind.bitrate).build())
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(UserFacingException("This video can't be converted on this phone (${exportException.errorCodeName})"))
                        }
                    })
                    .build()
                transformer.start(composition, output.absolutePath)
                handler.post(poll)
                cont.invokeOnCancellation {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
        }

        val result = withContext(Dispatchers.IO) { probe(context, Uri.fromFile(output)) }
        val poster = withContext(Dispatchers.Default) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                val frame = retriever.getFrameAtTime(minOf(500_000L, result.durationMs * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let { prepareBitmap(context, it, MediaKind.POST).also { _ -> it.recycle() } }
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
        return PreparedMedia(output, "video/mp4", result.width, result.height, result.durationMs, poster?.blurhash, poster)
    }

    class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

    /** Display size (rotation applied) and duration. */
    fun probe(context: Context, uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            return if (rotation % 180 != 0) VideoInfo(h, w, duration) else VideoInfo(w, h, duration)
        } catch (e: Exception) {
            throw UserFacingException("That video can't be opened")
        } finally {
            retriever.release()
        }
    }

    fun isVideo(context: Context, uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("video/") == true
}

/** Voice notes: mono AAC in MP4, 64 kbps, the same format the web client produces. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start() {
        val out = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(64_000)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
    }

    /** Stops and returns the recording, or null if it was too short to keep. */
    fun stop(): PreparedMedia? {
        val r = recorder ?: return null
        recorder = null
        val duration = System.currentTimeMillis() - startedAt
        runCatching { r.stop() }
        r.release()
        val out = file ?: return null
        if (duration < 700) {
            out.delete()
            return null
        }
        return PreparedMedia(out, "audio/mp4", 0, 0, duration)
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
    }

    val elapsedMs: Long get() = if (recorder != null) System.currentTimeMillis() - startedAt else 0
}

/** The photo filters, as colour matrices (the same looks as the web's CSS filters). */
object Filters {
    data class Filter(val name: String, val matrix: ColorMatrix?, val vignette: Float = 0f)

    private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }
    private fun contrast(c: Float, brightness: Float = 0f) = ColorMatrix(
        floatArrayOf(c, 0f, 0f, 0f, brightness + (1 - c) * 128, 0f, c, 0f, 0f, brightness + (1 - c) * 128, 0f, 0f, c, 0f, brightness + (1 - c) * 128, 0f, 0f, 0f, 1f, 0f),
    )
    private fun tint(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    private fun ColorMatrix.then(other: ColorMatrix) = ColorMatrix(this).apply { postConcat(other) }

    val all = listOf(
        Filter("Normal", null),
        Filter("Golden", contrast(1.08f, 6f).then(tint(1.08f, 1.0f, 0.88f)).then(saturation(1.15f))),
        Filter("Dusk", contrast(1.05f).then(tint(1.02f, 0.94f, 1.08f)), vignette = 0.5f),
        Filter("Clarity", contrast(1.18f, -4f).then(saturation(1.2f))),
        Filter("Fade", contrast(0.86f, 18f).then(saturation(0.85f))),
        Filter("Cool", tint(0.92f, 1.0f, 1.1f).then(saturation(1.05f))),
        Filter("Mono", saturation(0f).then(contrast(1.1f))),
        Filter("Noir", saturation(0f).then(contrast(1.35f, -10f)), vignette = 0.7f),
    )
}
