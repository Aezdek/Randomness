package xyz.aezdek.aezgram.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.aezdek.aezgram.crypto.AccountKeys
import xyz.aezdek.aezgram.protocol.ActivityPayload
import xyz.aezdek.aezgram.protocol.AnswerPayload
import xyz.aezdek.aezgram.protocol.CollectionPayload
import xyz.aezdek.aezgram.protocol.CommentPayload
import xyz.aezdek.aezgram.protocol.EpochPayload
import xyz.aezdek.aezgram.protocol.HighlightPayload
import xyz.aezdek.aezgram.protocol.Kind
import xyz.aezdek.aezgram.protocol.LikePayload
import xyz.aezdek.aezgram.protocol.LivePayload
import xyz.aezdek.aezgram.protocol.Me
import xyz.aezdek.aezgram.protocol.MessagePayload
import xyz.aezdek.aezgram.protocol.NotePayload
import xyz.aezdek.aezgram.protocol.ObjectDto
import xyz.aezdek.aezgram.protocol.PostPayload
import xyz.aezdek.aezgram.protocol.ProfilePayload
import xyz.aezdek.aezgram.protocol.ReactionPayload
import xyz.aezdek.aezgram.protocol.ReelPayload
import xyz.aezdek.aezgram.protocol.SettingsPayload
import xyz.aezdek.aezgram.protocol.StoryPayload
import xyz.aezdek.aezgram.protocol.ThreadDto
import xyz.aezdek.aezgram.protocol.ThreadPayload
import xyz.aezdek.aezgram.protocol.UserDto
import xyz.aezdek.aezgram.protocol.VotePayload
import xyz.aezdek.aezgram.protocol.Wire

/** A decrypted, verified object: the server's envelope, its content key, and the typed plaintext. */
class Entity(val dto: ObjectDto, val key: ByteArray, val payload: Any) {
    val id: String get() = dto.id

    inline fun <reified T> payloadAs(): T? = payload as? T
}

/** What we remember about someone's identity key: trust on first use, with history so old content still verifies. */
@Serializable
data class Pin(
    val signPk: String,
    val keyVersion: Int,
    val verified: Boolean = false,
    val changedFrom: String? = null,
    val history: List<String> = emptyList(),
)

enum class Connection { OFFLINE, CONNECTING, ONLINE }

/** Everything the UI renders from, in one immutable value (the Android counterpart of the web client's store). */
data class AppState(
    val me: Me? = null,
    val keys: AccountKeys? = null,
    val users: Map<String, UserDto> = emptyMap(),
    val threads: Map<String, ThreadDto> = emptyMap(),
    val entities: Map<String, Entity> = emptyMap(),
    val pins: Map<String, Pin> = emptyMap(),
    val followRequests: List<String> = emptyList(),
    val presence: Map<String, Boolean> = emptyMap(),
    /** threadId → userId → typing-until (epoch ms). */
    val typing: Map<String, Map<String, Long>> = emptyMap(),
    val seenStories: Map<String, Long> = emptyMap(),
    val connection: Connection = Connection.OFFLINE,
    val syncing: Boolean = false,
    val initialSyncDone: Boolean = false,
)

/** Payload (de)serialisation by kind. Lenient on input: the web client may send `null` where Kotlin has a default. */
object Payloads {
    val json: Json = Json(Wire.json) { coerceInputValues = true }

    private val serializers: Map<String, KSerializer<*>> = mapOf(
        Kind.PROFILE to ProfilePayload.serializer(),
        Kind.POST to PostPayload.serializer(),
        Kind.REEL to ReelPayload.serializer(),
        Kind.STORY to StoryPayload.serializer(),
        Kind.HIGHLIGHT to HighlightPayload.serializer(),
        Kind.NOTE to NotePayload.serializer(),
        Kind.COMMENT to CommentPayload.serializer(),
        Kind.LIKE to LikePayload.serializer(),
        Kind.VOTE to VotePayload.serializer(),
        Kind.ANSWER to AnswerPayload.serializer(),
        Kind.COLLECTION to CollectionPayload.serializer(),
        Kind.SETTINGS to SettingsPayload.serializer(),
        Kind.ACTIVITY to ActivityPayload.serializer(),
        Kind.THREAD to ThreadPayload.serializer(),
        Kind.EPOCH to EpochPayload.serializer(),
        Kind.MESSAGE to MessagePayload.serializer(),
        Kind.REACTION to ReactionPayload.serializer(),
        Kind.LIVE to LivePayload.serializer(),
    )

    /** Kinds this client doesn't know (from a newer client) decode to a [JsonElement] and are left alone. */
    fun decode(kind: String, text: String): Any {
        val serializer = serializers[kind] ?: return json.parseToJsonElement(text)
        return json.decodeFromString(serializer, text)!!
    }

    fun encode(kind: String, payload: Any): String {
        @Suppress("UNCHECKED_CAST")
        val serializer = serializers[kind] as KSerializer<Any>? ?: return json.encodeToString(JsonElement.serializer(), payload as JsonElement)
        return json.encodeToString(serializer, payload)
    }
}
