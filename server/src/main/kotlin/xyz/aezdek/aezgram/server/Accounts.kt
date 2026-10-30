package xyz.aezdek.aezgram.server

import org.slf4j.LoggerFactory
import xyz.aezdek.aezgram.protocol.AuthResponse
import xyz.aezdek.aezgram.protocol.ChangePasswordRequest
import xyz.aezdek.aezgram.protocol.DeviceInfo
import xyz.aezdek.aezgram.protocol.FollowState
import xyz.aezdek.aezgram.protocol.InviteDto
import xyz.aezdek.aezgram.protocol.LoginRequest
import xyz.aezdek.aezgram.protocol.MAX_MEMBERS
import xyz.aezdek.aezgram.protocol.Me
import xyz.aezdek.aezgram.protocol.PreloginResponse
import xyz.aezdek.aezgram.protocol.ResetKeysRequest
import xyz.aezdek.aezgram.protocol.SessionDto
import xyz.aezdek.aezgram.protocol.SignupRequest
import xyz.aezdek.aezgram.protocol.UpdateMeRequest
import xyz.aezdek.aezgram.protocol.UserDto
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Session(val id: String, val userId: String)

/** Default Argon2id cost handed to new accounts; matches KdfParams.DEFAULT in shared:crypto. */
const val DEFAULT_OPS_LIMIT = 3L
const val DEFAULT_MEM_LIMIT = 64 * 1024 * 1024
const val INVITE_DAYS = 7L
private const val DAY = 24L * 60 * 60 * 1000

/**
 * Accounts, sessions and invites. The server never sees a password: it stores SHA-256 of the client's Argon2id-derived
 * authKey (a 256-bit key, so a fast hash is enough to stop the database being usable as a login).
 */
class Accounts(private val db: Db, private val clock: Clock, private val presence: Presence) {
    private val log = LoggerFactory.getLogger(Accounts::class.java)
    private val serverSecret: ByteArray by lazy { db.tx { secret("server_secret") } }

    fun Tx.secret(name: String): ByteArray {
        one("SELECT value FROM kv WHERE key = ?", name) { it.str("value") }?.let { return B64u.dec(it, name) }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        exec("INSERT INTO kv (key, value) VALUES (?, ?)", name, B64u.enc(bytes))
        return bytes
    }

    /** On a fresh install, create the first invite (its user becomes the admin) and drop it where install.sh looks. */
    fun bootstrap(config: ServerConfig) {
        val code = db.tx {
            val users = long("SELECT COUNT(*) FROM users WHERE deleted = 0")!!
            val open = long("SELECT COUNT(*) FROM invites WHERE used_by IS NULL AND expires_at > ?", clock.now())!!
            if (users > 0 || open > 0) return@tx null
            createInvite(null, days = 30).code
        } ?: return
        config.firstInviteFile.writeText("$code\n")
        log.info("First invite code (the account that uses it becomes the admin): {}", code)
    }

    // ------------------------------------------------------------------------------------------------------- login

    fun prelogin(username: String): PreloginResponse = db.tx {
        one("SELECT salt, ops_limit, mem_limit FROM users WHERE username = ? AND deleted = 0", username.lowercase()) {
            PreloginResponse(B64u.enc(it.bytes("salt")), it.getLong("ops_limit"), it.getInt("mem_limit"))
        }
    } ?: PreloginResponse(B64u.enc(fakeSalt(username)), DEFAULT_OPS_LIMIT, DEFAULT_MEM_LIMIT)

