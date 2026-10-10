package xyz.aezdek.aezprint.data.network

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import retrofit2.HttpException
import xyz.aezdek.aezprint.data.network.dto.ErrorDto
import xyz.aezdek.aezprint.data.network.dto.JobDto
import xyz.aezdek.aezprint.data.network.dto.SubmitJobDto
import xyz.aezdek.aezprint.data.network.dto.toDomain
import xyz.aezdek.aezprint.data.network.dto.toDto
import xyz.aezdek.aezprint.domain.model.Job
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.Printer
import xyz.aezdek.aezprint.domain.model.RemoteDocument
import xyz.aezdek.aezprint.domain.model.Render
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.PrintServerException
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class RemotePrintServer @Inject constructor(
    private val apiProvider: ApiProvider,
    private val settings: SettingsRepository,
    private val json: Json,
) : PrintServer {

    private suspend fun api() = apiProvider.api(settings.settings.first().serverUrl)

    override suspend fun printer(): Printer = call { api().printer().toDomain() }

    override suspend fun upload(document: LocalDocument, onProgress: (Float) -> Unit): RemoteDocument = call {
        val body = ProgressRequestBody(File(document.path), PDF, onProgress)
        val part = MultipartBody.Part.createFormData("file", document.displayName, body)
        api().upload(part).toDomain()
    }

    override suspend fun render(documentId: String, options: PrintOptions): Render =
        call { api().render(documentId, options.toDto()).toDomain() }

    override suspend fun sheet(renderId: String, face: Int, widthPx: Int): ByteArray =
        call { api().sheet(renderId, face, widthPx).use { it.bytes() } }

    override suspend fun submit(documentId: String, options: PrintOptions): String =
        call { api().submit(SubmitJobDto(documentId, options.toDto())).jobId }

    override suspend fun job(jobId: String): Job = call { api().job(jobId).toDomain(jobId) }

    override suspend fun continueJob(jobId: String) = call { api().continueJob(jobId) }

    override suspend fun cancel(jobId: String) = call { api().cancel(jobId) }

    override suspend fun jobs(limit: Int): List<Job> = call {
        val items = when (val body: JsonElement = api().jobs(limit)) {
            is JsonArray -> body
            is JsonObject -> body["jobs"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        items.map { json.decodeFromJsonElement<JobDto>(it).toDomain(fallbackId = "") }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MissingCredentialsException) {
        throw PrintServerException.NotConfigured()
    } catch (e: InsecureUrlException) {
        throw PrintServerException.Unauthorized(e.message.orEmpty())
    } catch (e: HttpException) {
        throw mapHttpError(e.code(), e.response()?.errorBody()?.string())
    } catch (e: IOException) {
        throw PrintServerException.Offline(e)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw PrintServerException.Server(0, "bad_response", "The server sent a response the app doesn't understand")
    }

    internal fun mapHttpError(status: Int, body: String?): PrintServerException {
        val error = body?.let { runCatching { json.decodeFromString<ErrorDto>(it) }.getOrNull() }
        val message = error?.message?.takeIf { it.isNotBlank() }
        return when (status) {
            in 300..399, 401, 403 -> PrintServerException.Unauthorized(
                message ?: "The server rejected the credentials. Check the Cloudflare Access token and API key.",
            )
            404, 410 -> PrintServerException.NotFound(message ?: "Not found on the server")
            422 -> PrintServerException.Unsupported(message ?: "Some options aren't supported", error?.fields.orEmpty())
            else -> PrintServerException.Server(status, error?.error ?: "http_$status", message ?: "Server error ($status)")
        }
    }

    private companion object {
        val PDF = "application/pdf".toMediaType()
    }
}
