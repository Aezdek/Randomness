package xyz.aezdek.hush.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildCheckTest {
    private val dir = File(System.getProperty("hush.vectorsDir") ?: "../../crypto-vectors", "build-check")
    private val expected = Json.parseToJsonElement(File(dir, "expected.json").readText()).jsonObject["files"]!!.jsonObject

    /** The same fingerprints as tools/build-manifest.py, which writes the public record. */
    @Test
    fun sameFingerprintsAsTheToolThatWritesTheRecord() {
        for ((name, digest) in expected) assertEquals(name, digest.jsonPrimitive.content, BuildCheck.apkDigest(File(dir, name)).digest)
    }

    @Test
    fun signingChangesNothingButAnyOtherChangeShows() {
        val app = BuildCheck.apkDigest(File(dir, "app.zip"))
        val record = BuildManifest(build = 7, version = "1.0.7", apk = ApkRecord(app.digest, app.entries))
        assertEquals(BuildCheck.Verdict.Matches, BuildCheck.verifyApk(File(dir, "signed.zip"), record))
        for (tampered in listOf("changed.zip", "added.zip", "sneaky.zip", "dup.zip")) {
            assertTrue(tampered, BuildCheck.verifyApk(File(dir, tampered), record) is BuildCheck.Verdict.Differs)
        }
        // The right contents with one file fewer on record is still a difference.
        assertTrue(BuildCheck.verifyApk(File(dir, "app.zip"), record.copy(apk = ApkRecord(app.digest, app.entries - 1))) is BuildCheck.Verdict.Differs)
        assertTrue(BuildCheck.verifyApk(File(dir, "app.zip"), null) is BuildCheck.Verdict.Unknown)
        assertTrue(BuildCheck.verifyApk(File(dir, "app.zip"), record.copy(apk = null)) is BuildCheck.Verdict.Unknown)
    }

    @Test
    fun theRecordOnlyEverComesFromAnHttpsAddressChosenAtBuildTime() {
        assertEquals("https://github.com/o/r/releases/download/hush-latest/hush-build-52.json",
            BuildCheck.recordUrl("https://github.com/o/r/releases/download/hush-latest/", 52))
        assertNull(BuildCheck.recordUrl("http://github.com/o/r", 52))
        assertNull(BuildCheck.recordUrl("", 52))
        assertNull(BuildCheck.recordUrl("https://github.com/o/r", 0))
        assertEquals(52, BuildCheck.buildNumber("1.0.52"))
        assertNull(BuildCheck.buildNumber("dev"))
        assertNull(BuildCheck.buildNumber("1.0.-3"))
        val failure = runCatching { runBlocking { BuildCheck.fetchRecord(OkHttpClient(), "http://example.com", 1) } }.exceptionOrNull()
        assertTrue(failure is UserFacingException)
    }

    @Test
    fun onlyFilesABrowserRunsAreCheckedAndOddPathsAreLeftOut() {
        val record = BuildManifest(build = 1, web = mapOf(
            "index.html" to "a", "sw.js" to "b", "assets/app.css" to "c", "assets/font.woff2" to "d", "icon-192.png" to "e",
            "../etc/passwd.js" to "f", "/abs.js" to "g", "a b.js" to "h", "x.js?y" to "i",
        ))
        assertEquals(setOf("index.html", "sw.js", "assets/app.css"), BuildCheck.activeWebFiles(record).keys)
    }
}
