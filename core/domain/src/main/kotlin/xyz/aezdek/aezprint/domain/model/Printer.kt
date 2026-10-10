package xyz.aezdek.aezprint.domain.model

import kotlinx.serialization.Serializable

data class Printer(
    val name: String,
    val status: PrinterStatus,
    val stateReasons: List<String>,
    val accepting: Boolean,
    val media: List<MediaSize>,
    val capabilities: PrinterCapabilities,
) {
    val defaultMedia: MediaSize? get() = media.firstOrNull()
}

@Serializable
enum class PrinterStatus { IDLE, PRINTING, OFFLINE, OUT_OF_PAPER, JAM, ERROR }

data class PrinterCapabilities(
    val color: Boolean,
    val duplex: DuplexSupport,
    val resolutions: List<Int>,
)

enum class DuplexSupport { NONE, MANUAL, AUTOMATIC }
