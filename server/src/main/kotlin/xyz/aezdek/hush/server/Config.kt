package xyz.aezdek.hush.server

import java.io.File

/** Everything the server reads from its environment. Defaults suit the Docker image on Resolute. */
data class ServerConfig(
    val dataDir: File,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    /** Built web client (index.html + assets). Missing in tests and local API-only runs. */
    val webDir: File? = null,
    /** Holds hush.apk and version.json, signed on this server by the image's entrypoint. */
    val apkDir: File? = null,
    /** Contact for VAPID (`sub` claim). */
    val vapidSubject: String = "mailto:admin@aezdek.xyz",
    val cfTurnKeyId: String? = null,
    val cfTurnApiToken: String? = null,
    /** Refuse uploads below this much free disk, so the shared box never fills up. */
    val minFreeBytes: Long = 10L * 1024 * 1024 * 1024,
    val maxUploadBytes: Long = 2L * 1024 * 1024 * 1024,
    val version: String = "dev",
    /** Local hour (0–23) for the nightly SQLite snapshot. */
    val snapshotHour: Int = 3,
    val snapshotsToKeep: Int = 7,
    val runJobs: Boolean = true,
) {
    val blobsDir get() = File(dataDir, "blobs")
    val uploadsDir get() = File(dataDir, "uploads")
    val snapshotsDir get() = File(dataDir, "snapshots")
    val dbFile get() = File(dataDir, "hush.db")
    val backupStatusFile get() = File(dataDir, "backup-status.json")
    val firstInviteFile get() = File(dataDir, "first-invite.txt")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val dataDir = File(env["HUSH_DATA_DIR"] ?: "/data")
            return ServerConfig(
                dataDir = dataDir,
                host = env["HUSH_HOST"] ?: "0.0.0.0",
                port = env["HUSH_PORT"]?.toInt() ?: 8080,
                webDir = (env["HUSH_WEB_DIR"] ?: "/app/web").let(::File).takeIf { it.isDirectory },
                apkDir = (env["HUSH_APK_DIR"]?.let(::File) ?: File(dataDir, "apk")).takeIf { it.isDirectory },
                vapidSubject = env["HUSH_VAPID_SUBJECT"] ?: "mailto:admin@aezdek.xyz",
                cfTurnKeyId = env["CF_TURN_KEY_ID"]?.takeIf { it.isNotBlank() },
                cfTurnApiToken = env["CF_TURN_API_TOKEN"]?.takeIf { it.isNotBlank() },
                minFreeBytes = (env["HUSH_MIN_FREE_GB"]?.toLong() ?: 10L) * 1024 * 1024 * 1024,
                version = env["HUSH_VERSION"] ?: "dev",
            )
        }
    }
}
