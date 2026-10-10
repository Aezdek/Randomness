package xyz.aezdek.aezprint.feature.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder

/**
 * The QR payload for one-scan setup. Either JSON
 * `{"url":"https://…","clientId":"…","clientSecret":"…","apiKey":"…"}` or a link
 * `aezprint://setup?url=…&clientId=…&clientSecret=…&apiKey=…`.
 */
data class SetupCode(
    val url: String?,
    val clientId: String,
    val clientSecret: String,
    val apiKey: String,
) {
    override fun toString() = "SetupCode(url=$url, <redacted>)"

    companion object {
        fun parse(text: String): SetupCode? {
            val trimmed = text.trim()
            val values = when {
                trimmed.startsWith("{") -> runCatching {
                    (Json.parseToJsonElement(trimmed) as JsonObject).jsonObject.mapValues { it.value.jsonPrimitive.content }
                }.getOrNull()
                trimmed.startsWith("aezprint://", ignoreCase = true) -> runCatching {
                    URI(trimmed).rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { pair ->
                        val (key, value) = pair.split('=', limit = 2)
                        key to URLDecoder.decode(value, Charsets.UTF_8.name())
                    }
                }.getOrNull()
                else -> null
            } ?: return null
            val clientId = values["clientId"]?.takeIf { it.isNotBlank() } ?: return null
            val clientSecret = values["clientSecret"]?.takeIf { it.isNotBlank() } ?: return null
            val apiKey = values["apiKey"]?.takeIf { it.isNotBlank() } ?: return null
            val url = values["url"]?.takeIf { it.startsWith("https://", ignoreCase = true) }
            return SetupCode(url, clientId, clientSecret, apiKey)
        }
    }
}
