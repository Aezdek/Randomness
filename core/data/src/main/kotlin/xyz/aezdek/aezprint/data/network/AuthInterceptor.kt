package xyz.aezdek.aezprint.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import xyz.aezdek.aezprint.domain.repository.CredentialsRepository
import java.io.IOException
import javax.inject.Inject

class InsecureUrlException(url: String) : IOException("Refusing to send credentials over plain HTTP to $url")

class MissingCredentialsException : IOException("Server credentials are not configured")

/** Adds the Cloudflare Access service token and API key; refuses anything that isn't HTTPS. */
class AuthInterceptor @Inject constructor(
    private val credentials: CredentialsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps) throw InsecureUrlException(request.url.host)
        val creds = runBlocking { credentials.current() }
            ?.takeIf { it.isComplete }
            ?: throw MissingCredentialsException()
        return chain.proceed(
            request.newBuilder()
                .header("CF-Access-Client-Id", creds.clientId)
                .header("CF-Access-Client-Secret", creds.clientSecret)
                .header("Authorization", "Bearer ${creds.apiKey}")
                .build(),
        )
    }
}
