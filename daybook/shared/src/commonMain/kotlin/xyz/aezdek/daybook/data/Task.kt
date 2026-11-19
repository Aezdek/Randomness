package xyz.aezdek.daybook.data

import kotlinx.datetime.LocalDate
import xyz.aezdek.daybook.core.Subject

/** A task as the screens see it. */
data class Task(
    val id: String,
    val title: String,
    val subject: Subject,
    /** A paper id such as "P3", or empty. */
    val paper: String,
    /** Planned minutes, 0 when not set. */
    val minutes: Int,
    val date: LocalDate,
    val done: Boolean,
    val createdAt: Long,
)

/** What the add and edit sheets produce. */
data class TaskDraft(
    val title: String,
    val subject: Subject,
    val paper: String = "",
    val minutes: Int = 0,
    val date: LocalDate,
)
