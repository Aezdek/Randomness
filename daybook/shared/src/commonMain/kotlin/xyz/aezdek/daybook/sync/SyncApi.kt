package xyz.aezdek.daybook.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import xyz.aezdek.daybook.core.DaybookJson
import xyz.aezdek.daybook.core.PingResponse
import xyz.aezdek.daybook.core.SyncRequest
import xyz.aezdek.daybook.core.SyncResponse
import xyz.aezdek.daybook.core.WaitResponse

class SyncException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** No connection, or the server didn't answer. Retrying later should work. */
        Offline,

        /** The access token was refused. */
        Auth,

        /** Something in front of the server (such as Cloudflare Access) refused or redirected the request. */
        Blocked,

        /** The server answered with an error. */
        Server,

        /** The settings can't work, such as an invalid address. */
        Config,
    }
}

/** Talks to the Daybook sync server. [base] is configured with timeouts and JSON here. */
class SyncApi(base: HttpClient) {

    private val http = base.config {
        expectSuccess = false
        followRedirects = false
        install(ContentNegotiation) { json(DaybookJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun ping(config: SyncConfig): PingResponse = call(config) {
        http.get("${config.baseUrl}/api/ping") { auth(config) }
    }.body()

    suspend fun sync(config: SyncConfig, request: SyncRequest): SyncResponse = call(config) {
        http.post("${config.baseUrl}/api/sync") {
            auth(config)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.body()

    /** Long-polls until the server's revision passes [since], or about 25 seconds pass. Returns the revision. */
    suspend fun waitForChange(config: SyncConfig, since: Long): Long = call(config) {
        http.get("${config.baseUrl}/api/wait") {
            auth(config)
            parameter("since", since)
            timeout {
                requestTimeoutMillis = 45_000
                socketTimeoutMillis = 45_000
            }
        }
    }.body<WaitResponse>().rev

    private fun HttpRequestBuilder.auth(config: SyncConfig) {
        header(HttpHeaders.Authorization, "Bearer ${config.token.trim()}")
        if (config.accessClientId.isNotBlank() && config.accessClientSecret.isNotBlank()) {
            header("CF-Access-Client-Id", config.accessClientId.trim())
            header("CF-Access-Client-Secret", config.accessClientSecret.trim())
        }
    }

    private suspend fun call(config: SyncConfig, request: suspend () -> HttpResponse): HttpResponse {
        serverUrlProblem(config.serverUrl)?.let { throw SyncException(SyncException.Kind.Config, it) }
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncException.Kind.Offline, "Can't reach the server.", e)
        }
        val status = response.status.value
        val isJson = response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true
        return when {
            response.status.isSuccess() && isJson -> response
            response.status.isSuccess() ->
                throw SyncException(SyncException.Kind.Blocked, "Something other than a Daybook server answered. Check the address.")
            status == 401 -> throw SyncException(SyncException.Kind.Auth, "The server refused the access token.")
            status in 300..399 || status == 403 ->
                throw SyncException(
                    SyncException.Kind.Blocked,
                    "The request was blocked before it reached Daybook. If the server is behind Cloudflare Access, add a service token.",
                )
            status == 404 -> throw SyncException(SyncException.Kind.Server, "No Daybook server at that address.")
            status in 502..504 -> throw SyncException(SyncException.Kind.Offline, "The server is not responding.")
            else -> throw SyncException(SyncException.Kind.Server, "The server returned an error ($status).")
        }
    }
}
