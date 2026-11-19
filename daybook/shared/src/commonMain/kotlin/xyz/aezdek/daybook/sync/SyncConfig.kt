package xyz.aezdek.daybook.sync

/** Where and how to reach the sync server. Kept per device, never synced. */
data class SyncConfig(
    val serverUrl: String = "",
    val token: String = "",
    /** Optional Cloudflare Access service token, for servers behind Cloudflare Access. */
    val accessClientId: String = "",
    val accessClientSecret: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    /** The URL without trailing slashes or surrounding spaces. */
    val baseUrl: String get() = serverUrl.trim().trimEnd('/')
}

/** Device-local preferences that aren't synced. */
enum class Appearance { System, Light, Dark }

/** Platform storage for [SyncConfig] and local preferences. Secrets are kept encrypted where the platform allows. */
interface ConfigStore {
    fun loadSync(): SyncConfig
    fun saveSync(config: SyncConfig)
    fun loadAppearance(): Appearance
    fun saveAppearance(appearance: Appearance)
}

/** Why [url] can't be used, or null when it is fine. */
fun serverUrlProblem(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "Enter the server address."
    val match = Regex("^(https?)://([^/:?#\\s]+)(:\\d+)?(/[^\\s?#]*)?$").matchEntire(trimmed)
        ?: return "Enter a full address, like https://daybook.example.com"
    val (scheme, host) = match.destructured
    if (scheme == "http" && !isLocalHost(host)) {
        return "Use https. Plain http would send your token unencrypted."
    }
    return null
}

/** Hosts where plain http is acceptable: this machine and the Android emulator's alias for it. */
fun isLocalHost(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
