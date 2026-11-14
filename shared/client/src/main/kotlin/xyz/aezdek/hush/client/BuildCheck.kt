package xyz.aezdek.hush.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.aezdek.hush.protocol.Wire
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The public record of one build, written by CI next to the release (tools/build-manifest.py). */
@Serializable
data class BuildManifest(
    val format: Int = 1,
    val build: Int,
    val version: String = "",
    val commit: String = "",
    val apk: ApkRecord? = null,
    /** Every file of the web app, by path, with its SHA-256. */
    val web: Map<String, String> = emptyMap(),
)

@Serializable
data class ApkRecord(val digest: String, val entries: Int)

/**
 * Checks what a server hands out against the public record of the build it claims to be.
 *
 * A Hush server signs the Android app with its own key and serves the web app itself, so someone who took over the
 * server could hand out a changed app that Android would accept. The record is published where the code is built,
 * outside the server's reach: an update whose contents differ from it is refused, and a changed web app is reported.
 */
object BuildCheck {
    sealed interface Verdict {
        /** The contents are exactly those of the public build. */
        data object Matches : Verdict
        /** There is nothing to compare with: no record for this build, or it couldn't be fetched. */
        data class Unknown(val why: String) : Verdict
        /** The contents differ from the public build: not to be installed or trusted. */
        data class Differs(val what: String) : Verdict
    }

    class ApkDigest(val digest: String, val entries: Int)

    private val SIGNATURE_FILE = Regex("^META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))$")
    private const val MAX_MANIFEST = 2L * 1024 * 1024
    /** The web app's files that a browser runs or lays the page out with. Pictures and fonts can't act. */
    private val ACTIVE = setOf("html", "js", "mjs", "css", "wasm", "svg", "webmanifest", "json")

    /**
     * The fingerprint of an APK's contents: every file in it but the signature files, by name and contents. The same
     * before and after signing. Throws [IllegalArgumentException] for an archive that names a file twice, which
     * could show one thing here and another to Android.
     */
    fun apkDigest(file: File): ApkDigest = ZipFile(file).use { zip ->
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        for (entry in zip.entries()) {
            require(seen.add(entry.name)) { "The same file name is in the app twice" }
            names += entry.name
        }
        val covered = names.filterNot { SIGNATURE_FILE.matches(it) }.map { it to it.toByteArray(Charsets.UTF_8) }
            .sortedWith { a, b -> compareBytes(a.second, b.second) }
        val total = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        for ((name, bytes) in covered) {
            val content = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(zip.getEntry(name)).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    content.update(buffer, 0, n)
                }
            }
            total.update(bytes)
            total.update(0)
            total.update(content.digest())
        }
        ApkDigest(hex(total.digest()), covered.size)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return a.size - b.size
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The build number in a version name: 52 for "1.0.52". Null when it has none. */
    fun buildNumber(version: String): Int? = version.trim().split('.').lastOrNull()?.toIntOrNull()?.takeIf { it > 0 }

    /** Where the records are published: only ever an https address, chosen when the app was built. */
    fun recordUrl(base: String, build: Int): String? {
        val root = base.trim().trimEnd('/')
        if (!root.startsWith("https://") || build <= 0) return null
        return "$root/hush-build-$build.json"
    }

    /**
     * Fetches the public record for [build]. Null when there is none (a build that was never published). Throws
     * when it can't be reached or isn't a record for that build.
     */
    suspend fun fetchRecord(http: OkHttpClient, base: String, build: Int): BuildManifest? = withContext(Dispatchers.IO) {
        val url = recordUrl(base, build) ?: throw UserFacingException("This app wasn't built with a public record to check against.")
        http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw UserFacingException("The public build record couldn't be fetched (${response.code}).")
            if (!response.request.url.isHttps) throw UserFacingException("The public build record came over an insecure connection.")
            val body = response.body.source()
            if (body.request(MAX_MANIFEST + 1)) throw UserFacingException("The public build record is too large to be one.")
            val manifest = runCatching { Wire.json.decodeFromString(BuildManifest.serializer(), body.buffer.readUtf8()) }.getOrNull()
                ?: throw UserFacingException("The public build record couldn't be read.")
            if (manifest.build != build) throw UserFacingException("The public build record is for another build.")
            manifest
        }
    }

    /** Whether the APK in [file] is the app of [record]. */
    fun verifyApk(file: File, record: BuildManifest?): Verdict {
        val expected = record?.apk ?: return Verdict.Unknown("There is no public record of this build's Android app.")
        val actual = try {
            apkDigest(file)
        } catch (e: IllegalArgumentException) {
            return Verdict.Differs(e.message ?: "The app's archive is malformed.")
        } catch (e: java.io.IOException) {
            return Verdict.Differs("The app's archive can't be read.")
        }
        return if (actual.digest.equals(expected.digest, ignoreCase = true) && actual.entries == expected.entries) Verdict.Matches
        else Verdict.Differs("Its contents are not those of the public build ${record.version}.")
    }

    class WebReport(val verdict: Verdict, val checked: Int, val changed: List<String>)

    /** The web app's files worth checking, with their expected SHA-256. */
    fun activeWebFiles(record: BuildManifest): Map<String, String> =
        record.web.filterKeys { path -> path.substringAfterLast('.', "").lowercase() in ACTIVE && isPlainWebPath(path) }

    private fun isPlainWebPath(path: String) = path.isNotEmpty() && !path.startsWith("/") && ".." !in path && path.all { it.code in 0x21..0x7E && it != '?' && it != '#' && it != '\\' }

    /**
     * Fetches the web app from [serverUrl] the way a browser would and compares every file a browser runs with
     * [record]. The page itself comes first: if it is the public one, it can only load the public scripts.
     */
    suspend fun verifyWeb(http: OkHttpClient, serverUrl: String, record: BuildManifest?, onProgress: (Int, Int) -> Unit = { _, _ -> }): WebReport =
        withContext(Dispatchers.IO) {
            val files = record?.let(::activeWebFiles).orEmpty()
            if (record == null || "index.html" !in files) {
                return@withContext WebReport(Verdict.Unknown("There is no public record of this build's web app."), 0, emptyList())
            }
            val base = serverUrl.trim().trimEnd('/')
            val changed = ArrayList<String>()
            var done = 0
            // The page at the address itself, then each file by its own path (index.html is also served at /).
            val targets = listOf("" to files.getValue("index.html")) + files.entries.map { it.key to it.value }
            for ((path, expected) in targets) {
                val request = Request.Builder().url("$base/$path")
                    .header("User-Agent", BROWSER).header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").header("Cache-Control", "no-cache").build()
                val actual = try {
                    http.newCall(request).execute().use { response -> if (response.isSuccessful) sha256(response.body.bytes()) else "http ${response.code}" }
                } catch (e: java.io.IOException) {
                    return@withContext WebReport(Verdict.Unknown("Your server couldn't be reached to check its web app."), done, changed)
                }
                if (!actual.equals(expected, ignoreCase = true)) changed += path.ifEmpty { "/" }
                onProgress(++done, targets.size)
            }
            val verdict = if (changed.isEmpty()) Verdict.Matches
            else Verdict.Differs("${changed.size} of its files are not those of the public build ${record.version}.")
            WebReport(verdict, done, changed)
        }

    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
}
