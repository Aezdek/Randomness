package xyz.aezdek.aezprint.data.network

import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import xyz.aezdek.aezprint.data.network.dto.DocumentDto
import xyz.aezdek.aezprint.data.network.dto.JobCreatedDto
import xyz.aezdek.aezprint.data.network.dto.JobDto
import xyz.aezdek.aezprint.data.network.dto.OptionsDto
import xyz.aezdek.aezprint.data.network.dto.PrinterDto
import xyz.aezdek.aezprint.data.network.dto.RenderDto
import xyz.aezdek.aezprint.data.network.dto.SubmitJobDto

interface AezPrintApi {
    @GET("v1/printer")
    suspend fun printer(): PrinterDto

    @Multipart
    @POST("v1/documents")
    suspend fun upload(@Part file: MultipartBody.Part): DocumentDto

    @POST("v1/documents/{id}/renders")
    suspend fun render(@Path("id") documentId: String, @Body options: OptionsDto): RenderDto

    @GET("v1/renders/{renderId}/sheets/{n}.png")
    suspend fun sheet(@Path("renderId") renderId: String, @Path("n") face: Int, @Query("width") width: Int): ResponseBody

    @POST("v1/jobs")
    suspend fun submit(@Body body: SubmitJobDto): JobCreatedDto

    @GET("v1/jobs/{id}")
    suspend fun job(@Path("id") jobId: String): JobDto

    @POST("v1/jobs/{id}/continue")
    suspend fun continueJob(@Path("id") jobId: String)

    @DELETE("v1/jobs/{id}")
    suspend fun cancel(@Path("id") jobId: String)

    /** Returned either as a bare array or wrapped in `{ "jobs": [...] }`. */
    @GET("v1/jobs")
    suspend fun jobs(@Query("limit") limit: Int): JsonElement
}
