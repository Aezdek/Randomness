package xyz.aezdek.hush.server

/**
 * Versioned migrations, tracked in `PRAGMA user_version`. Append only: never edit a migration that has shipped.
 *
 * Everything user-generated is ciphertext. In plain text the server holds usernames, the follow graph, thread
 * membership, timestamps and sizes (see README, "What the server can see").
 */
object Schema {
    private val migrations: List<String> = listOf(
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            salt BLOB NOT NULL,
            ops_limit INTEGER NOT NULL,
            mem_limit INTEGER NOT NULL,
            auth_hash BLOB NOT NULL,
            sign_pk BLOB NOT NULL,
            box_pk BLOB NOT NULL,
            box_pk_sig BLOB NOT NULL,
            vault BLOB NOT NULL,
            key_version INTEGER NOT NULL DEFAULT 1,
            is_admin INTEGER NOT NULL DEFAULT 0,
            is_private INTEGER NOT NULL DEFAULT 1,
            show_activity INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            last_active_at INTEGER,
            deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE invites (
            code TEXT PRIMARY KEY,
            created_by TEXT,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            used_by TEXT,
            used_at INTEGER
        );
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY,
            token_hash BLOB NOT NULL UNIQUE,
            user_id TEXT NOT NULL,
            device_name TEXT NOT NULL,
            platform TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL
        );
        CREATE INDEX sessions_user ON sessions(user_id);
        CREATE TABLE follows (
            follower_id TEXT NOT NULL,
            followee_id TEXT NOT NULL,
            state TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY (follower_id, followee_id)
        );
        CREATE INDEX follows_followee ON follows(followee_id, state);
        CREATE TABLE blocks (
            blocker_id TEXT NOT NULL,
            blocked_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY (blocker_id, blocked_id)
        );
        CREATE TABLE objects (
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            author_id TEXT NOT NULL,
            parent_id TEXT,
            thread_id TEXT,
            audience TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER,
            version INTEGER NOT NULL,
            payload BLOB NOT NULL,
            commitment BLOB NOT NULL,
            signature BLOB NOT NULL,
            archived INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0,
            received_at INTEGER NOT NULL
        );
        CREATE INDEX objects_parent ON objects(parent_id);
        CREATE INDEX objects_thread ON objects(thread_id, received_at);
        CREATE INDEX objects_author ON objects(author_id, kind);
        CREATE INDEX objects_expiry ON objects(expires_at) WHERE expires_at IS NOT NULL AND deleted = 0;
        CREATE TABLE object_blobs (
            object_id TEXT NOT NULL,
            blob_id TEXT NOT NULL,
            PRIMARY KEY (object_id, blob_id)
        );
        CREATE INDEX object_blobs_blob ON object_blobs(blob_id);
        CREATE TABLE object_refs (
            object_id TEXT NOT NULL,
            ref_id TEXT NOT NULL,
            PRIMARY KEY (object_id, ref_id)
        );
        CREATE INDEX object_refs_ref ON object_refs(ref_id);
        CREATE TABLE wraps (
            object_id TEXT NOT NULL,
            recipient_id TEXT NOT NULL,
            sealed BLOB NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY (object_id, recipient_id)
        );
        CREATE INDEX wraps_recipient ON wraps(recipient_id);
        CREATE TABLE views (
            object_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            at INTEGER NOT NULL,
            PRIMARY KEY (object_id, user_id)
        );
        CREATE TABLE blobs (
            id TEXT PRIMARY KEY,
            size INTEGER NOT NULL,
            uploader_id TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE uploads (
            id TEXT PRIMARY KEY,
            uploader_id TEXT NOT NULL,
            size INTEGER NOT NULL,
            received INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE threads (
            id TEXT PRIMARY KEY,
            is_group INTEGER NOT NULL,
            created_by TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            pair_key TEXT UNIQUE
        );
        CREATE TABLE thread_members (
            thread_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            role TEXT NOT NULL,
            joined_at INTEGER NOT NULL,
            last_read_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (thread_id, user_id)
        );
        CREATE INDEX thread_members_user ON thread_members(user_id);
        CREATE TABLE changes (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            type TEXT NOT NULL,
            ref TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX changes_user ON changes(user_id, seq);
        CREATE TABLE wrap_tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            author_id TEXT NOT NULL,
            recipient_id TEXT NOT NULL,
            reason TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            done_at INTEGER
        );
        CREATE INDEX wrap_tasks_author ON wrap_tasks(author_id, done_at);
        CREATE TABLE push_subscriptions (
            endpoint TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            p256dh BLOB NOT NULL,
            auth BLOB NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE kv (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        """.trimIndent(),
        // 2: per-user push filters (see PushPrefs).
        """
        CREATE TABLE push_prefs (
            user_id TEXT PRIMARY KEY,
            muted_types TEXT NOT NULL,
            muted_threads TEXT NOT NULL
        );
        """.trimIndent(),
        // 3: when the author's client last fetched a wrap task, so completing it can't drop objects made after that.
        """
        ALTER TABLE wrap_tasks ADD COLUMN fetched_at INTEGER;
        """.trimIndent(),
        // 4: an admin can block an account (it can read but not post or message) or disable it (it can't sign in).
        """
        ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'active';
        """.trimIndent(),
    )

    fun migrate(db: Db) {
        val current = db.tx { long("PRAGMA user_version") ?: 0 }.toInt()
        for (version in current until migrations.size) {
            db.tx {
                migrations[version].split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }
                exec("PRAGMA user_version = ${version + 1}")
            }
        }
    }
}
