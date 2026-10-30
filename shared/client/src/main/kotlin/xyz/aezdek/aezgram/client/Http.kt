package xyz.aezdek.aezgram.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.aezdek.aezgram.protocol.ApiError
import xyz.aezdek.aezgram.protocol.Wire
import java.io.IOException

/** A non-2xx answer from the server. [code] is the machine-readable `error` field (e.g. `stale_epoch`). */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/**
 * The `/api/v1` client: JSON in and out with [Wire.json], the session's bearer token, and the same error shape the
 * web client sees. Blocking OkHttp calls run on [Dispatchers.IO].
 */
class Api(baseUrl: String, val http: OkHttpClient) {
    val origin: String = baseUrl.trimEnd('/')
    private val base = "$origin/api/v1"

    @Volatile
    var token: String? = null

    /** Called when an authenticated request comes back 401 (the session was revoked). */
    @Volatile
    var onUnauthorized: (() -> Unit)? = null

    val json: Json = Wire.json

    suspend inline fun <reified T> get(path: String): T = call("GET", path, null, serializer())
    suspend inline fun <reified T> delete(path: String): T = call("DELETE", path, null, serializer())
    suspend inline fun <reified T, reified B> post(path: String, body: B): T = call("POST", path, encode(body), serializer())
    suspend inline fun <reified T> post(path: String): T = call("POST", path, "{}", serializer())
    suspend inline fun <reified T, reified B> put(path: String, body: B): T = call("PUT", path, encode(body), serializer())
    suspend inline fun <reified T, reified B> patch(path: String, body: B): T = call("PATCH", path, encode(body), serializer())

    inline fun <reified B> encode(body: B): String = json.encodeToString(serializer<B>(), body)

    suspend fun <T> call(method: String, path: String, jsonBody: String?, out: KSerializer<T>): T {
        val body = jsonBody?.toRequestBody(JSON)
        val text = send(method, path, body).use { it.body.string() }
        @Suppress("UNCHECKED_CAST")
        return if (out.descriptor.serialName == "kotlin.Unit") Unit as T else json.decodeFromString(out, text)
    }

    /** PUTs raw bytes (resumable uploads) and decodes the JSON answer. */
    suspend fun <T> putBytes(path: String, bytes: ByteArray, out: KSerializer<T>): T {
        val text = send("PUT", path, bytes.toRequestBody(OCTETS)).use { it.body.string() }
        return json.decodeFromString(out, text)
    }

    /** A ciphertext blob, or the bytes [from] until [toExclusive] of it. */
    suspend fun blob(blobId: String, from: Long? = null, toExclusive: Long? = null): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$base/blobs/$blobId").apply {
            token?.let { header("Authorization", "Bearer $it") }
            if (from != null) header("Range", "bytes=$from-${toExclusive?.let { it - 1 } ?: ""}")
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "blob", "Media not available")
            response.body.bytes()
        }
    }

    private suspend fun send(method: String, path: String, body: RequestBody?): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base + path).apply {
            token?.let { header("Authorization", "Bearer $it") }
            method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") "{}".toRequestBody(JSON) else null)
        }.build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val text = response.use { it.body.string() }
            val error = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            if (response.code == 401 && token != null) onUnauthorized?.invoke()
            throw ApiException(response.code, error?.error ?: "http_${response.code}", error?.message ?: "Request failed (${response.code})")
        }
        response
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTETS = "application/octet-stream".toMediaType()
    }
}
