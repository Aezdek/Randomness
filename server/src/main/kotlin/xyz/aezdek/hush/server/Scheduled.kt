package xyz.aezdek.hush.server

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.ObjectUpload
import xyz.aezdek.hush.protocol.ScheduleRequest
import xyz.aezdek.hush.protocol.ScheduledDto

/**
 * Messages to deliver later. The author's app seals and signs the message as usual, dated for when it should arrive,
 * and hands over the finished upload; the server keeps it unread (it is ciphertext, like everything else) and, when
 * the time comes, posts it exactly as if the author had just sent it. Every rule a message meets on the way in is
 * checked then, not now: someone who has left the chat by then, or been paused or removed, delivers nothing.
 */
class Scheduled(private val db: Db, private val clock: Clock, private val objects: Objects) {
    private val log = LoggerFactory.getLogger(Scheduled::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun add(userId: String, req: ScheduleRequest): ScheduledDto {
        val now = clock.now()
        val up = req.upload
        if (up.kind != Kind.MESSAGE) badRequest("Only messages can be sent later")
        val threadId = up.threadId ?: badRequest("Only messages can be sent later")
        if (req.releaseAt < now + MIN_AHEAD) badRequest("Pick a time at least a minute from now")
        if (req.releaseAt > now + MAX_AHEAD) badRequest("A message can wait up to 60 days")
        // Dated for its delivery, so it arrives as new and passes the clock check then.
        if (kotlin.math.abs(up.createdAt - req.releaseAt) > MIN_AHEAD) badRequest("The message must be dated for its delivery")
        // Words only: nothing keeps a photo's file on the server until a message that isn't there yet points to it.
        if (up.blobIds.isNotEmpty()) badRequest("Only text can be sent later")
        if (up.expiresAt != null && up.expiresAt!! <= req.releaseAt) badRequest("The message would be gone before it arrived")
        val stored = json.encodeToString(ObjectUpload.serializer(), up)
        if (stored.length > MAX_UPLOAD) badRequest("That message is too long to send later")
        return db.tx {
            long("SELECT 1 FROM thread_members WHERE thread_id = ? AND user_id = ?", threadId, userId) ?: notFound("Chat not found")
            if ((long("SELECT COUNT(*) FROM scheduled WHERE user_id = ? AND failed IS NULL", userId) ?: 0) >= MAX_PENDING) {
                throw ApiException(io.ktor.http.HttpStatusCode.TooManyRequests, "too_many_scheduled", "You have $MAX_PENDING messages waiting already")
            }
            if (long("SELECT 1 FROM scheduled WHERE id = ?", up.id) != null || long("SELECT 1 FROM objects WHERE id = ?", up.id) != null) {
                conflict("Object ${up.id} exists", "exists")
            }
            exec("INSERT INTO scheduled (id, user_id, thread_id, release_at, upload, created_at) VALUES (?, ?, ?, ?, ?, ?)", up.id, userId, threadId, req.releaseAt, stored, now)
            ScheduledDto(up.id, threadId, req.releaseAt)
        }
    }

    fun list(userId: String): List<ScheduledDto> = db.tx {
        query("SELECT id, thread_id, release_at, failed FROM scheduled WHERE user_id = ? ORDER BY release_at", userId) {
            ScheduledDto(it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
        }
    }

    fun cancel(userId: String, id: String) {
        db.tx { if (exec("DELETE FROM scheduled WHERE id = ? AND user_id = ?", id, userId) == 0) notFound("No such message") }
    }

    /** Posts every message whose time has come. One that can't be posted stays a week, marked with why, then goes. */
    fun releaseDue(fx: Effects): Int {
        val now = clock.now()
        val due = db.tx {
            exec("DELETE FROM scheduled WHERE failed IS NOT NULL AND release_at < ?", now - KEEP_FAILED)
            query("SELECT id, user_id, upload FROM scheduled WHERE failed IS NULL AND release_at <= ? ORDER BY release_at LIMIT 200", now) {
                Triple(it.getString(1), it.getString(2), it.getString(3))
            }
        }
        var sent = 0
        for ((id, userId, stored) in due) {
            val problem = try {
                db.tx {
                    // As at the door: a removed or disabled account sends nothing, nor one an admin has paused.
                    val status = one("SELECT status FROM users WHERE id = ? AND deleted = 0", userId) { it.getString(1) } ?: forbidden("This account is gone")
                    if (status != "active") forbidden("Posting and messaging are paused on this account")
                    objects.createIn(this, userId, json.decodeFromString(ObjectUpload.serializer(), stored), fx)
                    exec("DELETE FROM scheduled WHERE id = ?", id)
                }
                sent++
                null
            } catch (e: ApiException) {
                e.message ?: "It couldn't be delivered"
            } catch (e: Exception) {
                log.warn("A scheduled message couldn't be read back: {}", e.javaClass.simpleName)
                "It couldn't be delivered"
            }
            if (problem != null) db.tx { exec("UPDATE scheduled SET failed = ? WHERE id = ?", problem.take(200), id) }
        }
        return sent
    }

    private companion object {
        const val MIN_AHEAD = 60_000L
        const val MAX_AHEAD = 60L * 24 * 60 * 60 * 1000
        const val KEEP_FAILED = 7L * 24 * 60 * 60 * 1000
        const val MAX_PENDING = 50
        const val MAX_UPLOAD = 256 * 1024
    }
}
