package xyz.aezdek.aezprint.domain.model

import kotlinx.serialization.Serializable

data class ServerCredentials(
    val clientId: String,
    val clientSecret: String,
    val apiKey: String,
) {
    val isComplete: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank() && apiKey.isNotBlank()

    override fun toString(): String = "ServerCredentials(<redacted>)"
}

@Serializable
data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val useFakeServer: Boolean = true,
    val biometricLock: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
) {
    companion object {
        const val DEFAULT_SERVER_URL = "https://print.aezdek.xyz"
    }
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Knobs for the fake server so every printer and network state can be exercised without hardware. */
@Serializable
data class FakeServerConfig(
    val printerStatus: PrinterStatus = PrinterStatus.IDLE,
    val latencyMs: Long = 400,
    val failUploads: Boolean = false,
    val rejectPosterOptions: Boolean = false,
    val secondsPerSheet: Double = 1.2,
    val queueDelayMs: Long = 1_200,
)
