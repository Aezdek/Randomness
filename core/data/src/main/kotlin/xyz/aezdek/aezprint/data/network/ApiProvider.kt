package xyz.aezdek.aezprint.data.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiProvider @Inject constructor(
    authInterceptor: AuthInterceptor,
    private val json: Json,
) {
    // Cloudflare Access answers a bad token with a redirect to its login page; following it would
    // turn an auth failure into a confusing HTML parse error.
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    @Volatile
    private var cached: Pair<String, AezPrintApi>? = null

    fun api(baseUrl: String): AezPrintApi {
        val normalized = baseUrl.trim().trimEnd('/') + "/"
        cached?.let { (url, api) -> if (url == normalized) return api }
        val api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AezPrintApi::class.java)
        cached = normalized to api
        return api
    }
}
