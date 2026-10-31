package xyz.aezdek.hush.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for /api/v1. Binary fields are URL-safe base64 without padding. Mirrored by web/src/api/types.ts;
 * the shapes are documented in docs/PROTOCOL.md.
 */
object Wire {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

const val MAX_MEMBERS = 10

// ---------------------------------------------------------------------------------------------------------------- auth

@Serializable
data class DeviceInfo(val name: String, val platform: String)

@Serializable
data class PreloginRequest(val username: String)

@Serializable
data class PreloginResponse(val salt: String, val opsLimit: Long, val memLimit: Int)

@Serializable
data class SignupRequest(
    val inviteCode: String,
    val username: String,
    val salt: String,
    val opsLimit: Long,
    val memLimit: Int,
    val authKey: String,
    val signPk: String,
    val boxPk: String,
    val boxPkSig: String,
    val vault: String,
    val device: DeviceInfo,
)

@Serializable
data class LoginRequest(val username: String, val authKey: String, val device: DeviceInfo)

@Serializable
data class AuthResponse(val token: String, val me: Me, val vault: String)

@Serializable
data class Me(
    val id: String,
    val username: String,
    val isAdmin: Boolean,
    val isPrivate: Boolean,
    val showActivity: Boolean,
    val createdAt: Long,
    val keyVersion: Int,
)

@Serializable
data class UpdateMeRequest(val isPrivate: Boolean? = null, val showActivity: Boolean? = null)

@Serializable
data class ChangePasswordRequest(
    val currentAuthKey: String,
    val salt: String,
    val opsLimit: Long,
    val memLimit: Int,
    val authKey: String,
    val vault: String,
)

@Serializable
data class DeleteAccountRequest(val authKey: String)

/** New identity after a suspected compromise; [rewraps] re-seal every content key the client could open. */
@Serializable
data class ResetKeysRequest(
    val currentAuthKey: String,
    val salt: String,
    val opsLimit: Long,
    val memLimit: Int,
    val authKey: String,
    val vault: String,
    val signPk: String,
    val boxPk: String,
    val boxPkSig: String,
    val rewraps: List<WrapUpload>,
)

@Serializable
data class SessionDto(
    val id: String,
    val deviceName: String,
    val platform: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val current: Boolean,
)

// --------------------------------------------------------------------------------------------------------------- users

object FollowState {
    const val NONE = "none"
    const val REQUESTED = "requested"
    const val FOLLOWING = "following"
}

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val signPk: String,
    val boxPk: String,
    val boxPkSig: String,
    val keyVersion: Int,
    val isPrivate: Boolean,
    val createdAt: Long,
    /** My relationship to them. */
    val followState: String,
    /** Their relationship to me. */
    val followsMe: String,
    val blockedByMe: Boolean,
    /** Null when they hide their activity status, or when hidden from me. */
    val lastActiveAt: Long? = null,
    val online: Boolean = false,
    val deleted: Boolean = false,
)

@Serializable
data class FollowResult(val state: String)

@Serializable
data class IdList(val ids: List<String>)

// ------------------------------------------------------------------------------------------------------------- objects

/** Object kinds. The payload schema for each is in Payloads.kt. */
object Kind {
    const val PROFILE = "profile"
    const val POST = "post"
    const val REEL = "reel"
    const val STORY = "story"
    const val HIGHLIGHT = "highlight"
    const val NOTE = "note"
    const val COMMENT = "comment"
    const val LIKE = "like"
    const val VOTE = "vote"
    const val ANSWER = "answer"
    const val COLLECTION = "collection"
    const val SETTINGS = "settings"
    const val ACTIVITY = "activity"
    const val THREAD = "thread"
    const val EPOCH = "epoch"
    const val MESSAGE = "message"
    const val REACTION = "reaction"
    const val LIVE = "live"

    /** Kinds whose content key is derived from the parent's ([xyz.aezdek.hush.protocol.Kind] children). */
    val CHILD_KEYED = setOf(COMMENT, LIKE, VOTE, MESSAGE, REACTION)
}

/** Who an object is sealed to. Signed, so the server cannot widen it. */
object Audience {
    /** Every member of the instance (public accounts, profiles). */
    const val MEMBERS = "members"
    const val FOLLOWERS = "followers"
    const val CLOSE_FRIENDS = "close_friends"
    /** Only the author (saved collections, settings). */
    const val SELF = "self"
    /** Specific people (answers, activity items). */
    const val DIRECT = "direct"
    /** A chat thread's members. */
    const val THREAD = "thread"
    /** Chat message deleted from the server once every member has seen it. */
    const val VANISH = "vanish"
    /** Media deleted from the server once every recipient has opened it. */
    const val ONCE = "once"
    /** Inherits its parent's readers (likes, comments). */
    const val PARENT = "parent"
}

@Serializable
data class WrapDto(val recipientId: String, val sealed: String)

@Serializable
data class ObjectUpload(
    val id: String,
    val kind: String,
    val parentId: String? = null,
    val threadId: String? = null,
    val audience: String,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val version: Int,
    val blobIds: List<String> = emptyList(),
    val refIds: List<String> = emptyList(),
    val payload: String,
    val commitment: String,
    val signature: String,
    val wraps: List<WrapDto> = emptyList(),
)

