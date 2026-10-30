package xyz.aezdek.hush.server

import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.ObjectUpload
import xyz.aezdek.hush.protocol.ViewDto
import xyz.aezdek.hush.protocol.WrapTaskDto
import xyz.aezdek.hush.protocol.WrapUpload
import java.sql.ResultSet

data class ObjectRow(
    val id: String,
    val kind: String,
    val authorId: String,
    val parentId: String?,
    val threadId: String?,
    val audience: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val version: Int,
    val payload: ByteArray,
    val commitment: ByteArray,
    val signature: ByteArray,
    val archived: Boolean,
    val deleted: Boolean,
    val receivedAt: Long,
)

private fun ResultSet.toObjectRow() = ObjectRow(
    id = str("id"), kind = str("kind"), authorId = str("author_id"), parentId = strOrNull("parent_id"),
    threadId = strOrNull("thread_id"), audience = str("audience"), createdAt = getLong("created_at"),
    expiresAt = longOrNull("expires_at"), version = getInt("version"), payload = bytes("payload"),
    commitment = bytes("commitment"), signature = bytes("signature"), archived = bool("archived"),
    deleted = bool("deleted"), receivedAt = getLong("received_at"),
)

private const val HOUR = 60L * 60 * 1000
private const val MAX_PAYLOAD = 256 * 1024
private const val MAX_DEPTH = 6

private val TOP_LEVEL = setOf(Kind.PROFILE, Kind.POST, Kind.REEL, Kind.STORY, Kind.HIGHLIGHT, Kind.NOTE, Kind.LIVE)
private val SELF_KINDS = setOf(Kind.SETTINGS, Kind.COLLECTION)
private val DIRECT_KINDS = setOf(Kind.ANSWER, Kind.ACTIVITY)
private val PARENT_KINDS = mapOf(
    Kind.COMMENT to setOf(Kind.POST, Kind.REEL, Kind.COMMENT),
    Kind.LIKE to setOf(Kind.POST, Kind.REEL, Kind.COMMENT, Kind.STORY, Kind.NOTE),
    Kind.VOTE to setOf(Kind.STORY),
)
/** One per author and parent: liking twice is a no-op, unliking deletes. */
private val ONE_PER_PARENT = setOf(Kind.LIKE, Kind.VOTE, Kind.REACTION)
private val PUSH_KINDS = setOf(Kind.MESSAGE, Kind.COMMENT, Kind.LIKE, Kind.ACTIVITY, Kind.ANSWER, Kind.REACTION)

/**
 * The object store: the heart of the server. Everything users create is an [ObjectRow] whose payload is ciphertext.
 *
 * Access ([canRead]) is decided from server-visible structure only:
 * - the author can always read;
 * - top-level objects: whoever holds a wrap (a sealed content key);
 * - children (likes, comments, votes): whoever can read the parent;
 * - chat objects: current thread members holding the right epoch's wrap;
 * - objects referenced by something you can read (stories inside a highlight).
 */
class Objects(private val db: Db, private val clock: Clock) {

    fun Tx.load(id: String): ObjectRow? = one("SELECT * FROM objects WHERE id = ?", id) { it.toObjectRow() }

    fun Tx.wrapFor(objectId: String, userId: String): ByteArray? =
        one("SELECT sealed FROM wraps WHERE object_id = ? AND recipient_id = ?", objectId, userId) { it.bytes("sealed") }

    fun Tx.isMember(threadId: String, userId: String) =
        exists("SELECT 1 FROM thread_members WHERE thread_id = ? AND user_id = ?", threadId, userId)

    private fun Tx.blocked(a: String, b: String) =
        exists("SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)", a, b, b, a)

