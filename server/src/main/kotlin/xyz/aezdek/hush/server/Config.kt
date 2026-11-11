package xyz.aezdek.hush.server

import java.io.File

/** Everything the server reads from its environment. Defaults suit the Docker image. */
data class ServerConfig(
    val dataDir: File,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    /** Built web client (index.html + assets). Missing in tests and local API-only runs. */
    val webDir: File? = null,
    /** Holds hush.apk and version.json, signed on this server by the image's entrypoint. */
    val apkDir: File? = null,
    /** Contact for VAPID (`sub` claim). */
    val vapidSubject: String = "mailto:admin@example.com",
    /** Where Hush is installed on the machine that runs the container, for the commands the admin panel shows. */
    val hostRoot: String = "/opt/hush",
    /** The header that names the visitor's address, set by whatever sits in front (see clientIp in Routes.kt); null for none. */
    val clientIpHeader: String? = "CF-Connecting-IP",
    val cfTurnKeyId: String? = null,
    val cfTurnApiToken: String? = null,
    /** Refuse uploads below this much free disk, so the shared box never fills up. */
    val minFreeBytes: Long = 10L * 1024 * 1024 * 1024,
    val maxUploadBytes: Long = 2L * 1024 * 1024 * 1024,
    val version: String = "dev",
    /** The commit this server runs, from update.sh's HUSH_COMMIT. */
    val commit: String? = null,
    /** Where the newest release describes itself, so admins can see when an update is waiting. */
    val releaseUrl: String? = null,
    /** Local hour (0–23) for the nightly SQLite snapshot. */
    val snapshotHour: Int = 3,
    val snapshotsToKeep: Int = 7,
    val runJobs: Boolean = true,
    /** The database key, from the [Vault] (unsealed with this machine's key or the server password). */
    val dbKey: ByteArray? = null,
    /** This machine's key (compose.yml mounts it read-only); null in tests that don't use one. */
    val machineKeyFile: File? = null,
) {
    val blobsDir get() = File(dataDir, "blobs")
    val uploadsDir get() = File(dataDir, "uploads")
    val snapshotsDir get() = File(dataDir, "snapshots")
    val dbFile get() = File(dataDir, "hush.db")
    val backupStatusFile get() = File(dataDir, "backup-status.json")
    val firstInviteFile get() = File(dataDir, "first-invite.txt")
    val keyringFile get() = File(dataDir, "keyring.json")
    val setupCodeFile get() = File(dataDir, "setup-code.txt")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val dataDir = File(env["HUSH_DATA_DIR"] ?: "/data")
            return ServerConfig(
                dataDir = dataDir,
                host = env["HUSH_HOST"] ?: "0.0.0.0",
                port = env["HUSH_PORT"]?.toInt() ?: 8080,
                webDir = (env["HUSH_WEB_DIR"] ?: "/app/web").let(::File).takeIf { it.isDirectory },
                apkDir = (env["HUSH_APK_DIR"]?.let(::File) ?: File(dataDir, "apk")).takeIf { it.isDirectory },
                vapidSubject = env["HUSH_VAPID_SUBJECT"]?.takeIf { it.isNotBlank() } ?: "mailto:admin@example.com",
                hostRoot = env["HUSH_HOST_ROOT"]?.takeIf { it.startsWith("/") && it.length <= 200 && it.none { c -> c.isWhitespace() || c in "\"'`$;&|<>" } } ?: "/opt/hush",
                // Unset means Cloudflare (how Hush was first run); "none" means nothing is in front.
                clientIpHeader = (env["HUSH_CLIENT_IP_HEADER"]?.trim() ?: "CF-Connecting-IP").takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) },
                cfTurnKeyId = env["CF_TURN_KEY_ID"]?.takeIf { it.isNotBlank() },
                cfTurnApiToken = env["CF_TURN_API_TOKEN"]?.takeIf { it.isNotBlank() },
                minFreeBytes = (env["HUSH_MIN_FREE_GB"]?.toLong() ?: 10L) * 1024 * 1024 * 1024,
                version = env["HUSH_VERSION"] ?: "dev",
                commit = env["HUSH_COMMIT"]?.takeIf { it.isNotBlank() },
                releaseUrl = env["HUSH_REPO"]?.takeIf { it.isNotBlank() }
                    ?.let { "https://github.com/$it/releases/download/hush-latest/hush-release.env" },
                machineKeyFile = File(env["HUSH_MACHINE_KEY_FILE"] ?: "/run/secrets/hush/machine.key"),
            )
        }
    }
}
