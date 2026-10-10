package xyz.aezdek.aezprint.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.aezdek.aezprint.data.network.AuthInterceptor
import xyz.aezdek.aezprint.data.network.InsecureUrlException
import xyz.aezdek.aezprint.data.network.MissingCredentialsException
import xyz.aezdek.aezprint.domain.model.ServerCredentials
import xyz.aezdek.aezprint.domain.repository.CredentialsRepository

class AuthInterceptorTest {

    private class FakeCredentials(initial: ServerCredentials?) : CredentialsRepository {
        override val credentials = MutableStateFlow(initial)
        override suspend fun current() = credentials.first()
        override suspend fun save(credentials: ServerCredentials) { this.credentials.value = credentials }
        override suspend fun clear() { credentials.value = null }
    }

    private val server = MockWebServer()
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    @Before
    fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(credentials: ServerCredentials?) = OkHttpClient.Builder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        .addInterceptor(AuthInterceptor(FakeCredentials(credentials)))
        .build()

    @Test
    fun `adds the three auth headers`() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val url = server.url("/v1/printer").newBuilder().host("localhost").build()
        client(ServerCredentials("id.access", "secret", "key")).newCall(Request.Builder().url(url).build()).execute().close()

        val request = server.takeRequest()
        assertEquals("id.access", request.headers["CF-Access-Client-Id"])
        assertEquals("secret", request.headers["CF-Access-Client-Secret"])
        assertEquals("Bearer key", request.headers["Authorization"])
    }

    @Test(expected = InsecureUrlException::class)
    fun `refuses plain http`() {
        client(ServerCredentials("a", "b", "c")).newCall(Request.Builder().url("http://print.example/v1/printer").build()).execute()
    }

    @Test(expected = MissingCredentialsException::class)
    fun `fails fast without credentials`() {
        val url = server.url("/v1/printer").newBuilder().host("localhost").build()
        client(null).newCall(Request.Builder().url(url).build()).execute()
    }

    @Test
    fun `credentials never appear in toString`() {
        assertEquals("ServerCredentials(<redacted>)", ServerCredentials("a", "b", "c").toString())
    }
}