    fun Tx.canRead(userId: String, obj: ObjectRow, depth: Int = 0): Boolean {
        if (obj.deleted) return false
        if (obj.authorId == userId) return true
        if (obj.archived || depth > MAX_DEPTH) return false
        if (obj.threadId != null) {
            if (!isMember(obj.threadId, userId)) return false
            return when (obj.kind) {
                Kind.THREAD, Kind.EPOCH -> wrapFor(obj.id, userId) != null
                else -> obj.parentId?.let { load(it) }?.let { canRead(userId, it, depth + 1) } ?: false
            }
        }
        if (blocked(userId, obj.authorId)) return false
        if (wrapFor(obj.id, userId) != null) return true
        if (obj.parentId != null) {
            val parent = load(obj.parentId) ?: return false
            if (canRead(userId, parent, depth + 1)) return true
        }
        if (depth == 0) {
            val referrers = strings("SELECT object_id FROM object_refs WHERE ref_id = ?", obj.id)
            if (referrers.any { id -> load(id)?.let { canRead(userId, it, depth + 1) } == true }) return true
        }
        return false
    }

    /** Everyone who can currently read [obj]: who to tell when it changes. */
    fun Tx.readers(obj: ObjectRow, depth: Int = 0): Set<String> {
        if (obj.archived) return setOf(obj.authorId)
        val out = linkedSetOf(obj.authorId)
        if (obj.threadId != null) {
            val members = strings("SELECT user_id FROM thread_members WHERE thread_id = ?", obj.threadId)
            when (obj.kind) {
                Kind.THREAD, Kind.EPOCH -> out += strings("SELECT recipient_id FROM wraps WHERE object_id = ?", obj.id).filter { it in members }
                else -> obj.parentId?.let { load(it) }?.takeIf { depth < MAX_DEPTH }?.let { out += readers(it, depth + 1).filter { m -> m in members } }
            }
            return out
        }
        out += strings("SELECT recipient_id FROM wraps WHERE object_id = ?", obj.id)
        if (obj.parentId != null && depth < MAX_DEPTH) load(obj.parentId)?.let { out += readers(it, depth + 1) }
        return out
    }

    fun Tx.toDto(obj: ObjectRow, viewerId: String) = ObjectDto(
        id = obj.id, kind = obj.kind, authorId = obj.authorId, parentId = obj.parentId, threadId = obj.threadId,
        audience = obj.audience, createdAt = obj.createdAt, expiresAt = obj.expiresAt, version = obj.version,
        blobIds = strings("SELECT blob_id FROM object_blobs WHERE object_id = ? ORDER BY blob_id", obj.id),
        refIds = strings("SELECT ref_id FROM object_refs WHERE object_id = ? ORDER BY ref_id", obj.id),
        payload = B64u.enc(obj.payload), commitment = B64u.enc(obj.commitment), signature = B64u.enc(obj.signature),
        wrap = wrapFor(obj.id, viewerId)?.let(B64u::enc), archived = obj.archived, receivedAt = obj.receivedAt,
    )

    fun get(userId: String, id: String): ObjectDto = db.tx {
        val obj = load(id)?.takeIf { canRead(userId, it) } ?: notFound()
        toDto(obj, userId)
    }

    // ------------------------------------------------------------------------------------------------------ create

    fun create(userId: String, up: ObjectUpload, fx: Effects, allowThreadKinds: Boolean = false): ObjectDto = db.tx {
        createIn(this, userId, up, fx, allowThreadKinds)
    }

