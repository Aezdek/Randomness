package xyz.aezdek.hush.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.ObjectCrypto
import xyz.aezdek.hush.crypto.ObjectHeader
import xyz.aezdek.hush.crypto.Sodium
import xyz.aezdek.hush.protocol.ActivityPayload
import xyz.aezdek.hush.protocol.ActivityType
import xyz.aezdek.hush.protocol.AddMembersRequest
import xyz.aezdek.hush.protocol.AnswerPayload
import xyz.aezdek.hush.protocol.ArchiveRequest
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.CollectionPayload
import xyz.aezdek.hush.protocol.CommentPayload
import xyz.aezdek.hush.protocol.CreateThreadRequest
import xyz.aezdek.hush.protocol.EpochPayload
import xyz.aezdek.hush.protocol.FollowState
import xyz.aezdek.hush.protocol.HighlightPayload
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.LikePayload
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.NotePayload
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.ObjectUpload
import xyz.aezdek.hush.protocol.OkResponse
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ProfilePayload
import xyz.aezdek.hush.protocol.ReactionPayload
import xyz.aezdek.hush.protocol.ReadRequest
import xyz.aezdek.hush.protocol.ReelPayload
import xyz.aezdek.hush.protocol.SettingsPayload
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.ThreadDto
import xyz.aezdek.hush.protocol.ThreadPayload
import xyz.aezdek.hush.protocol.UserDto
import xyz.aezdek.hush.protocol.VotePayload
import xyz.aezdek.hush.protocol.WrapDto

/** Thrown for things the user can fix or retry; the message is fit to show. */
class UserFacingException(message: String) : Exception(message)

/**
 * Creates, edits and deletes objects: picks the content key, seals and signs the payload, wraps the key for exactly the
 * people who may read it, and keeps the local state in step. Mirrors web/src/core/compose.ts.
 */
