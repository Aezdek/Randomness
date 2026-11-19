package xyz.aezdek.daybook.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bumped only for changes an older peer cannot read. */
const val PROTOCOL_VERSION: Int = 1

/** Anything that is merged with last-writer-wins: a newer stamp replaces an older one. */
interface Stamped {
    /** Milliseconds since the epoch, from the editing device's clock, always ahead of the version it replaced. */
    val updatedAt: Long

    /** The editing device's id. Breaks ties between equal [updatedAt] values the same way on every peer. */
    val updatedBy: String
}

/** True when [this] should replace [other]. */
fun Stamped.supersedes(other: Stamped): Boolean =
    updatedAt > other.updatedAt || (updatedAt == other.updatedAt && updatedBy > other.updatedBy)

fun Stamped.sameStampAs(other: Stamped): Boolean = updatedAt == other.updatedAt && updatedBy == other.updatedBy

/**
 * The stamp for an edit of a record whose current stamp is [previous]. It follows the wall clock, but
 * never falls behind the version being replaced, so an edit always wins over what it was based on even
 * when this device's clock runs slow.
 */
fun nextStamp(now: Long, previous: Long?): Long = if (previous == null) now else maxOf(now, previous + 1)

@Serializable
data class TaskRecord(
    val id: String,
    val title: String,
    /** A [Subject.id]. Kept as a string so a newer client's subjects pass through an older server. */
    val subject: String,
    /** A [Paper.id] such as "P3", or empty. */
    val paper: String = "",
    /** Planned time in minutes, 0 when not set. */
    val minutes: Int = 0,
    /** The day the task is planned for, as an ISO date (yyyy-MM-dd). */
    val date: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long,
    override val updatedAt: Long,
    override val updatedBy: String,
    /** Deletions are kept as tombstones so they reach every device. */
    val deleted: Boolean = false,
) : Stamped

/** A small synced key-value pair, such as the exam date. */
@Serializable
data class SettingRecord(
    val key: String,
    val value: String,
    override val updatedAt: Long,
    override val updatedBy: String,
    val deleted: Boolean = false,
) : Stamped

object SettingKeys {
    const val EXAM_DATE = "examDate"
}

@Serializable
data class SyncRequest(
    /** The highest server revision this device has applied. 0 for a first sync. */
    val since: Long,
    val deviceId: String,
    /** Local changes not yet accepted by the server. */
    val tasks: List<TaskRecord> = emptyList(),
    val settings: List<SettingRecord> = emptyList(),
)

@Serializable
data class SyncResponse(
    /** Apply [tasks] and [settings], then send this as `since` next time. */
    val rev: Long,
    /** Every record changed after `since`, plus the server's copy of any pushed record that lost. */
    val tasks: List<TaskRecord> = emptyList(),
    val settings: List<SettingRecord> = emptyList(),
    /** Ids of pushed records the server refused as malformed. They will never be accepted as they are. */
    val rejected: List<String> = emptyList(),
    /** More changes are waiting: sync again straight away. */
    val hasMore: Boolean = false,
    val serverTime: Long = 0,
)

@Serializable
data class WaitResponse(val rev: Long)

@Serializable
data class PingResponse(
    val service: String = "daybook",
    val protocol: Int = PROTOCOL_VERSION,
    val rev: Long,
    val tasks: Long,
)

object Limits {
    const val MAX_ID = 64
    const val MAX_TITLE = 300
    const val MAX_PAPER = 16
    const val MAX_SUBJECT = 16
    const val MAX_MINUTES = 24 * 60
    const val MAX_SETTING_KEY = 64
    const val MAX_SETTING_VALUE = 1024

    /** Records per push. */
    const val MAX_BATCH = 500

    /** Records per pull page. */
    const val PAGE = 1000
}

val DaybookJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
