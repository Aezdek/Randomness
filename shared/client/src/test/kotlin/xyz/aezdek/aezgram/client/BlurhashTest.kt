package xyz.aezdek.aezgram.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BlurhashTest {
    @Test
    fun theReferenceHashDecodes() {
        // The example from blurha.sh: a mostly blue-grey photo.
        val pixels = Blurhash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 32, 32)
        assertNotNull(pixels)
        assertEquals(32 * 32, pixels!!.size)
        assertTrue(pixels.all { (it ushr 24) == 0xff })
    }

    @Test
    fun encodingThenDecodingKeepsTheAverageColour() {
        val w = 24
        val h = 16
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) 0xffe84393.toInt() else 0xff7c5cff.toInt() }
        val hash = Blurhash.encode(pixels, w, h)
        assertEquals(4 + 2 * 4 * 3, hash.length)
        val decoded = Blurhash.decode(hash, w, h)!!
        fun avg(p: IntArray, shift: Int) = p.map { (it shr shift) and 0xff }.average()
        for (shift in listOf(16, 8, 0)) assertTrue(abs(avg(pixels, shift) - avg(decoded, shift)) < 12)
        // The left half stays pinker than the right.
        assertTrue(((decoded[h / 2 * w + 2] shr 16) and 0xff) > ((decoded[h / 2 * w + w - 3] shr 16) and 0xff))
    }

    @Test
    fun malformedHashesAreRejected() {
        assertNull(Blurhash.decode("nope", 8, 8))
        assertNull(Blurhash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdn", 8, 8))
    }
}