    /** Unknown usernames get a stable fake salt, so prelogin does not reveal who has an account. */
    private fun fakeSalt(username: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(serverSecret, "HmacSHA256"))
        return mac.doFinal("salt:${username.lowercase()}".encodeToByteArray()).copyOf(16)
    }

    fun signup(req: SignupRequest, fx: Effects): AuthResponse = db.tx {
        val username = req.username.trim().lowercase()
        if (!USERNAME.matches(username)) badRequest("Usernames are 2–30 characters: a–z, 0–9, dots and underscores", "bad_username")
        val salt = B64u.dec(req.salt, "salt", size = 16)
        val authKey = B64u.dec(req.authKey, "authKey", size = 32)
        val signPk = B64u.dec(req.signPk, "signPk", size = 32)
        val boxPk = B64u.dec(req.boxPk, "boxPk", size = 32)
        val boxPkSig = B64u.dec(req.boxPkSig, "boxPkSig", size = 64)
        val vault = B64u.dec(req.vault, "vault", max = 1024)
        if (req.opsLimit < 1 || req.memLimit < 8192) badRequest("KDF parameters too weak")
        checkDevice(req.device)

        val now = clock.now()
        val invite = one("SELECT code, expires_at, used_by FROM invites WHERE code = ?", req.inviteCode.trim().uppercase()) {
            Triple(it.str("code"), it.getLong("expires_at"), it.strOrNull("used_by"))
        }
        if (invite == null || invite.third != null || invite.second < now) forbidden("That invite code is not valid any more")
        val members = long("SELECT COUNT(*) FROM users WHERE deleted = 0")!!
        if (members >= MAX_MEMBERS) forbidden("AezGram is full ($MAX_MEMBERS members)")
        if (exists("SELECT 1 FROM users WHERE username = ?", username)) conflict("That username is taken", "username_taken")

        val id = Ids.random(12)
        exec(
            """INSERT INTO users (id, username, salt, ops_limit, mem_limit, auth_hash, sign_pk, box_pk, box_pk_sig, vault,
               is_admin, created_at, last_active_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, username, salt, req.opsLimit, req.memLimit, sha256(authKey), signPk, boxPk, boxPkSig, vault,
            members == 0L, now, now,
        )
        exec("UPDATE invites SET used_by = ?, used_at = ? WHERE code = ?", id, now, invite.first)

        // Everyone learns about the newcomer; the newcomer learns about everyone. Existing members' clients will
        // seal their public content to the newcomer (wrap tasks).
        val others = strings("SELECT id FROM users WHERE deleted = 0 AND id != ?", id)
        change(others, ChangeType.USER, id, fx, now)
        for (other in others) {
            change(id, ChangeType.USER, other, fx, now)
            addWrapTask(other, id, "member", now)
        }
        change(id, ChangeType.USER, id, fx, now)
        fx.syncUsers += others
        val token = newSession(id, req.device, now)
        AuthResponse(token, me(id), req.vault)
    }

    fun login(req: LoginRequest): AuthResponse = db.tx {
        checkDevice(req.device)
        val authKey = B64u.dec(req.authKey, "authKey", size = 32)
        val row = one("SELECT id, auth_hash, vault FROM users WHERE username = ? AND deleted = 0", req.username.trim().lowercase()) {
            Triple(it.str("id"), it.bytes("auth_hash"), it.bytes("vault"))
        }
        if (row == null || !MessageDigest.isEqual(row.second, sha256(authKey))) unauthorized("Wrong username or password")
        val token = newSession(row.first, req.device, clock.now())
        AuthResponse(token, me(row.first), B64u.enc(row.third))
    }

    private fun checkDevice(device: DeviceInfo) {
        if (device.name.length > 80 || device.platform !in setOf("android", "web")) badRequest("Unknown device")
    }

    private fun Tx.newSession(userId: String, device: DeviceInfo, now: Long): String {
        val token = Ids.token()
        exec(
            "INSERT INTO sessions (id, token_hash, user_id, device_name, platform, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            Ids.random(9), sha256(token.encodeToByteArray()), userId, device.name, device.platform, now, now,
        )
        return token
    }

    private val sessionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Session, Long>>()

    /** Resolves a bearer token. Cached for a minute; revocation clears the cache. */
    fun authenticate(token: String?): Session? {
        if (token.isNullOrBlank() || token.length > 100) return null
        val now = clock.now()
        sessionCache[token]?.let { (session, at) -> if (now - at < 60_000) return session }
        val session = db.tx {
            val hash = sha256(token.encodeToByteArray())
            one(
                """SELECT s.id, s.user_id FROM sessions s JOIN users u ON u.id = s.user_id
                   WHERE s.token_hash = ? AND u.deleted = 0""",
                hash,
            ) { Session(it.str("id"), it.str("user_id")) }?.also {
                exec("UPDATE sessions SET last_seen_at = ? WHERE id = ?", now, it.id)
                exec("UPDATE users SET last_active_at = ? WHERE id = ?", now, it.userId)
            }
        } ?: return null
        sessionCache[token] = session to now
        return session
    }

    fun logout(session: Session) = revoke(session.userId, session.id)

    fun sessions(session: Session): List<SessionDto> = db.tx {
        query("SELECT * FROM sessions WHERE user_id = ? ORDER BY last_seen_at DESC", session.userId) {
            SessionDto(
                it.str("id"), it.str("device_name"), it.str("platform"), it.getLong("created_at"),
                it.getLong("last_seen_at"), it.str("id") == session.id,
            )
        }
    }

    fun revoke(userId: String, sessionId: String) {
        db.tx {
            if (exec("DELETE FROM sessions WHERE id = ? AND user_id = ?", sessionId, userId) == 0) notFound("No such session")
            exec("DELETE FROM push_subscriptions WHERE session_id = ?", sessionId)
        }
        sessionCache.entries.removeIf { it.value.first.id == sessionId }
        presence.disconnectSession(sessionId)
    }

    private fun Tx.checkAuthKey(userId: String, authKey: String) {
        val hash = one("SELECT auth_hash FROM users WHERE id = ?", userId) { it.bytes("auth_hash") } ?: unauthorized()
        if (!MessageDigest.isEqual(hash, sha256(B64u.dec(authKey, "authKey", size = 32)))) forbidden("Wrong password")
    }

    /** Re-seals the same private keys under a new password, and signs out every other device. */
    fun changePassword(session: Session, req: ChangePasswordRequest) {
        val revoked = db.tx {
            checkAuthKey(session.userId, req.currentAuthKey)
            if (req.opsLimit < 1 || req.memLimit < 8192) badRequest("KDF parameters too weak")
            exec(
                "UPDATE users SET salt = ?, ops_limit = ?, mem_limit = ?, auth_hash = ?, vault = ? WHERE id = ?",
                B64u.dec(req.salt, "salt", size = 16), req.opsLimit, req.memLimit,
                sha256(B64u.dec(req.authKey, "authKey", size = 32)), B64u.dec(req.vault, "vault", max = 1024), session.userId,
            )
            val others = strings("SELECT id FROM sessions WHERE user_id = ? AND id != ?", session.userId, session.id)
            exec("DELETE FROM sessions WHERE user_id = ? AND id != ?", session.userId, session.id)
            exec("DELETE FROM push_subscriptions WHERE user_id = ? AND session_id != ?", session.userId, session.id)
            others
        }
        sessionCache.entries.removeIf { it.value.first.userId == session.userId && it.value.first.id != session.id }
        revoked.forEach(presence::disconnectSession)
    }

    fun updateMe(session: Session, req: UpdateMeRequest, social: Social, fx: Effects): Me = db.tx {
        req.showActivity?.let { exec("UPDATE users SET show_activity = ? WHERE id = ?", it, session.userId) }
        req.isPrivate?.let { social.setPrivate(this, session.userId, it, fx) }
        val everyone = strings("SELECT id FROM users WHERE deleted = 0")
        change(everyone, ChangeType.USER, session.userId, fx, clock.now())
        me(session.userId)
    }

    // ------------------------------------------------------------------------------------------------------- users

    fun Tx.me(userId: String): Me = one("SELECT * FROM users WHERE id = ?", userId) {
        Me(
            id = it.str("id"), username = it.str("username"), isAdmin = it.bool("is_admin"), isPrivate = it.bool("is_private"),
            showActivity = it.bool("show_activity"), createdAt = it.getLong("created_at"), keyVersion = it.getInt("key_version"),
        )
    } ?: notFound("No such user")

    fun me(session: Session): Me = db.tx { me(session.userId) }

    fun Tx.user(viewerId: String, userId: String): UserDto? {
        val viewerShowsActivity = one("SELECT show_activity FROM users WHERE id = ?", viewerId) { it.bool("show_activity") } ?: false
        return one("SELECT * FROM users WHERE id = ?", userId) { row -> row.toUserDto(this, viewerId, viewerShowsActivity) }
    }

    fun Tx.users(viewerId: String): List<UserDto> {
        val viewerShowsActivity = one("SELECT show_activity FROM users WHERE id = ?", viewerId) { it.bool("show_activity") } ?: false
        return strings("SELECT id FROM users WHERE deleted = 0 ORDER BY created_at")
            .mapNotNull { id -> one("SELECT * FROM users WHERE id = ?", id) { it.toUserDto(this, viewerId, viewerShowsActivity) } }
    }

    private fun java.sql.ResultSet.toUserDto(tx: Tx, viewerId: String, viewerShowsActivity: Boolean): UserDto {
        val id = str("id")
        val deleted = bool("deleted")
        val showsActivity = bool("show_activity")
        val activityVisible = viewerShowsActivity && showsActivity && !deleted && id != viewerId
        return UserDto(
            id = id,
            username = str("username"),
            signPk = B64u.enc(bytes("sign_pk")),
            boxPk = B64u.enc(bytes("box_pk")),
            boxPkSig = B64u.enc(bytes("box_pk_sig")),
            keyVersion = getInt("key_version"),
            isPrivate = bool("is_private"),
            createdAt = getLong("created_at"),
            followState = tx.followState(viewerId, id),
            followsMe = tx.followState(id, viewerId),
            blockedByMe = tx.exists("SELECT 1 FROM blocks WHERE blocker_id = ? AND blocked_id = ?", viewerId, id),
            lastActiveAt = if (activityVisible) longOrNull("last_active_at") else null,
            online = activityVisible && presence.isOnline(id),
            deleted = deleted,
        )
    }

    fun userDto(viewerId: String, userId: String): UserDto = db.tx { user(viewerId, userId) } ?: notFound("No such user")

    fun allUsers(viewerId: String): List<UserDto> = db.tx { users(viewerId) }

    // ----------------------------------------------------------------------------------------------------- invites

    fun Tx.createInvite(createdBy: String?, days: Long = INVITE_DAYS): InviteDto {
        val now = clock.now()
        val invite = InviteDto(Ids.invite(), now, now + days * DAY)
        exec("INSERT INTO invites (code, created_by, created_at, expires_at) VALUES (?, ?, ?, ?)", invite.code, createdBy, now, invite.expiresAt)
        return invite
    }

    fun createInvite(session: Session): InviteDto = db.tx {
        requireAdmin(session.userId)
        createInvite(session.userId)
    }

    fun invites(session: Session): List<InviteDto> = db.tx {
        requireAdmin(session.userId)
        query("SELECT * FROM invites ORDER BY created_at DESC LIMIT 50") {
            InviteDto(it.str("code"), it.getLong("created_at"), it.getLong("expires_at"), it.strOrNull("used_by"))
        }
    }

    fun Tx.requireAdmin(userId: String) {
        if (one("SELECT is_admin FROM users WHERE id = ?", userId) { it.bool("is_admin") } != true) forbidden("Admins only")
    }

    fun isAdmin(userId: String): Boolean = db.tx { one("SELECT is_admin FROM users WHERE id = ?", userId) { it.bool("is_admin") } == true }

    // -------------------------------------------------------------------------------------------------- deletion

    /** Deletes the account and everything it authored. Frees the username. */
    fun deleteAccount(session: Session, authKey: String, objects: Objects, fx: Effects) {
        val sessions = db.tx {
            checkAuthKey(session.userId, authKey)
            val now = clock.now()
            val mine = strings("SELECT id FROM objects WHERE author_id = ? AND deleted = 0 AND parent_id IS NULL", session.userId)
            for (id in mine) with(objects) { deleteTree(id, fx, now) }
            exec("DELETE FROM wraps WHERE recipient_id = ?", session.userId)
            exec("DELETE FROM follows WHERE follower_id = ? OR followee_id = ?", session.userId, session.userId)
            exec("DELETE FROM blocks WHERE blocker_id = ? OR blocked_id = ?", session.userId, session.userId)
            val threads = strings("SELECT thread_id FROM thread_members WHERE user_id = ?", session.userId)
            exec("DELETE FROM thread_members WHERE user_id = ?", session.userId)
            for (thread in threads) {
                change(strings("SELECT user_id FROM thread_members WHERE thread_id = ?", thread), ChangeType.THREAD, thread, fx, now)
            }
            exec("DELETE FROM wrap_tasks WHERE author_id = ? OR recipient_id = ?", session.userId, session.userId)
            exec("DELETE FROM push_subscriptions WHERE user_id = ?", session.userId)
            val sessions = strings("SELECT id FROM sessions WHERE user_id = ?", session.userId)
            exec("DELETE FROM sessions WHERE user_id = ?", session.userId)
            exec(
                "UPDATE users SET deleted = 1, username = ?, vault = ?, auth_hash = ? WHERE id = ?",
                "deleted-${session.userId}", ByteArray(0), ByteArray(32), session.userId,
            )
            change(strings("SELECT id FROM users WHERE deleted = 0"), ChangeType.USER, session.userId, fx, now)
            sessions
        }
        sessionCache.entries.removeIf { it.value.first.userId == session.userId }
        sessions.forEach(presence::disconnectSession)
    }

    /**
     * Replaces the account's keys after a suspected compromise. The client has already re-sealed every content key it
     * could open to the new box key ([rewraps]); old wraps are replaced. Contacts see a key-change warning.
     */
    fun resetKeys(session: Session, req: ResetKeysRequest, fx: Effects) = db.tx {
        checkAuthKey(session.userId, req.currentAuthKey)
        val now = clock.now()
        exec(
            """UPDATE users SET salt = ?, ops_limit = ?, mem_limit = ?, auth_hash = ?, vault = ?, sign_pk = ?, box_pk = ?,
               box_pk_sig = ?, key_version = key_version + 1 WHERE id = ?""",
            B64u.dec(req.salt, "salt", size = 16), req.opsLimit, req.memLimit, sha256(B64u.dec(req.authKey, "authKey", size = 32)),
            B64u.dec(req.vault, "vault", max = 1024), B64u.dec(req.signPk, "signPk", size = 32), B64u.dec(req.boxPk, "boxPk", size = 32),
            B64u.dec(req.boxPkSig, "boxPkSig", size = 64), session.userId,
        )
        // Wraps sealed to the old key are useless now; keep only the re-sealed ones.
        exec("DELETE FROM wraps WHERE recipient_id = ?", session.userId)
        for (wrap in req.rewraps) {
            if (!exists("SELECT 1 FROM objects WHERE id = ? AND deleted = 0", wrap.objectId)) continue
            exec(
                "INSERT OR REPLACE INTO wraps (object_id, recipient_id, sealed, created_at) VALUES (?, ?, ?, ?)",
                wrap.objectId, session.userId, B64u.dec(wrap.sealed, "sealed", max = 256), now,
            )
        }
        change(strings("SELECT id FROM users WHERE deleted = 0"), ChangeType.USER, session.userId, fx, now)
        // Everyone whose content this account could read must re-seal it to the new key.
        for (author in strings("SELECT id FROM users WHERE deleted = 0 AND id != ?", session.userId)) {
            addWrapTask(author, session.userId, "rekey", now)
        }
    }
}

fun Tx.followState(followerId: String, followeeId: String): String =
    one("SELECT state FROM follows WHERE follower_id = ? AND followee_id = ?", followerId, followeeId) { it.str("state") }
        ?: FollowState.NONE

fun Tx.addWrapTask(authorId: String, recipientId: String, reason: String, now: Long) {
    if (authorId == recipientId) return
    if (exists("SELECT 1 FROM wrap_tasks WHERE author_id = ? AND recipient_id = ? AND done_at IS NULL", authorId, recipientId)) return
    exec("INSERT INTO wrap_tasks (author_id, recipient_id, reason, created_at) VALUES (?, ?, ?, ?)", authorId, recipientId, reason, now)
}
