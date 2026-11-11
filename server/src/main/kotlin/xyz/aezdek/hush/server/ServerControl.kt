package xyz.aezdek.hush.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import xyz.aezdek.hush.protocol.ControlStatusDto
import xyz.aezdek.hush.protocol.MAX_MEMBERS
import xyz.aezdek.hush.protocol.ServerAction
import xyz.aezdek.hush.protocol.ServerLimits
import xyz.aezdek.hush.protocol.ServerSettingsDto
import xyz.aezdek.hush.protocol.Wire
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

private const val GB = 1024L * 1024 * 1024

/**
 * The server's own settings, in data/config.json. The admin panel changes them (with the server password) through a
 * form, never by editing JSON; `control.sh web-admin on` on the server turns the web admin back on. A setting left
 * out of the file keeps its default (the members limit Hush ships with, the disk floor from HUSH_MIN_FREE_GB).
 */
@Serializable
data class SettingsFile(
    val maxMembers: Int? = null,
    val inviteDays: Int? = null,
    val minFreeGb: Int? = null,
    val webAdmin: Boolean = true,
)

class Settings(private val config: ServerConfig) {
    private val file = File(config.dataDir, "config.json")

    @Volatile
    private var cached: Pair<Long, SettingsFile>? = null

    /** Read again whenever the file changes, so an edit on the server takes effect without a restart. */
    fun current(): SettingsFile {
        val stamp = if (file.isFile) file.lastModified() else 0L
        cached?.let { (at, value) -> if (at == stamp) return value }
        val value = if (stamp == 0L) SettingsFile() else runCatching { Wire.json.decodeFromString(SettingsFile.serializer(), file.readText()) }.getOrElse { SettingsFile() }
        cached = stamp to value
        return value
    }

    val maxMembers get() = current().maxMembers ?: MAX_MEMBERS
    val inviteDays get() = current().inviteDays ?: INVITE_DAYS.toInt()
    val minFreeBytes get() = current().minFreeGb?.let { it * GB } ?: config.minFreeBytes
    val webAdmin get() = current().webAdmin

    fun dto() = ServerSettingsDto(maxMembers, inviteDays, (minFreeBytes / GB).toInt().coerceAtLeast(if (minFreeBytes > 0) 1 else 0), webAdmin)

    /** Checks and saves new settings; [members] is how many there are now (the limit can't go below it). */
    fun update(next: ServerSettingsDto, members: Int): ServerSettingsDto {
        with(ServerLimits) {
            if (next.maxMembers !in MIN_MEMBERS..MAX_MEMBERS_LIMIT) badRequest("Members: between $MIN_MEMBERS and $MAX_MEMBERS_LIMIT")
            if (next.maxMembers < members) badRequest("There are already $members members; remove some first or allow at least $members")
            if (next.inviteDays !in MIN_INVITE_DAYS..MAX_INVITE_DAYS) badRequest("Invites: between $MIN_INVITE_DAYS and $MAX_INVITE_DAYS days")
            if (next.minFreeGb !in MIN_FREE_GB..MAX_FREE_GB) badRequest("Free disk: between $MIN_FREE_GB and $MAX_FREE_GB GB")
        }
        write(current().copy(maxMembers = next.maxMembers, inviteDays = next.inviteDays, minFreeGb = next.minFreeGb))
        return dto()
    }

    fun setWebAdmin(on: Boolean) = write(current().copy(webAdmin = on))

    private fun write(value: SettingsFile) = synchronized(this) {
        config.dataDir.mkdirs()
        val temp = File(config.dataDir, "config.json.tmp")
        temp.writeText(Wire.json.encodeToString(SettingsFile.serializer(), value) + "\n")
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cached = null
    }
}

/**
 * Jobs for the server itself: install an update, restart, back up now, check for updates. The server can't run
 * anything on the machine (it lives in a locked-down container); it leaves a request in data/control/, where
 * control.sh (a systemd path unit on the host, installed by update.sh) picks it up, runs that one job, and writes
 * back how it went. Only the four jobs in [ServerAction] exist; nothing in the request is ever run as a command.
 */
class ServerControl(private val config: ServerConfig, private val clock: Clock) {
    private val dir = File(config.dataDir, "control")
    private val request = File(dir, "request.env")
    private val status = File(dir, "status.env")
    private val log = File(dir, "last.log")

    /** control.sh marks itself installed here; without it nothing would pick a request up. */
    fun agentInstalled(): Boolean = File(dir, "agent").isFile

    fun status(): ControlStatusDto? {
        val pending = request.takeIf { it.isFile }?.let { read(it) }
        if (pending != null) {
            return ControlStatusDto(pending["id"].orEmpty(), pending["action"].orEmpty(), "queued", requestedAt = pending["requestedAt"]?.toLongOrNull())
        }
        val values = status.takeIf { it.isFile }?.let { read(it) } ?: return null
        return ControlStatusDto(
            id = values["id"].orEmpty(),
            action = values["action"].orEmpty(),
            state = values["state"]?.takeIf { it in setOf("running", "done", "failed") } ?: "failed",
            startedAt = values["startedAt"]?.toLongOrNull()?.times(1000),
            finishedAt = values["finishedAt"]?.toLongOrNull()?.times(1000),
            message = values["message"]?.take(300),
        )
    }

    fun log(): String? = log.takeIf { it.isFile }?.readText()?.takeLast(4000)

    fun request(action: String, by: String): ControlStatusDto {
        if (action !in ServerAction.ALL) badRequest("Unknown action")
        if (!agentInstalled()) {
            throw ApiException(
                HttpStatusCode.Conflict, "no_agent", "Server controls aren't installed yet: run update.sh once on the server",
                command = "sudo ${config.hostRoot}/update.sh",
            )
        }
        val current = status()
        if (current != null && (current.state == "queued" || current.state == "running")) conflict("Wait for the ${current.action} that's running to finish", "busy")
        dir.mkdirs()
        val id = Ids.random(8)
        val temp = File(dir, "request.env.tmp")
        // A fixed set of plain values, read by control.sh with a strict parser; no free text from the request.
        temp.writeText("id=$id\naction=$action\nrequestedAt=${clock.now()}\nby=${by.filter { it.isLetterOrDigit() }.take(24)}\n")
        Files.move(temp.toPath(), request.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return ControlStatusDto(id, action, "queued", requestedAt = clock.now())
    }

    private fun read(file: File): Map<String, String> = runCatching {
        file.readLines().mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }.toMap()
    }.getOrDefault(emptyMap())
}

/**
 * Server controls stay unlocked for a few minutes after the admin enters the server password, per session, so a
 * series of changes doesn't ask every time. Kept in memory only: a restart locks them again.
 */
class Elevation(private val clock: Clock) {
    private val until = ConcurrentHashMap<String, Long>()

    fun grant(sessionId: String): Long = (clock.now() + UNLOCKED_MS).also { until[sessionId] = it }
    fun revoke(sessionId: String) = until.remove(sessionId)
    fun unlockedUntil(sessionId: String): Long? = until[sessionId]?.takeIf { it > clock.now() }

    fun require(sessionId: String) {
        unlockedUntil(sessionId) ?: throw ApiException(HttpStatusCode.Forbidden, "locked", "Enter the server password to change this")
    }

    companion object {
        const val UNLOCKED_MS = 10 * 60_000L
    }
}
