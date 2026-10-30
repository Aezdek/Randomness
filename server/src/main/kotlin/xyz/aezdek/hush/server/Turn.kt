package xyz.aezdek.hush.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import xyz.aezdek.hush.protocol.IceServer
import xyz.aezdek.hush.protocol.TurnResponse
import xyz.aezdek.hush.protocol.Wire
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * ICE servers for calls. With a Cloudflare TURN key configured, short-lived TURN credentials from Cloudflare's API
 * (a relay only ever sees SRTP ciphertext). Without one, Cloudflare's free STUN only, which works on most Wi-Fi but
 * can fail on mobile networks behind symmetric NAT.
 */
class Turn(private val config: ServerConfig, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(Turn::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val stunOnly = TurnResponse(listOf(IceServer(listOf("stun:stun.cloudflare.com:3478"))), relay = false)

    @Volatile
    private var cached: Pair<TurnResponse, Long>? = null

    fun iceServers(): TurnResponse {
        val keyId = config.cfTurnKeyId ?: return stunOnly
        val token = config.cfTurnApiToken ?: return stunOnly
        cached?.let { (response, expiresAt) -> if (clock.now() < expiresAt) return response }
        return runCatching {
            val request = HttpRequest.newBuilder(URI("https://rtc.live.cloudflare.com/v1/turn/keys/$keyId/credentials/generate-ice-servers"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"ttl":$TTL_SECONDS}"""))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "Cloudflare TURN answered ${response.statusCode()}" }
            parse(response.body())
        }.onSuccess {
            cached = it to clock.now() + TTL_SECONDS * 1000 / 2
        }.getOrElse {
            log.warn("Could not get TURN credentials: {}", it.message)
            stunOnly
        }
    }

    companion object {
        private const val TTL_SECONDS = 24 * 3600

        /** Accepts both shapes Cloudflare has used: `iceServers` as one object or as an array. */
        fun parse(body: String): TurnResponse {
            val root = Wire.json.parseToJsonElement(body).jsonObject
            val servers = when (val ice = root["iceServers"]) {
                is JsonArray -> ice.map { it.jsonObject }
                is JsonObject -> listOf(ice)
                else -> error("no iceServers in Cloudflare's answer")
            }.map { server ->
                IceServer(
                    urls = urls(server["urls"]),
                    username = server["username"]?.jsonPrimitive?.contentOrNull,
                    credential = server["credential"]?.jsonPrimitive?.contentOrNull,
                )
            }
            return TurnResponse(servers, relay = servers.any { s -> s.urls.any { it.startsWith("turn") } })
        }

        private fun urls(element: JsonElement?): List<String> = when (element) {
            is JsonArray -> element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }
    }
}
