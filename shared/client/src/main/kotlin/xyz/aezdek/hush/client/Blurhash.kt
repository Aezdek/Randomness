package xyz.aezdek.hush.client

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.withSign

/**
 * BlurHash (https://blurha.sh), the same algorithm the web client uses: a ~30-character placeholder stored inside the
 * encrypted media reference, decoded instantly while the real image downloads and decrypts.
 */
object Blurhash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    /** [pixels] are ARGB ints, row by row. */
    fun encode(pixels: IntArray, width: Int, height: Int, componentsX: Int = 4, componentsY: Int = 3): String {
        require(componentsX in 1..9 && componentsY in 1..9 && pixels.size >= width * height)
        val factors = Array(componentsX * componentsY) { FloatArray(3) }
        for (j in 0 until componentsY) for (i in 0 until componentsX) {
            val normalisation = if (i == 0 && j == 0) 1f else 2f
            var r = 0f; var g = 0f; var b = 0f
            for (y in 0 until height) for (x in 0 until width) {
                val basis = normalisation * cos(PI * i * x / width).toFloat() * cos(PI * j * y / height).toFloat()
                val p = pixels[y * width + x]
                r += basis * srgbToLinear((p shr 16) and 0xff)
                g += basis * srgbToLinear((p shr 8) and 0xff)
                b += basis * srgbToLinear(p and 0xff)
            }
            val scale = 1f / (width * height)
            factors[j * componentsX + i] = floatArrayOf(r * scale, g * scale, b * scale)
        }
        val out = StringBuilder()
        out.append(base83(componentsX - 1 + (componentsY - 1) * 9, 1))
        val ac = factors.drop(1)
        val maximum = if (ac.isEmpty()) 1f else {
            val actual = ac.maxOf { f -> f.maxOf { abs(it) } }
            val quantised = floor(actual * 166 - 0.5).toInt().coerceIn(0, 82)
            out.append(base83(quantised, 1))
            (quantised + 1) / 166f
        }
        if (ac.isEmpty()) out.append(base83(0, 1))
        val dc = factors[0]
        out.append(base83((linearToSrgb(dc[0]) shl 16) + (linearToSrgb(dc[1]) shl 8) + linearToSrgb(dc[2]), 4))
        for (f in ac) {
            fun q(v: Float) = floor(signPow(v / maximum, 0.5f) * 9 + 9.5f).toInt().coerceIn(0, 18)
            out.append(base83(q(f[0]) * 19 * 19 + q(f[1]) * 19 + q(f[2]), 2))
        }
        return out.toString()
    }

    /** ARGB pixels of a [width]×[height] image, or null if [hash] is malformed. */
    fun decode(hash: String, width: Int, height: Int, punch: Float = 1f): IntArray? {
        if (hash.length < 6) return null
        val size = decode83(hash, 0, 1) ?: return null
        val nx = size % 9 + 1
        val ny = size / 9 + 1
        if (hash.length != 4 + 2 * nx * ny) return null
        val maximum = ((decode83(hash, 1, 2) ?: return null) + 1) / 166f * punch
        val colors = Array(nx * ny) { index ->
            if (index == 0) {
                val v = decode83(hash, 2, 6) ?: return null
                floatArrayOf(srgbToLinear(v shr 16), srgbToLinear((v shr 8) and 0xff), srgbToLinear(v and 0xff))
            } else {
                val v = decode83(hash, 4 + index * 2, 6 + index * 2) ?: return null
                floatArrayOf(signPow(((v / (19 * 19)) - 9) / 9f, 2f) * maximum, signPow((((v / 19) % 19) - 9) / 9f, 2f) * maximum,
                    signPow(((v % 19) - 9) / 9f, 2f) * maximum)
            }
        }
        val cosX = Array(nx) { i -> FloatArray(width) { x -> cos(PI * x * i / width).toFloat() } }
        val cosY = Array(ny) { j -> FloatArray(height) { y -> cos(PI * y * j / height).toFloat() } }
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0f; var g = 0f; var b = 0f
            for (j in 0 until ny) for (i in 0 until nx) {
                val basis = cosX[i][x] * cosY[j][y]
                val c = colors[i + j * nx]
                r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
            }
            out[y * width + x] = (0xff shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
        }
        return out
    }

    private fun decode83(text: String, from: Int, to: Int): Int? {
        var value = 0
        for (i in from until to) {
            val digit = CHARS.indexOf(text[i])
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    private fun base83(value: Int, length: Int): String {
        val out = CharArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            out[i] = CHARS[v % 83]
            v /= 83
        }
        return String(out)
    }

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) (v * 12.92f * 255 + 0.5f).toInt() else ((1.055f * v.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).roundToInt()
    }

    private fun signPow(value: Float, exp: Float): Float = abs(value).pow(exp).withSign(sign(value))

    @Suppress("unused")
    private fun clampMax(a: Float, b: Float) = max(a, b)
}