class Composer(
    private val api: Api,
    private val state: MutableStateFlow<AppState>,
    private val engine: SyncEngine,
    private val index: () -> Index,
) {
    private fun self(): Pair<xyz.aezdek.hush.protocol.Me, xyz.aezdek.hush.crypto.AccountKeys> {
        val s = state.value
        val me = s.me ?: throw UserFacingException("Not signed in")
        val keys = s.keys ?: throw UserFacingException("Not signed in")
        return me to keys
    }

    /** Everyone who may read something with [audience], always including us. */
    fun recipientsFor(audience: String, explicit: List<String> = emptyList()): List<String> {
        val (me) = self()
        val live = state.value.users.values.filter { !it.deleted && !it.blockedByMe }
        val out = when (audience) {
            Audience.MEMBERS -> live.map { it.id }
            Audience.FOLLOWERS -> live.filter { it.followsMe == FollowState.FOLLOWING }.map { it.id }
            Audience.CLOSE_FRIENDS -> {
                val close = index().settings.closeFriends.toSet()
                live.filter { it.followsMe == FollowState.FOLLOWING && it.id in close }.map { it.id }
            }
            Audience.SELF -> emptyList()
            else -> explicit
        }
        return (listOf(me.id) + out).distinct()
    }

    /** The audience for something we publish to our profile: everyone if public, followers if private. */
    fun profileAudience(): String = if (state.value.me?.isPrivate == true) Audience.FOLLOWERS else Audience.MEMBERS

    private fun wrapsFor(key: ByteArray, recipients: List<String>): List<WrapDto> {
        val s = state.value
        return recipients.mapNotNull { id ->
            val boxPk = if (id == s.me?.id) s.keys?.boxPk else s.users[id]?.boxPk?.let(B64::decode)
            boxPk?.let { WrapDto(id, B64.encode(ObjectCrypto.wrap(key, it))) }
        }
    }

    class Draft(
        val kind: String,
        val audience: String,
        val payload: Any,
        val parentId: String? = null,
        val threadId: String? = null,
        val expiresAt: Long? = null,
        val blobIds: List<String> = emptyList(),
        val refIds: List<String> = emptyList(),
        val recipients: List<String>? = null,
        val id: String? = null,
        val key: ByteArray? = null,
    )

    class Sealed(val upload: ObjectUpload, val key: ByteArray)

    fun seal(draft: Draft, version: Int = 1, createdAt: Long = System.currentTimeMillis()): Sealed {
        val (me, keys) = self()
        val id = draft.id ?: newObjectId()
        val key = draft.key ?: if (draft.kind in Kind.CHILD_KEYED) {
            val parent = draft.parentId?.let { state.value.entities[it] } ?: throw UserFacingException("That is no longer available")
            ObjectCrypto.childKey(parent.key, id)
        } else {
            ObjectCrypto.newContentKey()
        }
        val header = ObjectHeader(id, draft.kind, me.id, draft.parentId, draft.threadId, draft.audience, createdAt, draft.expiresAt, version,
            draft.blobIds, draft.refIds)
        val sealed = ObjectCrypto.seal(header, Payloads.encode(draft.kind, draft.payload).encodeToByteArray(), key, keys)
        val childKeyed = draft.audience == Audience.PARENT || draft.kind == Kind.MESSAGE
        val wraps = if (childKeyed) emptyList() else wrapsFor(key, draft.recipients ?: recipientsFor(draft.audience))
        return Sealed(
            ObjectUpload(
                id = id, kind = draft.kind, parentId = draft.parentId, threadId = draft.threadId, audience = draft.audience,
                createdAt = createdAt, expiresAt = draft.expiresAt, version = version, blobIds = draft.blobIds, refIds = draft.refIds,
                payload = B64.encode(sealed.payload), commitment = B64.encode(sealed.commitment), signature = B64.encode(sealed.signature),
                wraps = wraps,
            ),
            key,
        )
    }

    suspend fun publish(draft: Draft): Entity {
        val sealed = seal(draft)
        val dto: ObjectDto = api.post("/objects", sealed.upload)
        engine.add(dto, sealed.key, draft.payload)
        return Entity(dto, sealed.key, draft.payload)
    }

    /** A new version with the same key; only the content changes. */
    suspend fun edit(entity: Entity, payload: Any, blobIds: List<String>? = null, refIds: List<String>? = null): Entity {
        val dto = entity.dto
        val sealed = seal(
            Draft(dto.kind, dto.audience, payload, dto.parentId, dto.threadId, dto.expiresAt, blobIds ?: dto.blobIds, refIds ?: dto.refIds,
                recipients = emptyList(), id = dto.id, key = entity.key),
            version = dto.version + 1,
            createdAt = dto.createdAt,
        )
        val updated: ObjectDto = api.put("/objects/${dto.id}", sealed.upload.copy(wraps = emptyList()))
        engine.add(updated, entity.key, payload)
        return Entity(updated, entity.key, payload)
    }

    suspend fun delete(id: String) {
        api.delete<OkResponse>("/objects/$id")
        engine.remove(id)
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        api.post<OkResponse, ArchiveRequest>("/objects/$id/archive", ArchiveRequest(archived))
        state.value.entities[id]?.let { engine.add(it.dto.copy(archived = archived), it.key, it.payload) }
    }

    // --------------------------------------------------------------------------------------------- profile & settings

    suspend fun saveProfile(payload: ProfilePayload): Entity {
        val existing = index().profiles[self().first.id]
        val blobIds = listOfNotNull(payload.avatar?.blobId)
        return if (existing != null) edit(existing, payload, blobIds = blobIds)
        else publish(Draft(Kind.PROFILE, Audience.MEMBERS, payload, blobIds = blobIds))
    }

    suspend fun saveSettings(change: (SettingsPayload) -> SettingsPayload): Entity {
        val existing = index().settingsEntity
        val payload = change(existing?.payloadAs<SettingsPayload>() ?: SettingsPayload())
        return if (existing != null) edit(existing, payload) else publish(Draft(Kind.SETTINGS, Audience.SELF, payload))
    }

    // ------------------------------------------------------------------------------------------------------ content

    suspend fun createPost(payload: PostPayload): Entity {
        val entity = publish(Draft(Kind.POST, profileAudience(), payload, blobIds = mediaBlobIds(payload.media)))
        notifyMentions(entity.id, payload.caption, payload.taggedUserIds)
        return entity
    }

    suspend fun createReel(payload: ReelPayload): Entity {
        val entity = publish(Draft(Kind.REEL, profileAudience(), payload, blobIds = mediaBlobIds(listOfNotNull(payload.video, payload.cover))))
        notifyMentions(entity.id, payload.caption, payload.taggedUserIds)
        return entity
    }

    suspend fun createStory(payload: StoryPayload, closeFriends: Boolean): Entity =
        publish(
            Draft(
                Kind.STORY,
                if (closeFriends) Audience.CLOSE_FRIENDS else profileAudience(),
                payload,
                blobIds = mediaBlobIds(listOfNotNull(payload.media, payload.drawing)),
                expiresAt = System.currentTimeMillis() + DAY,
            ),
        )

    suspend fun createHighlight(payload: HighlightPayload): Entity =
        publish(Draft(Kind.HIGHLIGHT, profileAudience(), payload, refIds = payload.stories.map { it.id },
            blobIds = payload.cover?.let { mediaBlobIds(listOf(it)) }.orEmpty()))

    suspend fun updateHighlight(entity: Entity, payload: HighlightPayload): Entity =
        edit(entity, payload, refIds = payload.stories.map { it.id }, blobIds = payload.cover?.let { mediaBlobIds(listOf(it)) }.orEmpty())

    suspend fun createNote(payload: NotePayload): Entity {
        index().notes[self().first.id]?.let { runCatching { delete(it.id) } }
        return publish(Draft(Kind.NOTE, profileAudience(), payload, expiresAt = System.currentTimeMillis() + DAY))
    }

    suspend fun comment(parentId: String, text: String): Entity {
        val entity = publish(Draft(Kind.COMMENT, Audience.PARENT, CommentPayload(text), parentId = parentId))
        notifyMentions(rootOf(parentId), text, emptyList())
        return entity
    }

    suspend fun like(parentId: String, emoji: String = "❤️"): Entity =
        publish(Draft(Kind.LIKE, Audience.PARENT, LikePayload(emoji), parentId = parentId))

    suspend fun unlike(parentId: String) {
        val me = self().first.id
        index().children[parentId]?.firstOrNull { it.dto.kind == Kind.LIKE && it.dto.authorId == me }?.let { delete(it.id) }
    }

    suspend fun toggleLike(parentId: String) {
        if (index().isLiked(parentId, self().first.id)) unlike(parentId) else like(parentId)
    }

    suspend fun vote(storyId: String, payload: VotePayload): Entity = publish(Draft(Kind.VOTE, Audience.PARENT, payload, parentId = storyId))

    /** Question-sticker answers go to the story's author only. */
    suspend fun answer(story: Entity, payload: AnswerPayload): Entity =
        publish(Draft(Kind.ANSWER, Audience.DIRECT, payload, refIds = listOf(story.id), recipients = listOf(self().first.id, story.dto.authorId)))

    private fun rootOf(id: String): String {
        val entities = state.value.entities
        var current = entities[id]
        while (current?.dto?.parentId != null && entities.containsKey(current.dto.parentId)) current = entities[current.dto.parentId]
        return current?.id ?: id
    }

    /** Tells @mentioned and tagged people, as a direct activity item only they can read. */
    private suspend fun notifyMentions(objectId: String, text: String, tagged: List<String>) {
        val me = self().first.id
        val byName = state.value.users.values.associate { it.username.lowercase() to it.id }
        val notify = LinkedHashMap<String, String>()
        for (match in MENTION.findAll(text)) byName[match.groupValues[1].lowercase()]?.let { notify[it] = ActivityType.MENTION }
        for (id in tagged) notify[id] = ActivityType.TAG
        notify.remove(me)
        for ((userId, type) in notify) {
            runCatching {
                publish(Draft(Kind.ACTIVITY, Audience.DIRECT, ActivityPayload(type, objectId, text.take(140)), recipients = listOf(me, userId)))
            }
        }
    }

    // -------------------------------------------------------------------------------------------------- collections

    suspend fun toggleSaved(postId: String): Entity {
        val saved = index().collections.firstOrNull { it.payloadAs<CollectionPayload>()?.isDefault == true }
            ?: return publish(Draft(Kind.COLLECTION, Audience.SELF, CollectionPayload("Saved", listOf(postId), isDefault = true)))
        val payload = saved.payloadAs<CollectionPayload>()!!
        val ids = if (postId in payload.postIds) payload.postIds - postId else listOf(postId) + payload.postIds
        return edit(saved, payload.copy(postIds = ids))
    }

    // ------------------------------------------------------------------------------------------------------- social

    suspend fun refreshUser(id: String): UserDto {
        val user: UserDto = api.get("/users/$id")
        engine.upsertUser(user)
        return user
    }

    suspend fun follow(id: String) { api.post<Unit>("/follows/$id"); refreshUser(id) }
    suspend fun unfollow(id: String) { api.delete<Unit>("/follows/$id"); refreshUser(id) }

    suspend fun acceptFollow(id: String) {
        api.post<Unit>("/follows/requests/$id/accept")
        state.update { it.copy(followRequests = it.followRequests - id) }
        refreshUser(id)
        engine.processWrapTasks()
    }

    suspend fun declineFollow(id: String) {
        api.post<Unit>("/follows/requests/$id/decline")
        state.update { it.copy(followRequests = it.followRequests - id) }
    }

    suspend fun removeFollower(id: String) { api.delete<Unit>("/followers/$id"); refreshUser(id) }
    suspend fun block(id: String) { api.post<Unit>("/blocks/$id"); refreshUser(id) }
    suspend fun unblock(id: String) { api.delete<Unit>("/blocks/$id"); refreshUser(id) }

    // -------------------------------------------------------------------------------------------------------- chats

    private fun latestEpoch(threadId: String): Entity? = index().epochs[threadId]?.lastOrNull()

    private fun sealEpoch(threadId: String, memberIds: List<String>) =
        seal(Draft(Kind.EPOCH, Audience.THREAD, EpochPayload(memberIds), threadId = threadId, recipients = memberIds))

    /** An existing one-to-one chat with [userId], or a new one. */
    suspend fun openDirect(userId: String): String {
        val me = self().first.id
        state.value.threads.values.firstOrNull { t ->
            !t.isGroup && t.members.size == 2 && t.members.any { it.userId == userId } && t.members.any { it.userId == me }
        }?.let { return it.id }
        return createThread(listOf(userId), isGroup = false, meta = ThreadPayload())
    }

    suspend fun createThread(memberIds: List<String>, isGroup: Boolean, meta: ThreadPayload): String {
        val me = self().first.id
        val all = (listOf(me) + memberIds).distinct()
        val threadId = newObjectId()
        val thread = seal(Draft(Kind.THREAD, Audience.THREAD, meta, id = threadId, threadId = threadId, recipients = all))
        val epoch = sealEpoch(threadId, all)
        return try {
            val created: ThreadDto = api.post("/threads", CreateThreadRequest(isGroup, memberIds, thread.upload, epoch.upload))
            engine.upsertThread(created)
            engine.sync()
            created.id
        } catch (e: ApiException) {
            // Someone else started our one-to-one chat first; the message carries its id.
            if (e.code != "thread_exists") throw e
            engine.sync()
            e.message ?: throw e
        }
    }

    /** If people left since the newest epoch, start a new one so they cannot read what comes next. */
    private suspend fun epochForSending(threadId: String): Entity {
        val thread = state.value.threads[threadId] ?: throw UserFacingException("That chat is gone")
        val latest = latestEpoch(threadId)
        val members = thread.members.map { it.userId }.sorted()
        if (latest != null && latest.payloadAs<EpochPayload>()?.memberIds?.sorted() == members) return latest
        val epoch = sealEpoch(threadId, members)
        val dto: ObjectDto = api.post("/threads/$threadId/epochs", epoch.upload)
        val payload = EpochPayload(members)
        engine.add(dto, epoch.key, payload)
        return Entity(dto, epoch.key, payload)
    }

    suspend fun sendMessage(threadId: String, payload: MessagePayload, audience: String = Audience.THREAD, expiresAt: Long? = null,
                            blobIds: List<String>? = null): Entity {
        repeat(2) { attempt ->
            val epoch = epochForSending(threadId)
            try {
                return publish(Draft(Kind.MESSAGE, audience, payload, parentId = epoch.id, threadId = threadId,
                    blobIds = blobIds ?: mediaBlobIds(payload.media), expiresAt = expiresAt))
            } catch (e: ApiException) {
                if (e.code != "stale_epoch" || attempt > 0) throw e
                engine.sync()
            }
        }
        throw UserFacingException("Could not send")
    }

    suspend fun react(message: Entity, emoji: String) {
        val me = self().first.id
        val mine = index().children[message.id]?.firstOrNull { it.dto.kind == Kind.REACTION && it.dto.authorId == me }
        if (mine != null) delete(mine.id)
        if (mine?.payloadAs<ReactionPayload>()?.emoji == emoji) return
        publish(Draft(Kind.REACTION, Audience.PARENT, ReactionPayload(emoji), parentId = message.id, threadId = message.dto.threadId))
    }

    suspend fun addMembers(threadId: String, userIds: List<String>) {
        val thread = state.value.threads[threadId] ?: return
        val meta = index().threadMeta[threadId]
        val all = (thread.members.map { it.userId } + userIds).distinct()
        val epoch = sealEpoch(threadId, all)
        val threadWraps = meta?.let { wrapsFor(it.key, userIds) }.orEmpty()
        val updated: ThreadDto = api.post("/threads/$threadId/members", AddMembersRequest(userIds, epoch.upload, threadWraps))
        engine.upsertThread(updated)
        engine.sync()
    }

    suspend fun leaveThread(threadId: String, userId: String = self().first.id) {
        api.delete<Unit>("/threads/$threadId/members/$userId")
        engine.sync()
    }

    suspend fun markRead(threadId: String) {
        api.post<Unit, ReadRequest>("/threads/$threadId/read", ReadRequest(System.currentTimeMillis()))
    }

    suspend fun recordView(id: String) {
        runCatching { api.post<Unit>("/objects/$id/views") }
    }

    companion object {
        const val DAY = 24L * 60 * 60 * 1000
        private val MENTION = Regex("@([a-z0-9._]{2,30})", RegexOption.IGNORE_CASE)

        fun newObjectId(): String = B64.encode(Sodium.random(16))

        fun mediaBlobIds(media: List<MediaRef>): List<String> = media.flatMap { listOfNotNull(it.blobId, it.poster?.blobId) }
    }
}
