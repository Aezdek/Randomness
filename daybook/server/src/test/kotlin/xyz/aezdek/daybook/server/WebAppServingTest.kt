package xyz.aezdek.daybook.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAppServingTest {

    private val token = "test-token-0123456789abcdef"

    private fun webTest(root: String = "testweb", block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val store = ServerStore.open(":memory:")
        application { daybookModule(token, store, web = WebAssets(root)) }
        block()
    }

    @Test
    fun theRootServesTheAppPage() = webTest {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/html; charset=utf-8", response.headers[HttpHeaders.ContentType])
        assertTrue(response.bodyAsText().contains("Daybook test"))
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun modulesAndWasmGetTheTypesBrowsersRequire() = webTest {
        assertEquals("text/javascript; charset=utf-8", client.get("/sub/app.mjs").headers[HttpHeaders.ContentType])
        assertEquals("application/wasm", client.get("/app.wasm").headers[HttpHeaders.ContentType])
    }

    @Test
    fun largeFilesAreGzippedForBrowsersThatAcceptIt() = webTest {
        val plain = client.get("/index.html")
        assertNull(plain.headers[HttpHeaders.ContentEncoding])
        val zipped = client.get("/index.html") { header(HttpHeaders.AcceptEncoding, "gzip, br") }
        assertEquals("gzip", zipped.headers[HttpHeaders.ContentEncoding])
        val unzipped = GZIPInputStream(ByteArrayInputStream(zipped.bodyAsBytes())).readBytes()
        assertTrue(unzipped.contentEquals(plain.bodyAsBytes()))
    }

    @Test
    fun aReturningBrowserRevalidatesInsteadOfDownloadingAgain() = webTest {
        val first = client.get("/app.wasm")
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        val again = client.get("/app.wasm") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test
    fun pathsCannotEscapeTheWebFolder() = webTest {
        assertEquals(HttpStatusCode.NotFound, client.get("/../logback.xml").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/sub/%2e%2e/%2e%2e/logback.xml").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/missing.js").status)
    }

    @Test
    fun theApiStillWinsOverTheWebsite() = webTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/ping").status)
        assertEquals("ok", client.get("/health").bodyAsText())
    }

    @Test
    fun securityHeadersAreSet() = webTest {
        val response = client.get("/")
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertTrue(response.headers["Content-Security-Policy"]!!.contains("connect-src 'self'"))
    }

    @Test
    fun aServerBuiltWithoutTheWebAppSaysSo() = webTest(root = "no-such-folder") {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("doesn't include the web app"))
        assertEquals(HttpStatusCode.NotFound, client.get("/daybook.mjs").status)
    }
}