@Serializable
data class ObjectDto(
    val id: String,
    val kind: String,
    val authorId: String,
    val parentId: String? = null,
    val threadId: String? = null,
    val audience: String,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val version: Int,
    val blobIds: List<String> = emptyList(),
    val refIds: List<String> = emptyList(),
    val payload: String,
    val commitment: String,
    val signature: String,
    /** The wrap sealed to the requesting user, if there is one (children and thread messages have none). */
    val wrap: String? = null,
    val archived: Boolean = false,
    /** Server receive time, for ordering. */
    val receivedAt: Long,
)

@Serializable
data class WrapUpload(val objectId: String, val recipientId: String, val sealed: String)

@Serializable
data class WrapsRequest(val wraps: List<WrapUpload>)

@Serializable
data class ArchiveRequest(val archived: Boolean)

@Serializable
data class ViewDto(val userId: String, val at: Long)

@Serializable
data class WrapTaskDto(
    val id: Long,
    val recipientId: String,
    val reason: String,
    /** The author's own objects that the recipient should now be able to read, each with the author's own wrap. */
    val objects: List<ObjectDto>,
)

// ---------------------------------------------------------------------------------------------------------------- sync

@Serializable
data class SyncResponse(
    val cursor: Long,
    val hasMore: Boolean,
    val objects: List<ObjectDto>,
    val deletedIds: List<String>,
    val users: List<UserDto>,
    val threads: List<ThreadDto>,
    val removedThreadIds: List<String>,
    val pendingWrapTasks: Int,
    val followRequests: List<String>,
)

// ------------------------------------------------------------------------------------------------------------- threads

@Serializable
data class ThreadMemberDto(val userId: String, val role: String, val joinedAt: Long, val lastReadAt: Long)

@Serializable
data class ThreadDto(
    val id: String,
    val isGroup: Boolean,
    val createdBy: String,
    val createdAt: Long,
    val members: List<ThreadMemberDto>,
)

@Serializable
data class CreateThreadRequest(val isGroup: Boolean, val memberIds: List<String>, val thread: ObjectUpload, val epoch: ObjectUpload)

/** New members get a fresh epoch (so they cannot read earlier messages) and a wrap of the thread's own key. */
@Serializable
data class AddMembersRequest(val userIds: List<String>, val epoch: ObjectUpload, val threadWraps: List<WrapDto>)

@Serializable
data class ReadRequest(val at: Long)

// --------------------------------------------------------------------------------------------------------------- blobs

@Serializable
data class StartUploadRequest(val size: Long)

@Serializable
data class UploadState(val uploadId: String, val size: Long, val received: Long, val chunkSize: Int)

@Serializable
data class BlobDto(val blobId: String, val size: Long)

// ------------------------------------------------------------------------------------------------------- realtime/push

/** WebSocket frames, both directions. `t` says which fields are set. */
@Serializable
data class WsFrame(
    val t: String,
    val cursor: Long? = null,
    val threadId: String? = null,
    val userId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val online: Boolean? = null,
    val signal: SignalDto? = null,
    /** On an outgoing signal: wake the recipient with a push if none of their devices is connected. */
    val ring: Boolean? = null,
    val callId: String? = null,
)

object WsType {
    const val SYNC = "sync"
    const val TYPING = "typing"
    const val PRESENCE = "presence"
    const val SIGNAL = "signal"
    const val PING = "ping"
    const val PONG = "pong"
    const val RING_TEST = "ring_test"
}

@Serializable
data class SignalDto(val sealed: String, val signature: String, val sentAt: Long)

@Serializable
data class VapidKey(val publicKey: String)

@Serializable
data class PushSubscriptionRequest(val endpoint: String, val p256dh: String, val auth: String)

/**
 * Which pushes a user doesn't want. The real switches live in the encrypted settings object, so each client mirrors
 * them here: Web Push has to show every push it receives, so muted ones must not be sent at all.
 */
@Serializable
data class PushPrefs(val mutedTypes: List<String> = emptyList(), val mutedThreads: List<String> = emptyList())

@Serializable
data class TestRingRequest(val delaySeconds: Int = 20)

@Serializable
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable
data class TurnResponse(val iceServers: List<IceServer>, val relay: Boolean)

// --------------------------------------------------------------------------------------------------------------- admin

@Serializable
data class InviteDto(val code: String, val createdAt: Long, val expiresAt: Long, val usedBy: String? = null)

@Serializable
data class AdminStatus(
    val users: Int,
    val maxUsers: Int,
    val diskFreeBytes: Long,
    val dataBytes: Long,
    val uploadsBlocked: Boolean,
    val lastBackupAt: Long? = null,
    val lastBackupOk: Boolean? = null,
    val backupMessage: String? = null,
    val version: String,
    /** The commit this server runs (written by update.sh), when known. */
    val commit: String? = null,
    val startedAt: Long = 0,
    /** The newest published release, checked at most hourly; null when unknown or GitHub can't be reached. */
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
)

/** A member as the admin panel lists them: no content, only what running the server needs. */
@Serializable
data class AdminUserDto(
    val id: String,
    val username: String,
    val isAdmin: Boolean,
    val createdAt: Long,
    val lastActiveAt: Long? = null,
    /** Encrypted media they uploaded. */
    val storageBytes: Long,
    val posts: Int,
    val sessions: Int,
)

@Serializable
data class AppVersion(val versionCode: Int, val versionName: String, val sha256: String, val size: Long, val url: String)

@Serializable
data class ApiError(val error: String, val message: String)

@Serializable
data class Health(val ok: Boolean, val version: String)

@Serializable
data class OkResponse(@SerialName("ok") val ok: Boolean = true)
