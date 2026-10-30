package xyz.aezdek.aezgram.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.aezdek.aezgram.crypto.B64
import xyz.aezdek.aezgram.crypto.CryptoException
import xyz.aezdek.aezgram.crypto.ObjectCrypto
import xyz.aezdek.aezgram.crypto.ObjectHeader
import xyz.aezdek.aezgram.crypto.PublicKeys
import xyz.aezdek.aezgram.protocol.HighlightPayload
import xyz.aezdek.aezgram.protocol.Kind
import xyz.aezdek.aezgram.protocol.ObjectDto
import xyz.aezdek.aezgram.protocol.OkResponse
import xyz.aezdek.aezgram.protocol.SyncResponse
import xyz.aezdek.aezgram.protocol.ThreadDto
import xyz.aezdek.aezgram.protocol.UserDto
import xyz.aezdek.aezgram.protocol.WrapTaskDto
import xyz.aezdek.aezgram.protocol.WrapUpload
import xyz.aezdek.aezgram.protocol.WrapsRequest
import java.util.logging.Logger

/**
 * Keeps the local copy in step with the server: replays the change log from our cursor, verifies and decrypts what
 * arrives, pins identity keys, cascades deletions, and seals our content to newcomers (wrap tasks). A line-for-line
 * counterpart of web/src/core/sync.ts, so both clients treat every object the same way.
 */
