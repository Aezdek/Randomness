package xyz.aezdek.daybook.server

import io.ktor.http.ContentType
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * The web app's files, read from the classpath under [root] and kept in memory once requested.
 *
 * Each file gets a strong ETag from its content, so a returning browser revalidates and gets a 304 instead
 * of the multi-megabyte WebAssembly, yet a new server version is never served stale. Compressible files are
 * gzipped once.
 */
class WebAssets(private val root: String, private val loader: ClassLoader = WebAssets::class.java.classLoader) {

    class Asset(val bytes: ByteArray, val gzipped: ByteArray?, val contentType: ContentType, val etag: String)

    private val cache = ConcurrentHashMap<String, Asset>()

    /** True when this build includes the web app. */
    val available: Boolean = loader.getResource("$root/index.html") != null

    /** The file at [path] (relative, "/"-separated), or null when there is none or the path is unsafe. */
    fun get(path: String): Asset? {
        val clean = path.trim('/').ifEmpty { "index.html" }
        if (!SAFE_PATH.matches(clean) || clean.split('/').any { it == ".." || it == "." }) return null
        cache[clean]?.let { return it }
        val bytes = loader.getResourceAsStream("$root/$clean")?.use { it.readBytes() } ?: return null
        val type = contentTypeFor(clean)
        val gzipped = if (isCompressible(clean) && bytes.size > 1024) gzip(bytes) else null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val etag = "\"" + digest.take(16).joinToString("") { "%02x".format(it) } + "\""
        return Asset(bytes, gzipped, type, etag).also { cache[clean] = it }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream(bytes.size / 3).use { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
            out.toByteArray()
        }

    companion object {
        private val SAFE_PATH = Regex("^[A-Za-z0-9._/-]+$")

        fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> ContentType.Text.Html.withParameter("charset", "utf-8")
            "mjs", "js" -> ContentType("text", "javascript").withParameter("charset", "utf-8")
            "wasm" -> ContentType("application", "wasm")
            "json" -> ContentType.Application.Json
            "webmanifest" -> ContentType("application", "manifest+json")
            "css" -> ContentType.Text.CSS
            "png" -> ContentType.Image.PNG
            "svg" -> ContentType.Image.SVG
            "ttf" -> ContentType("font", "ttf")
            "txt" -> ContentType.Text.Plain
            else -> ContentType.Application.OctetStream
        }

        private fun isCompressible(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase() in setOf("html", "mjs", "js", "wasm", "json", "webmanifest", "css", "svg", "ttf", "txt")
    }
}
