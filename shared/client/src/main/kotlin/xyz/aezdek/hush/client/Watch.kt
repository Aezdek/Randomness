package xyz.aezdek.hush.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.aezdek.hush.protocol.WatchParty
import xyz.aezdek.hush.protocol.WatchState

// Watching something together from the members' own media server (Jellyfin). The chat carries only which film and
// where it is; each app plays it straight from that server with its owner's own sign-in there. Hush's server takes no
// part, and never sees the film, the Jellyfin address or anyone's Jellyfin password.

/** The rules for what a chat says about a film being watched: whatever another member's app sends, cut down to sense. */
object Watching {
    const val MAX_TITLE = 120
    const val MAX_RUNTIME_MS = 24 * 60 * 60 * 1000L
    /** How far apart two players may drift before the follower jumps. */
    const val DRIFT_MS = 2_500L
    private val ITEM_ID = Regex("^[0-9a-fA-F-]{8,64}$")

    fun title(raw: String?): String = raw?.replace('\n', ' ')?.trim()?.take(MAX_TITLE).orEmpty().ifEmpty { "Something to watch" }

    /** Whether [party] names something a media server could have: its id goes into a web address, so only an id will do. */
    fun valid(party: WatchParty?): Boolean = party != null && ITEM_ID.matches(party.itemId) && party.runtimeMs in 0..MAX_RUNTIME_MS

    data class Now(val playing: Boolean, val positionMs: Long)

    /**
     * Where the film should be at [now], from the chat's states (oldest first): the newest sensible one counts. Paused
     * at the start until someone plays. A state dated in the future is read as "now"; one with a time no clock shows
     * is ignored.
     */
    fun now(states: List<WatchState>, runtimeMs: Long, now: Long): Now {
        val last = states.lastOrNull { Display.plausibleTime(it.at) && it.positionMs in 0..MAX_RUNTIME_MS } ?: return Now(false, 0)
        val end = if (runtimeMs in 1..MAX_RUNTIME_MS) runtimeMs else MAX_RUNTIME_MS
        val moved = if (last.playing) (now - minOf(last.at, now)).coerceAtLeast(0) else 0
        val position = (last.positionMs + moved).coerceIn(0, end)
        return Now(last.playing && position < end, position)
    }
}

/** A sign-in to a Jellyfin server, kept on the device that made it. */
data class JellyfinSession(val base: String, val token: String, val userId: String)

data class JellyfinItem(val id: String, val title: String, val runtimeMs: Long)

/** The few things Hush asks of a Jellyfin server: a sign-in, a list of films and episodes, and where to play one from. */
class Jellyfin(private val http: OkHttpClient, private val device: String = "Android") {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class AuthBody(@SerialName("Username") val username: String, @SerialName("Pw") val password: String)

    @Serializable
    private class AuthUser(@SerialName("Id") val id: String = "")

    @Serializable
    private class AuthResult(@SerialName("AccessToken") val token: String = "", @SerialName("User") val user: AuthUser = AuthUser())

    @Serializable
    private class Item(
        @SerialName("Id") val id: String = "",
        @SerialName("Name") val name: String = "",
        @SerialName("Type") val type: String = "",
        @SerialName("SeriesName") val series: String? = null,
        @SerialName("ProductionYear") val year: Int? = null,
        @SerialName("RunTimeTicks") val ticks: Long? = null,
    )

    @Serializable
    private class Items(@SerialName("Items") val items: List<Item> = emptyList())

    private fun identity(token: String? = null) =
        "MediaBrowser Client=\"Hush\", Device=\"$device\", DeviceId=\"hush-${device.lowercase()}\", Version=\"1.0\"" + (token?.let { ", Token=\"$it\"" } ?: "")

    /** Signs in with someone's own Jellyfin name and password. Only the token it gives back is kept. */
    suspend fun signIn(address: String, username: String, password: String): JellyfinSession = withContext(Dispatchers.IO) {
        val base = baseOf(address) ?: throw UserFacingException("That isn't a web address")
        val body = json.encodeToString(AuthBody.serializer(), AuthBody(username.trim(), password)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(base.newBuilder().addPathSegments("Users/AuthenticateByName").build()).header("Authorization", identity()).post(body).build()
        val result = try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw UserFacingException("Jellyfin didn't accept that name and password")
                if (!response.isSuccessful) throw UserFacingException("Jellyfin answered with an error (${response.code})")
                json.decodeFromString(AuthResult.serializer(), response.body?.string().orEmpty())
            }
        } catch (e: UserFacingException) {
            throw e
        } catch (e: Exception) {
            throw UserFacingException("Couldn't reach Jellyfin at ${base.host}")
        }
        if (result.token.isEmpty() || result.user.id.isEmpty()) throw UserFacingException("That doesn't look like a Jellyfin server")
        JellyfinSession(base.toString().trimEnd('/'), result.token, result.user.id)
    }

    /** Films and episodes, by name; [search] narrows them. */
    suspend fun items(session: JellyfinSession, search: String = ""): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val url = (baseOf(session.base) ?: throw UserFacingException("Sign in to Jellyfin again")).newBuilder().addPathSegment("Items")
            .addQueryParameter("userId", session.userId).addQueryParameter("IncludeItemTypes", "Movie,Episode").addQueryParameter("Recursive", "true")
            .addQueryParameter("SortBy", "SortName").addQueryParameter("Limit", "60").addQueryParameter("Fields", "RunTimeTicks")
            .apply { if (search.isNotBlank()) addQueryParameter("searchTerm", search.trim().take(80)) }.build()
        val request = Request.Builder().url(url).header("Authorization", identity(session.token)).build()
        val found = try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw UserFacingException("Sign in to Jellyfin again")
                if (!response.isSuccessful) throw UserFacingException("Jellyfin answered with an error (${response.code})")
                json.decodeFromString(Items.serializer(), response.body?.string().orEmpty())
            }
        } catch (e: UserFacingException) {
            throw e
        } catch (e: Exception) {
            throw UserFacingException("Couldn't reach Jellyfin")
        }
        found.items.filter { it.id.isNotEmpty() }.map { item ->
            val name = listOfNotNull(item.series, item.name).joinToString(": ") + (item.year?.takeIf { item.series == null }?.let { " ($it)" } ?: "")
            JellyfinItem(item.id, Watching.title(name), ((item.ticks ?: 0) / 10_000).coerceIn(0, Watching.MAX_RUNTIME_MS))
        }
    }

    companion object {
        /** A Jellyfin address someone typed, as a base address: https (or http) only, "https://" added if left out. */
        fun baseOf(address: String): HttpUrl? {
            val text = address.trim().trimEnd('/')
            if (text.isEmpty()) return null
            val url = (if (text.contains("://")) text else "https://$text").toHttpUrlOrNull() ?: return null
            return url.takeIf { it.username.isEmpty() && it.password.isEmpty() }
        }

        /** Where this session plays [itemId] from, as the file is. Null unless the id is one (it becomes part of the address). */
        fun streamUrl(session: JellyfinSession, itemId: String): String? {
            if (!Watching.valid(WatchParty(itemId, ""))) return null
            return baseOf(session.base)?.newBuilder()?.addPathSegment("Videos")?.addPathSegment(itemId)?.addPathSegment("stream")
                ?.addQueryParameter("static", "true")?.addQueryParameter("api_key", session.token)?.build()?.toString()
        }
    }
}