class SyncEngine(
    private val api: Api,
    private val state: MutableStateFlow<AppState>,
    private val scope: CoroutineScope,
) {
    private val log = Logger.getLogger("aezgram.sync")
    var db: LocalDb? = null
        private set
    private var cursor = 0L
    private val syncLock = Mutex()
    @Volatile
    private var again = false
    @Volatile
    private var running: CompletableDeferred<Unit>? = null

    /** Objects waiting for a parent, an author or a highlight key. Guarded by [ingestLock]. */
    private val pending = LinkedHashMap<String, ObjectDto>()
    /** Keys for stories reachable through highlights (no wrap of their own after expiry). */
    private val refKeys = HashMap<String, ByteArray>()
    private val ingestLock = Any()

    fun open(db: LocalDb) {
        this.db = db
        cursor = db.cursor()
        val users = db.users()
        val threads = db.threads()
        val objects = db.objects()
        state.update {
            it.copy(
                users = users.associateBy(UserDto::id),
                threads = threads.associateBy(ThreadDto::id),
                pins = db.pins(),
                seenStories = db.seenStories(),
                presence = users.associate { u -> u.id to u.online },
                initialSyncDone = objects.isNotEmpty(),
            )
        }
        ingest(objects)
    }

    fun close() {
        db = null
        cursor = 0
        synchronized(ingestLock) {
            pending.clear()
            refKeys.clear()
        }
    }

    /** Coalesces concurrent requests: one sync runs at a time, and one more runs after it if anything asked. */
    suspend fun sync() {
        running?.let {
            again = true
            it.await()
            return
        }
        syncLock.withLock {
            val done = CompletableDeferred<Unit>()
            running = done
            try {
                do {
                    again = false
                    syncOnce()
                } while (again)
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
                throw e
            } finally {
                running = null
            }
        }
    }

    private suspend fun syncOnce() {
        val db = db ?: return
        state.update { it.copy(syncing = true) }
        try {
            var response: SyncResponse
            do {
                response = api.get("/sync?cursor=$cursor&limit=300")
                apply(db, response)
                cursor = response.cursor
                db.setCursor(cursor)
            } while (response.hasMore)
            val requests = response.followRequests
            state.update { it.copy(followRequests = requests, initialSyncDone = true) }
            if (response.pendingWrapTasks > 0) processWrapTasks()
        } finally {
            state.update { it.copy(syncing = false) }
        }
    }

    private fun apply(db: LocalDb, response: SyncResponse) {
        db.apply(response.objects, response.deletedIds, response.users, response.threads, response.removedThreadIds)
        applyUsers(response.users)
        if (response.threads.isNotEmpty() || response.removedThreadIds.isNotEmpty()) {
            state.update { s ->
                val threads = s.threads.toMutableMap()
                for (thread in response.threads) threads[thread.id] = thread
                for (id in response.removedThreadIds) threads.remove(id)
                s.copy(threads = threads)
            }
            for (id in response.removedThreadIds) removeWhere { it.dto.threadId == id }
        }
        for (id in response.deletedIds) remove(id)
        ingest(response.objects)
    }

    /** Pins identity keys on first sight and flags changes; refuses keys that are not self-signed. */
    fun applyUsers(users: List<UserDto>) {
        if (users.isEmpty()) return
        val nextPins = state.value.pins.toMutableMap()
        val accepted = ArrayList<UserDto>()
        var pinsChanged = false
        for (user in users) {
            val selfSigned = user.deleted || runCatching {
                PublicKeys(B64.decode(user.signPk), B64.decode(user.boxPk), B64.decode(user.boxPkSig)).isSelfSigned()
            }.getOrDefault(false)
            if (!selfSigned) {
                log.warning("Ignoring ${user.username}: box key not signed by identity key")
                continue
            }
            accepted += user
            val pin = nextPins[user.id]
            if (pin == null) {
                nextPins[user.id] = Pin(user.signPk, user.keyVersion)
                pinsChanged = true
            } else if (pin.signPk != user.signPk) {
                nextPins[user.id] = Pin(user.signPk, user.keyVersion, verified = false, changedFrom = pin.signPk, history = pin.history + pin.signPk)
                pinsChanged = true
            }
        }
        if (pinsChanged) db?.setPins(nextPins)
        state.update { s ->
            s.copy(
                users = s.users + accepted.associateBy(UserDto::id),
                pins = nextPins,
                presence = s.presence + accepted.associate { it.id to it.online },
            )
        }
    }

    /** Every identity key we have seen for a user, newest first: old content stays verifiable after a key reset. */
    private fun signKeysOf(userId: String, s: AppState): List<ByteArray> {
        val keys = s.keys
        if (userId == s.me?.id && keys != null) return listOf(keys.signPk)
        val out = LinkedHashSet<String>()
        s.users[userId]?.let { out += it.signPk }
        s.pins[userId]?.let { pin ->
            out += pin.signPk
            out += pin.history
        }
        return out.map(B64::decode)
    }

    /** Decrypts what it can, retrying the rest as their parents arrive. */
    fun ingest(dtos: List<ObjectDto>) {
        val highlightStories = ArrayList<String>()
        synchronized(ingestLock) {
            for (dto in dtos) pending[dto.id] = dto
            val decoded = HashMap<String, Entity>()
            val snapshot = state.value
            var progress = true
            while (progress && pending.isNotEmpty()) {
                progress = false
                for ((id, dto) in pending.entries.toList()) {
                    when (val result = decode(dto, decoded, snapshot)) {
                        Decoded.Wait -> continue
                        Decoded.Fail -> Unit
                        is Decoded.Ok -> {
                            decoded[id] = result.entity
                            if (dto.kind == Kind.HIGHLIGHT) {
                                for (story in result.entity.payloadAs<HighlightPayload>()?.stories.orEmpty()) {
                                    refKeys[story.id] = B64.decode(story.key)
                                    highlightStories += story.id
                                }
                            }
                        }
                    }
                    pending.remove(id)
                    progress = true
                }
            }
            if (decoded.isNotEmpty()) state.update { it.copy(entities = it.entities + decoded) }
        }
        if (highlightStories.isNotEmpty()) scope.launch { for (id in highlightStories) fetchReferenced(id) }
    }

    private sealed interface Decoded {
        data object Wait : Decoded
        data object Fail : Decoded
        class Ok(val entity: Entity) : Decoded
    }

    private fun decode(dto: ObjectDto, batch: Map<String, Entity>, s: AppState): Decoded {
        val keys = s.keys ?: return Decoded.Wait
        return try {
            val wrap = dto.wrap
            val parentId = dto.parentId
            val key: ByteArray = when {
                wrap != null -> ObjectCrypto.unwrap(B64.decode(wrap), keys, B64.decode(dto.commitment))
                dto.kind in Kind.CHILD_KEYED && parentId != null -> {
                    val parent = batch[parentId] ?: s.entities[parentId] ?: state.value.entities[parentId] ?: return Decoded.Wait
                    ObjectCrypto.childKey(parent.key, dto.id)
                }
                else -> refKeys[dto.id] ?: return Decoded.Wait
            }
            val signKeys = signKeysOf(dto.authorId, s)
            if (signKeys.isEmpty()) return Decoded.Wait
            var plain: ByteArray? = null
            var lastError: Exception? = null
            for (signPk in signKeys) {
                try {
                    plain = ObjectCrypto.open(headerOf(dto), B64.decode(dto.payload), B64.decode(dto.commitment), B64.decode(dto.signature), signPk, key)
                    break
                } catch (e: CryptoException) {
                    lastError = e
                }
            }
            val text = plain?.decodeToString() ?: throw lastError ?: CryptoException("no key opened ${dto.id}")
            Decoded.Ok(Entity(dto, key, Payloads.decode(dto.kind, text)))
        } catch (e: CryptoException) {
            log.warning("Dropping ${dto.kind} ${dto.id}: ${e.message}")
            Decoded.Fail
        } catch (e: kotlinx.serialization.SerializationException) {
            log.warning("Dropping ${dto.kind} ${dto.id}: ${e.message}")
            Decoded.Fail
        } catch (e: IllegalArgumentException) {
            log.warning("Dropping ${dto.kind} ${dto.id}: ${e.message}")
            Decoded.Fail
        }
    }

    /** A story inside a highlight may have no wrap for us any more; fetch it through the highlight. */
    private suspend fun fetchReferenced(id: String) {
        if (state.value.entities.containsKey(id) || synchronized(ingestLock) { pending.containsKey(id) }) return
        try {
            val dto: ObjectDto = api.get("/objects/$id")
            db?.putObject(dto)
            ingest(listOf(dto))
        } catch (_: Exception) {
            // gone
        }
    }

    /** Adds an object we just created (we already know its key). */
    fun add(dto: ObjectDto, key: ByteArray, payload: Any) {
        db?.putObject(dto)
        synchronized(ingestLock) { pending.remove(dto.id) }
        state.update { it.copy(entities = it.entities + (dto.id to Entity(dto, key, payload))) }
        ingest(emptyList())
    }

    fun remove(id: String) {
        val entities = state.value.entities
        val doomed = linkedSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (e in entities.values) {
                val parent = e.dto.parentId
                if (parent != null && parent in doomed && e.dto.id !in doomed) {
                    doomed += e.dto.id
                    grew = true
                }
            }
        }
        for (doomedId in doomed) db?.deleteObject(doomedId)
        synchronized(ingestLock) { doomed.forEach(pending::remove) }
        state.update { it.copy(entities = it.entities - doomed) }
    }

    private fun removeWhere(predicate: (Entity) -> Boolean) {
        for (e in state.value.entities.values) if (predicate(e)) remove(e.dto.id)
    }

    fun markStorySeen(id: String) {
        state.update { it.copy(seenStories = it.seenStories + (id to System.currentTimeMillis())) }
        db?.setSeenStories(state.value.seenStories)
    }

    fun acknowledgeKeyChange(userId: String, verified: Boolean = false) {
        val pin = state.value.pins[userId] ?: return
        val next = state.value.pins + (userId to pin.copy(changedFrom = null, verified = verified))
        state.update { it.copy(pins = next) }
        db?.setPins(next)
    }

    /** Our client's half of the "new follower / new member" handshake: seal our content keys to them. */
    suspend fun processWrapTasks() {
        val s = state.value
        val keys = s.keys ?: return
        val tasks: List<WrapTaskDto> = api.get("/wrap-tasks")
        for (task in tasks) {
            val recipient = state.value.users[task.recipientId]
            if (recipient == null || recipient.deleted) {
                api.post<OkResponse>("/wrap-tasks/${task.id}/done")
                continue
            }
            val boxPk = B64.decode(recipient.boxPk)
            val wraps = task.objects.mapNotNull { obj ->
                val wrap = obj.wrap ?: return@mapNotNull null
                runCatching {
                    val key = ObjectCrypto.unwrap(B64.decode(wrap), keys, B64.decode(obj.commitment))
                    WrapUpload(obj.id, recipient.id, B64.encode(ObjectCrypto.wrap(key, boxPk)))
                }.getOrNull()
            }
            for (chunk in wraps.chunked(500)) api.post<OkResponse, WrapsRequest>("/wraps", WrapsRequest(chunk))
            api.post<OkResponse>("/wrap-tasks/${task.id}/done")
        }
    }

    fun upsertThread(thread: ThreadDto) {
        state.update { it.copy(threads = it.threads + (thread.id to thread)) }
        db?.apply(emptyList(), emptyList(), emptyList(), listOf(thread), emptyList())
    }

    fun upsertUser(user: UserDto) {
        applyUsers(listOf(user))
        db?.putUser(user)
    }

    companion object {
        fun headerOf(dto: ObjectDto) = ObjectHeader(
            id = dto.id,
            kind = dto.kind,
            authorId = dto.authorId,
            parentId = dto.parentId,
            threadId = dto.threadId,
            audience = dto.audience,
            createdAt = dto.createdAt,
            expiresAt = dto.expiresAt,
            version = dto.version,
            blobIds = dto.blobIds,
            refIds = dto.refIds,
        )
    }
}
