package xyz.aezdek.aezprint.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class JobState(val apiValue: String) {
    QUEUED("queued"),
    PROCESSING("processing"),
    AWAITING_FLIP("awaiting_flip"),
    COMPLETED("completed"),
    CANCELED("canceled"),
    FAILED("failed");

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELED || this == FAILED

    companion object {
        fun fromApi(value: String): JobState? = entries.firstOrNull { it.apiValue == value }
    }
}

data class Job(
    val id: String,
    val state: JobState,
    val sheetsCompleted: Int,
    val sheetsTotal: Int,
    val message: String?,
) {
    val progress: Float
        get() = if (sheetsTotal <= 0) 0f else (sheetsCompleted.toFloat() / sheetsTotal).coerceIn(0f, 1f)
}

/** The app's local view of a print: covers the time before the server has assigned a job id. */
@Serializable
data class PrintRecord(
    val localId: String,
    val documentId: String,
    val documentName: String,
    val options: PrintOptions,
    val createdAt: Long,
    val phase: PrintPhase = PrintPhase.UPLOADING,
    val uploadProgress: Float = 0f,
    val remoteDocumentId: String? = null,
    val jobId: String? = null,
    val jobState: JobState? = null,
    val sheetsCompleted: Int = 0,
    val sheetsTotal: Int = 0,
    val message: String? = null,
) {
    val isActive: Boolean
        get() = phase == PrintPhase.UPLOADING || phase == PrintPhase.SUBMITTING ||
            (phase == PrintPhase.SUBMITTED && jobState?.isTerminal != true)

    val awaitingFlip: Boolean get() = jobState == JobState.AWAITING_FLIP
}

@Serializable
enum class PrintPhase { UPLOADING, SUBMITTING, SUBMITTED, FAILED, CANCELED }
