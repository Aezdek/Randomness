package xyz.aezdek.hush.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Background work: expiry every minute, media GC hourly, and the nightly SQLite snapshot backups pick up. */
class Jobs(
    private val config: ServerConfig,
    private val db: Db,
    private val objects: Objects,
    private val blobs: Blobs,
    private val realtime: Realtime,
) {
    private val log = LoggerFactory.getLogger(Jobs::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var minutes = 0L
            while (isActive) {
                runCatching { expireNow() }.onFailure { log.error("expiry failed", it) }
                if (minutes % 60 == 30L) runCatching { blobs.gc() }.onFailure { log.error("media GC failed", it) }
                runCatching { snapshotIfDue() }.onFailure { log.error("snapshot failed", it) }
                minutes++
                delay(60_000)
            }
        }
    }

    fun expireNow() {
        val fx = Effects()
        objects.expire(fx)
        realtime.apply(fx)
    }

    /**
     * `VACUUM INTO` writes a consistent copy without stopping the server. backup.sh ships these (and the blobs) to the
     * encrypted Google Drive remote; seven are kept locally.
     */
    fun snapshotIfDue(now: LocalDateTime = LocalDateTime.now()): File? {
        if (now.hour != config.snapshotHour) return null
        val target = File(config.snapshotsDir, "hush-${LocalDate.from(now)}.db")
        if (target.exists()) return null
        return snapshot(target)
    }

    fun snapshot(target: File): File {
        config.snapshotsDir.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.delete()
        db.raw("VACUUM INTO '${temp.absolutePath.replace("'", "''")}'")
        temp.renameTo(target)
        config.snapshotsDir.listFiles { file -> file.name.startsWith("hush-") && file.name.endsWith(".db") }
            ?.sortedByDescending { it.name }
            ?.drop(config.snapshotsToKeep)
            ?.forEach { it.delete() }
        log.info("Wrote snapshot {}", target.name)
        return target
    }
}