    fun createIn(tx: Tx, userId: String, up: ObjectUpload, fx: Effects, allowThreadKinds: Boolean = false): ObjectDto = with(tx) {
        val now = clock.now()
        validateCommon(up)
        if (up.version != 1) badRequest("A new object starts at version 1")
        if (load(up.id) != null) conflict("Object ${up.id} exists", "exists")

        val parent = up.parentId?.let { load(it) ?: notFound("Parent not found") }
        if (parent != null && !canRead(userId, parent)) notFound("Parent not found")

        when (up.kind) {
            in TOP_LEVEL -> {
                if (parent != null || up.threadId != null) badRequest("${up.kind} cannot have a parent or thread")
                val allowed = when (up.kind) {
                    Kind.PROFILE -> setOf(Audience.MEMBERS)
                    Kind.STORY -> setOf(Audience.MEMBERS, Audience.FOLLOWERS, Audience.CLOSE_FRIENDS)
                    else -> setOf(Audience.MEMBERS, Audience.FOLLOWERS)
                }
                if (up.audience !in allowed) badRequest("${up.kind} cannot have audience ${up.audience}")
                if (up.kind == Kind.PROFILE && exists("SELECT 1 FROM objects WHERE author_id = ? AND kind = ? AND deleted = 0", userId, Kind.PROFILE)) {
                    conflict("You already have a profile object; update it instead", "exists")
                }
                if (up.kind in setOf(Kind.STORY, Kind.NOTE, Kind.LIVE)) {
                    val expires = up.expiresAt ?: badRequest("${up.kind} needs expiresAt")
                    if (expires <= now || expires > now + 25 * HOUR) badRequest("expiresAt must be within 25 hours")
                }
            }
            in SELF_KINDS -> {
                if (parent != null || up.threadId != null || up.audience != Audience.SELF) badRequest("${up.kind} is private to its author")
            }
            in DIRECT_KINDS -> {
                if (parent != null || up.threadId != null || up.audience != Audience.DIRECT) badRequest("${up.kind} is sent directly")
            }
            in PARENT_KINDS.keys -> {
                val p = parent ?: badRequest("${up.kind} needs a parent")
                if (p.kind !in PARENT_KINDS.getValue(up.kind)) badRequest("A ${up.kind} cannot go on a ${p.kind}")
                if (up.threadId != null || up.audience != Audience.PARENT) badRequest("${up.kind} inherits its parent's audience")
            }
            Kind.THREAD, Kind.EPOCH -> {
                if (!allowThreadKinds) badRequest("Use the threads API for ${up.kind}")
                if (up.audience != Audience.THREAD || up.threadId == null || parent != null) badRequest("bad ${up.kind}")
            }
            Kind.MESSAGE -> {
                val threadId = up.threadId ?: badRequest("A message needs a thread")
                if (!isMember(threadId, userId)) forbidden("You are not in that chat")
                val epoch = parent ?: badRequest("A message needs its epoch as parent")
                if (epoch.kind != Kind.EPOCH || epoch.threadId != threadId) badRequest("Parent must be an epoch of the thread")
                val latest = one(
                    "SELECT id FROM objects WHERE thread_id = ? AND kind = ? AND deleted = 0 ORDER BY received_at DESC LIMIT 1",
                    threadId, Kind.EPOCH,
                ) { it.str("id") }
                if (latest != epoch.id) conflict("The chat's key changed; refresh and resend", "stale_epoch")
                if (up.audience !in setOf(Audience.THREAD, Audience.VANISH, Audience.ONCE)) badRequest("bad message audience")
                up.expiresAt?.let { if (it <= now || it > now + 7 * 24 * HOUR) badRequest("Messages disappear within 7 days") }
            }
            Kind.REACTION -> {
                val p = parent ?: badRequest("A reaction needs a message")
                if (p.kind != Kind.MESSAGE || up.threadId != p.threadId || up.audience != Audience.PARENT) badRequest("bad reaction")
            }
            else -> badRequest("Unknown kind ${up.kind}")
        }
        if (up.kind in ONE_PER_PARENT && exists(
                "SELECT 1 FROM objects WHERE author_id = ? AND parent_id = ? AND kind = ? AND deleted = 0", userId, up.parentId, up.kind,
            )
        ) {
            conflict("Already done", "exists")
        }

        // Wraps: never on children (they derive their key), always one for the author on everything else.
        val childKeyed = up.audience == Audience.PARENT || up.kind == Kind.MESSAGE
        if (childKeyed && up.wraps.isNotEmpty()) badRequest("${up.kind} derives its key from its parent; no wraps")
        if (!childKeyed && up.wraps.none { it.recipientId == userId }) badRequest("Include a wrap for yourself")
        if (up.audience == Audience.SELF && up.wraps.any { it.recipientId != userId }) badRequest("Only you can read ${up.kind}")
        val members = if (up.threadId != null) strings("SELECT user_id FROM thread_members WHERE thread_id = ?", up.threadId).toSet() else null
        for (wrap in up.wraps) {
            if (!exists("SELECT 1 FROM users WHERE id = ? AND deleted = 0", wrap.recipientId)) badRequest("Unknown recipient")
            if (members != null && wrap.recipientId !in members) badRequest("Recipient is not in the chat")
        }
        // Someone who blocked the author (which the author's client cannot know) silently gets no wrap.
        val wraps = up.wraps.filter { it.recipientId == userId || members != null || !blocked(userId, it.recipientId) }
        checkBlobs(userId, up.blobIds)
        checkRefs(userId, up.refIds)

        exec(
            """INSERT INTO objects (id, kind, author_id, parent_id, thread_id, audience, created_at, expires_at, version,
               payload, commitment, signature, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            up.id, up.kind, userId, up.parentId, up.threadId, up.audience, up.createdAt, up.expiresAt, up.version,
            B64u.dec(up.payload, "payload", max = MAX_PAYLOAD), B64u.dec(up.commitment, "commitment", size = 32),
            B64u.dec(up.signature, "signature", size = 64), now,
        )
        for (wrap in wraps.distinctBy { it.recipientId }) {
            exec(
                "INSERT INTO wraps (object_id, recipient_id, sealed, created_at) VALUES (?, ?, ?, ?)",
                up.id, wrap.recipientId, B64u.dec(wrap.sealed, "wrap", max = 256), now,
            )
        }
        up.blobIds.distinct().forEach { exec("INSERT INTO object_blobs (object_id, blob_id) VALUES (?, ?)", up.id, it) }
        up.refIds.distinct().forEach { exec("INSERT INTO object_refs (object_id, ref_id) VALUES (?, ?)", up.id, it) }
        queueMissedWraps(userId, up, wraps.mapTo(HashSet()) { it.recipientId }, fx, now)

        val obj = load(up.id)!!
        val readers = readers(obj)
        change(readers, ChangeType.OBJECT, obj.id, fx, now)
        if (obj.kind in PUSH_KINDS) {
            val notify = when (obj.kind) {
                Kind.COMMENT, Kind.LIKE, Kind.REACTION -> setOfNotNull(parent?.authorId)
                else -> readers
            }
            notify.filter { it != userId }.forEach { fx.pushes += PushHint(it, obj.kind, userId, obj.threadId ?: obj.id) }
        }
        toDto(obj, userId)
    }

    private fun Tx.validateCommon(up: ObjectUpload) {
        if (!Ids.isObjectId(up.id)) badRequest("Object ids are 16–32 base64url characters")
        if (up.blobIds.size > 40) badRequest("Too many media files")
        if (up.refIds.size > 200) badRequest("Too many references")
        if (up.wraps.size > 20) badRequest("Too many recipients")
    }

    private fun Tx.checkBlobs(userId: String, blobIds: List<String>) {
        for (blobId in blobIds.distinct()) {
            val uploader = one("SELECT uploader_id FROM blobs WHERE id = ?", blobId) { it.str("uploader_id") } ?: badRequest("Unknown media $blobId")
            if (uploader != userId && !canReadBlob(userId, blobId)) badRequest("Unknown media $blobId")
        }
    }

    private fun Tx.checkRefs(userId: String, refIds: List<String>) {
        for (ref in refIds.distinct()) {
            val obj = load(ref)
            if (obj == null || !canRead(userId, obj)) badRequest("Unknown reference $ref")
        }
    }

    fun Tx.canReadBlob(userId: String, blobId: String): Boolean {
        if (exists("SELECT 1 FROM blobs WHERE id = ? AND uploader_id = ?", blobId, userId)) return true
        return strings("SELECT object_id FROM object_blobs WHERE blob_id = ?", blobId)
            .any { id -> load(id)?.let { canRead(userId, it) } == true }
    }

    // ------------------------------------------------------------------------------------------------------ update

    /** A new version of an object: same key (commitment), same routing fields, new ciphertext. */
    fun update(userId: String, id: String, up: ObjectUpload, fx: Effects): ObjectDto = db.tx {
        val now = clock.now()
        val old = load(id)?.takeIf { !it.deleted } ?: notFound()
        if (old.authorId != userId) forbidden("Only the author can edit this")
        validateCommon(up)
        if (up.id != id || up.kind != old.kind || up.parentId != old.parentId || up.threadId != old.threadId ||
            up.audience != old.audience || up.createdAt != old.createdAt
        ) {
            badRequest("Only the content of an object can change")
        }
        if (up.version != old.version + 1) conflict("Expected version ${old.version + 1}", "version")
        val commitment = B64u.dec(up.commitment, "commitment", size = 32)
        if (!commitment.contentEquals(old.commitment)) badRequest("Edits keep the same content key")
        val newExpiry = up.expiresAt
        if (old.expiresAt != null && (newExpiry == null || newExpiry > old.expiresAt)) badRequest("Cannot extend expiry")
        checkBlobs(userId, up.blobIds)
        checkRefs(userId, up.refIds)
        exec(
            "UPDATE objects SET version = ?, payload = ?, signature = ?, expires_at = ? WHERE id = ?",
            up.version, B64u.dec(up.payload, "payload", max = MAX_PAYLOAD), B64u.dec(up.signature, "signature", size = 64), up.expiresAt, id,
        )
        exec("DELETE FROM object_blobs WHERE object_id = ?", id)
        exec("DELETE FROM object_refs WHERE object_id = ?", id)
        up.blobIds.distinct().forEach { exec("INSERT INTO object_blobs (object_id, blob_id) VALUES (?, ?)", id, it) }
        up.refIds.distinct().forEach { exec("INSERT INTO object_refs (object_id, ref_id) VALUES (?, ?)", id, it) }
        val obj = load(id)!!
        change(readers(obj), ChangeType.OBJECT, id, fx, now)
        toDto(obj, userId)
    }

    // ------------------------------------------------------------------------------------------------------ delete

    fun delete(userId: String, id: String, fx: Effects) = db.tx {
        val obj = load(id)?.takeIf { !it.deleted } ?: notFound()
        val threadAdmin = obj.threadId != null && obj.kind != Kind.THREAD && obj.kind != Kind.EPOCH &&
            exists("SELECT 1 FROM threads WHERE id = ? AND created_by = ?", obj.threadId, userId)
        if (obj.authorId != userId && !threadAdmin) forbidden("Only the author can delete this")
        if (obj.kind == Kind.THREAD || obj.kind == Kind.EPOCH) badRequest("Leave the chat instead")
        deleteTree(id, fx, clock.now())
    }

    /** Tombstones [id] and all its descendants, telling everyone who could read them. */
    fun Tx.deleteTree(id: String, fx: Effects, now: Long) {
        val root = load(id) ?: return
        val readers = readers(root)
        val all = ArrayList<String>()
        var frontier = listOf(id)
        while (frontier.isNotEmpty()) {
            all += frontier
            frontier = frontier.flatMap { strings("SELECT id FROM objects WHERE parent_id = ? AND deleted = 0", it) }
        }
        for (objectId in all) {
            exec("UPDATE objects SET deleted = 1, payload = ?, signature = ? WHERE id = ?", ByteArray(0), ByteArray(0), objectId)
            exec("DELETE FROM wraps WHERE object_id = ?", objectId)
            exec("DELETE FROM object_blobs WHERE object_id = ?", objectId)
            exec("DELETE FROM object_refs WHERE object_id = ?", objectId)
            exec("DELETE FROM views WHERE object_id = ?", objectId)
        }
        change(readers, ChangeType.DELETED, id, fx, now)
    }

    fun archive(userId: String, id: String, archived: Boolean, fx: Effects) = db.tx {
        val obj = load(id)?.takeIf { !it.deleted } ?: notFound()
        if (obj.authorId != userId) forbidden("Only the author can archive this")
        if (obj.kind !in setOf(Kind.POST, Kind.REEL, Kind.STORY)) badRequest("Only posts, reels and stories can be archived")
        val before = readers(obj.copy(archived = false))
        exec("UPDATE objects SET archived = ? WHERE id = ?", archived, id)
        val now = clock.now()
        change(before - userId, if (archived) ChangeType.DELETED else ChangeType.OBJECT, id, fx, now)
        change(userId, ChangeType.OBJECT, id, fx, now)
    }

    // ------------------------------------------------------------------------------------------------------- wraps

    /**
     * Adds wraps. An author may seal their objects to anyone allowed to read them; anyone may replace their *own*
     * wrap on something they can read (after a key reset). Readers verify every key against the signed commitment.
     */
    fun addWraps(userId: String, wraps: List<WrapUpload>, fx: Effects) = db.tx {
        val now = clock.now()
        if (wraps.size > 2000) badRequest("Too many wraps at once")
        for (wrap in wraps) {
            val obj = load(wrap.objectId)?.takeIf { !it.deleted } ?: continue
            val sealed = B64u.dec(wrap.sealed, "sealed", max = 256)
            if (obj.audience == Audience.PARENT || obj.kind == Kind.MESSAGE) badRequest("${obj.kind} has no wraps")
            when {
                obj.authorId == userId -> {
                    if (!exists("SELECT 1 FROM users WHERE id = ? AND deleted = 0", wrap.recipientId)) badRequest("Unknown recipient")
                    if (obj.audience == Audience.SELF && wrap.recipientId != userId) badRequest("Private object")
                    if (obj.threadId != null && !isMember(obj.threadId, wrap.recipientId)) badRequest("Recipient is not in the chat")
                    if (wrap.recipientId != userId && blocked(userId, wrap.recipientId)) continue
                }
                wrap.recipientId == userId && canRead(userId, obj) -> Unit
                else -> forbidden("Cannot add that wrap")
            }
            val isNew = wrapFor(obj.id, wrap.recipientId) == null
            exec(
                "INSERT OR REPLACE INTO wraps (object_id, recipient_id, sealed, created_at) VALUES (?, ?, ?, ?)",
                obj.id, wrap.recipientId, sealed, now,
            )
            if (isNew) notifyTree(obj.id, wrap.recipientId, fx, now)
        }
    }

    /**
     * A client that hadn't heard of someone yet (they joined or followed moments ago) seals a new post without them, and
     * the "seal your old posts for them" task may already have run before the post existed. Queue another task for
     * anyone the audience includes but the wraps left out, and nudge the author's client to sync and seal it.
     */
    private fun Tx.queueMissedWraps(authorId: String, up: ObjectUpload, wrapped: Set<String>, fx: Effects, now: Long) {
        if (up.kind !in TOP_LEVEL || (up.audience != Audience.MEMBERS && up.audience != Audience.FOLLOWERS)) return
        val authorPrivate = one("SELECT is_private FROM users WHERE id = ?", authorId) { it.bool("is_private") } ?: true
        val missed = strings("SELECT id FROM users WHERE deleted = 0 AND id != ?", authorId).filter { other ->
            other !in wrapped && !blocked(authorId, other) && (
                up.audience == Audience.MEMBERS || !authorPrivate ||
                    followState(other, authorId) == xyz.aezdek.hush.protocol.FollowState.FOLLOWING
                )
        }
        missed.forEach { addWrapTask(authorId, it, "missed", now) }
        if (missed.isNotEmpty()) fx.syncUsers += authorId
    }

    /** Tells [userId] about an object and every descendant (comments, likes) they can now read. */
    private fun Tx.notifyTree(id: String, userId: String, fx: Effects, now: Long) {
        var frontier = listOf(id)
        var depth = 0
        while (frontier.isNotEmpty() && depth <= MAX_DEPTH) {
            frontier.forEach { change(userId, ChangeType.OBJECT, it, fx, now) }
            frontier = frontier.flatMap { strings("SELECT id FROM objects WHERE parent_id = ? AND deleted = 0", it) }
            depth++
        }
    }

    /** Removes [recipientId]'s wraps on [authorId]'s top-level objects with one of [audiences]. */
    fun Tx.revokeWraps(authorId: String, recipientId: String, audiences: Set<String>, fx: Effects, now: Long, exceptKinds: Set<String> = emptySet()) {
        if (audiences.isEmpty()) return
        val ids = strings(
            """SELECT o.id FROM objects o JOIN wraps w ON w.object_id = o.id
               WHERE o.author_id = ? AND w.recipient_id = ? AND o.thread_id IS NULL AND o.audience IN (${placeholders(audiences.size)})""",
            authorId, recipientId, *audiences.toTypedArray(),
        ).filter { id -> load(id)?.kind !in exceptKinds }
        for (id in ids) {
            exec("DELETE FROM wraps WHERE object_id = ? AND recipient_id = ?", id, recipientId)
            change(recipientId, ChangeType.DELETED, id, fx, now)
        }
    }

    // ------------------------------------------------------------------------------------------------------- views

    fun recordView(userId: String, id: String, fx: Effects) = db.tx {
        val obj = load(id)?.takeIf { canRead(userId, it) } ?: notFound()
        if (obj.authorId == userId) return@tx
        val now = clock.now()
        exec("INSERT OR IGNORE INTO views (object_id, user_id, at) VALUES (?, ?, ?)", id, userId, now)
        change(obj.authorId, ChangeType.OBJECT, id, fx, now)
        if (obj.audience == Audience.ONCE) deleteOnceIfSeen(obj, fx, now)
    }

    fun views(userId: String, id: String): List<ViewDto> = db.tx {
        val obj = load(id) ?: notFound()
        if (obj.authorId != userId) forbidden("Only the author sees who viewed this")
        query("SELECT user_id, at FROM views WHERE object_id = ? ORDER BY at DESC", id) { ViewDto(it.str("user_id"), it.getLong("at")) }
    }

    private fun Tx.deleteOnceIfSeen(obj: ObjectRow, fx: Effects, now: Long) {
        val threadId = obj.threadId ?: return
        val recipients = strings("SELECT user_id FROM thread_members WHERE thread_id = ? AND user_id != ?", threadId, obj.authorId)
        val viewed = strings("SELECT user_id FROM views WHERE object_id = ?", obj.id).toSet()
        if (recipients.all { it in viewed }) deleteTree(obj.id, fx, now)
    }

    // -------------------------------------------------------------------------------------------------- wrap tasks

    fun Tx.openTaskCount(authorId: String): Int =
        (long("SELECT COUNT(*) FROM wrap_tasks WHERE author_id = ? AND done_at IS NULL", authorId) ?: 0).toInt()

    /** The author's open tasks, each with the objects to seal for its recipient (and the author's own wraps). */
    fun wrapTasks(authorId: String): List<WrapTaskDto> = db.tx {
        val now = clock.now()
        query("SELECT id, recipient_id, reason FROM wrap_tasks WHERE author_id = ? AND done_at IS NULL ORDER BY id", authorId) {
            Triple(it.getLong("id"), it.str("recipient_id"), it.str("reason"))
        }.map { (taskId, recipientId, reason) ->
            exec("UPDATE wrap_tasks SET fetched_at = ? WHERE id = ?", now, taskId)
            WrapTaskDto(taskId, recipientId, reason, unsealedFor(authorId, recipientId, now, since = 0).map { toDto(it, authorId) })
        }
    }

    /**
     * Closes a task, unless something the recipient should read appeared after the client fetched it (say a post made
     * while the client was sealing, and so sealed without them): then it stays open for the client's next sync.
     */
    fun completeTask(authorId: String, taskId: Long, fx: Effects): Unit = db.tx {
        val (recipientId, fetchedAt) = one("SELECT recipient_id, fetched_at FROM wrap_tasks WHERE id = ? AND author_id = ?", taskId, authorId) {
            it.str("recipient_id") to (it.longOrNull("fetched_at") ?: 0L)
        } ?: notFound()
        if (unsealedFor(authorId, recipientId, clock.now(), since = fetchedAt).isEmpty()) {
            exec("UPDATE wrap_tasks SET done_at = ? WHERE id = ? AND done_at IS NULL", clock.now(), taskId)
        } else {
            fx.syncUsers += authorId
        }
    }

    /** [authorId]'s live top-level objects that [recipientId] may read but has no wrap for, received at or after [since]. */
    private fun Tx.unsealedFor(authorId: String, recipientId: String, now: Long, since: Long): List<ObjectRow> {
        if (!exists("SELECT 1 FROM users WHERE id = ? AND deleted = 0", recipientId) || blocked(authorId, recipientId)) return emptyList()
        val authorPrivate = one("SELECT is_private FROM users WHERE id = ?", authorId) { it.bool("is_private") } ?: true
        val follows = followState(recipientId, authorId) == xyz.aezdek.hush.protocol.FollowState.FOLLOWING
        val audiences = buildList {
            add(Audience.MEMBERS)
            if (follows || !authorPrivate) add(Audience.FOLLOWERS)
        }
        return query(
            """SELECT * FROM objects o WHERE o.author_id = ? AND o.deleted = 0 AND o.archived = 0
               AND o.thread_id IS NULL AND o.parent_id IS NULL AND o.kind IN (${placeholders(TOP_LEVEL.size)})
               AND o.audience IN (${placeholders(audiences.size)})
               AND (o.expires_at IS NULL OR o.expires_at > ?) AND o.received_at >= ?
               AND NOT EXISTS (SELECT 1 FROM wraps w WHERE w.object_id = o.id AND w.recipient_id = ?)
               ORDER BY o.received_at""",
            authorId, *TOP_LEVEL.toTypedArray(), *audiences.toTypedArray(), now, since, recipientId,
        ) { it.toObjectRow() }
    }

    // ------------------------------------------------------------------------------------------------------ expiry

    /**
     * - Stories: after 24 h, viewers lose their wraps; the author keeps the story in their archive.
     * - Notes, live streams and timed messages: deleted.
     * - Vanish-mode messages: deleted once every other member has read past them.
     */
    fun expire(fx: Effects) = db.tx {
        val now = clock.now()
        val stories = query(
            """SELECT DISTINCT o.id, o.author_id FROM objects o JOIN wraps w ON w.object_id = o.id
               WHERE o.kind = ? AND o.deleted = 0 AND o.expires_at <= ? AND w.recipient_id != o.author_id""",
            Kind.STORY, now,
        ) { it.str("id") to it.str("author_id") }
        for ((id, authorId) in stories) {
            val viewers = strings("SELECT recipient_id FROM wraps WHERE object_id = ? AND recipient_id != ?", id, authorId)
            exec("DELETE FROM wraps WHERE object_id = ? AND recipient_id != ?", id, authorId)
            change(viewers, ChangeType.DELETED, id, fx, now)
        }
        val expired = strings(
            "SELECT id FROM objects WHERE deleted = 0 AND kind != ? AND expires_at IS NOT NULL AND expires_at <= ?", Kind.STORY, now,
        )
        expired.forEach { deleteTree(it, fx, now) }

        val vanish = query(
            "SELECT * FROM objects WHERE deleted = 0 AND kind = ? AND audience = ?", Kind.MESSAGE, Audience.VANISH,
        ) { it.toObjectRow() }
        for (message in vanish) {
            val others = query(
                "SELECT last_read_at FROM thread_members WHERE thread_id = ? AND user_id != ?", message.threadId, message.authorId,
            ) { it.getLong("last_read_at") }
            if (others.isNotEmpty() && others.all { it >= message.receivedAt }) deleteTree(message.id, fx, now)
        }
    }
}
