package xyz.aezdek.aezprint.designsystem.format

import android.text.format.DateUtils
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.PrintPhase
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.model.SizingMode

object Formatting {

    fun optionsSummary(options: PrintOptions): String = buildList {
        add(if (options.copies == 1) "1 copy" else "${options.copies} copies")
        when (val pages = options.pages) {
            PageSelection.All -> Unit
            is PageSelection.Current -> add("page ${pages.page}")
            is PageSelection.Custom -> add("pages ${pages.expression}")
        }
        when (options.sizing.mode) {
            SizingMode.MULTIPLE -> add("${options.multiple.pagesPerSheet} per sheet")
            SizingMode.BOOKLET -> add("booklet")
            SizingMode.POSTER -> add("poster ${options.poster.tileScalePercent}%")
            SizingMode.CUSTOM -> add("${options.sizing.scalePercent}%")
            else -> Unit
        }
        if (options.isTwoSided) add("two-sided")
        if (options.tonerSave) add("toner saver")
    }.joinToString(" · ")

    fun status(record: PrintRecord): String = when {
        record.phase == PrintPhase.UPLOADING -> "Uploading"
        record.phase == PrintPhase.SUBMITTING -> "Sending"
        record.phase == PrintPhase.CANCELED -> "Canceled"
        record.phase == PrintPhase.FAILED -> "Failed"
        else -> when (record.jobState) {
            JobState.QUEUED, null -> "Queued"
            JobState.PROCESSING -> "Printing"
            JobState.AWAITING_FLIP -> "Flip the stack"
            JobState.COMPLETED -> "Printed"
            JobState.CANCELED -> "Canceled"
            JobState.FAILED -> "Failed"
        }
    }

    fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String =
        DateUtils.getRelativeTimeSpanString(epochMillis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    fun flipEdge(options: PrintOptions): Duplex =
        if (options.sizing.mode == SizingMode.BOOKLET) Duplex.SHORT_EDGE else options.duplex

    fun fileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
