package xyz.aezdek.hush.server

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.aezdek.hush.protocol.AppVersion
import java.io.File
import java.nio.file.Files

class AppUpdateTest {
    @Test
    fun theAppCanCheckForAndDownloadItsUpdateWithoutSigningIn() {
        val apkDir = Files.createTempDirectory("hush-apk").toFile()
        val apk = ByteArray(4096) { it.toByte() }
        File(apkDir, "hush.apk").writeBytes(apk)
        File(apkDir, "version.json").writeText(
            """{"versionCode":24,"versionName":"1.0.24","sha256":"abc","size":4096,"url":"/download/hush.apk"}""",
        )
        try {
            serverTest({ it.copy(apkDir = apkDir) }) {
                val version = http.get("/version")
                assertEquals(HttpStatusCode.OK, version.status)
                assertEquals("no-store", version.headers[HttpHeaders.CacheControl])
                assertEquals(AppVersion(24, "1.0.24", "abc", 4096, "/download/hush.apk"), version.body<AppVersion>())
                // The app adds ?v=<build> to dodge caches; the server ignores it.
                val download = http.get("/download/hush.apk?v=24")
                assertEquals(HttpStatusCode.OK, download.status)
                assertEquals("no-store", download.headers[HttpHeaders.CacheControl])
                assertArrayEquals(apk, download.bodyAsBytes())
            }
        } finally {
            apkDir.deleteRecursively()
        }
    }

    @Test
    fun aServerWithoutAnAndroidBuildSaysSo() = serverTest {
        assertEquals(HttpStatusCode.NotFound, http.get("/version").status)
    }
}
