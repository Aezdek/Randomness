package xyz.aezdek.daybook.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.aezdek.daybook.sync.Appearance
import xyz.aezdek.daybook.sync.ConfigStore
import xyz.aezdek.daybook.sync.SyncConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/** Where Daybook keeps its files, following the XDG base directory spec. */
object DesktopPaths {
    private val home = System.getProperty("user.home")

    val dataDir: File by lazy {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
        File(base, "daybook").also { it.mkdirs() }
    }

    val configDir: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
        File(base, "daybook").also { dir ->
            dir.mkdirs()
            restrict(dir, directory = true)
        }
    }

    val database: File get() = File(dataDir, "daybook.db")

    /** Owner-only permissions, so other users on the machine can't read the token. */
    fun restrict(file: File, directory: Boolean = false) {
        runCatching {
            val perms = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            if (directory) perms += PosixFilePermission.OWNER_EXECUTE
            Files.setPosixFilePermissions(file.toPath(), perms)
        }
    }
}

/**
 * Sync settings in `~/.config/daybook/config.json`, readable only by you. Linux has no keystore that
 * every desktop provides, so the file's permissions are the protection, as with SSH keys.
 */
class DesktopConfigStore(private val file: File = File(DesktopPaths.configDir, "config.json")) : ConfigStore {

    @Serializable
    private data class Stored(
        val serverUrl: String = "",
        val token: String = "",
        val accessClientId: String = "",
        val accessClientSecret: String = "",
        val appearance: String = Appearance.System.name,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    private fun read(): Stored = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrDefault(Stored())

    @Synchronized
    private fun write(value: Stored) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), value))
        DesktopPaths.restrict(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun loadSync(): SyncConfig = read().let { SyncConfig(it.serverUrl, it.token, it.accessClientId, it.accessClientSecret) }

    override fun saveSync(config: SyncConfig) = write(
        read().copy(
            serverUrl = config.serverUrl,
            token = config.token,
            accessClientId = config.accessClientId,
            accessClientSecret = config.accessClientSecret,
        ),
    )

    override fun loadAppearance(): Appearance = runCatching { Appearance.valueOf(read().appearance) }.getOrDefault(Appearance.System)

    override fun saveAppearance(appearance: Appearance) = write(read().copy(appearance = appearance.name))
}

/** Desktop preferences that live in GNOME's settings. Anything that can't be read falls back to the default. */
object DesktopSystem {
    private fun gsetting(schema: String, key: String): String? = runCatching {
        val process = ProcessBuilder("gsettings", "get", schema, key).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 }
    }.getOrNull()

    /** GNOME 42+ "Dark Style", or a theme whose name says dark. */
    fun prefersDark(): Boolean {
        val scheme = gsetting("org.gnome.desktop.interface", "color-scheme")
        if (scheme != null && scheme.contains("dark")) return true
        if (scheme != null && scheme.contains("light")) return false
        return gsetting("org.gnome.desktop.interface", "gtk-theme")?.contains("dark", ignoreCase = true) == true
    }

    /** GNOME's "Reduce Animation" / "Animations" switch. */
    fun reduceMotion(): Boolean = gsetting("org.gnome.desktop.interface", "enable-animations") == "false"
}
