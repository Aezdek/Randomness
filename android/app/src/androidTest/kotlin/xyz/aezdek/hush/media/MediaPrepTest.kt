package xyz.aezdek.hush.media

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The media pipeline on a real Android runtime (CI runs it on an emulator): what leaves the phone must match the spec
 * the web client also enforces, whatever the camera produced. Uses the web e2e fixtures (see app/build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class MediaPrepTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(name: String): File {
        val file = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun tracks(file: File): Map<String, MediaFormat> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).associate { i ->
                val format = extractor.getTrackFormat(i)
                format.getString(MediaFormat.KEY_MIME)!! to format
            }
        } finally {
            extractor.release()
        }
    }

    @Test
    fun fourKPhoneClipBecomesA1080pH264AacReel() = runBlocking {
        val input = fixture("clip-4k-portrait.mp4")
        val inputInfo = MediaPrep.probe(context, Uri.fromFile(input))
        assertTrue("fixture is 4K portrait", inputInfo.height > inputInfo.width && inputInfo.height >= 3000)

        val prepared = MediaPrep.prepareVideo(context, Uri.fromFile(input), MediaKind.REEL)

        assertEquals("video/mp4", prepared.mime)
        assertEquals(1080, prepared.width)
        assertEquals(1920, prepared.height)
        val formats = tracks(prepared.file)
        assertNotNull("H.264 video", formats[MediaFormat.MIMETYPE_VIDEO_AVC])
        assertNotNull("AAC audio survives", formats[MediaFormat.MIMETYPE_AUDIO_AAC])
        assertTrue("duration kept", (prepared.durationMs ?: 0) > 0)
        assertTrue("has content", prepared.file.length() > 10_000)
        assertNotNull("poster frame", prepared.poster)
        assertNotNull("blurhash", prepared.blurhash)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(prepared.file.absolutePath)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val (shownW, shownH) = if (rotation % 180 == 0) w to h else h to w
            assertEquals("portrait as filmed", 1080 to 1920, shownW to shownH)
        } finally {
            retriever.release()
        }
    }

    @Test
    fun photosAreReencodedAsJpegWithinTheEdgeLimit() = runBlocking {
        val input = fixture("city.jpg")
        val prepared = MediaPrep.prepareImage(context, Uri.fromFile(input), MediaKind.POST)

        assertEquals("image/jpeg", prepared.mime)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(prepared.file.absolutePath, bounds)
        assertEquals(prepared.width, bounds.outWidth)
        assertEquals(prepared.height, bounds.outHeight)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= MediaKind.POST.imageEdge)
        // Re-encoding from pixels means nothing from the camera's EXIF (location above all) survives.
        @Suppress("DEPRECATION")
        assertFalse(ExifInterface(prepared.file.absolutePath).getLatLong(FloatArray(2)))
        assertNotNull(prepared.blurhash)
    }
}
