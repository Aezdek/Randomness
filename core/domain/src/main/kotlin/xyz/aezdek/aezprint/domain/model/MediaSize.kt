package xyz.aezdek.aezprint.domain.model

import kotlin.math.abs

/** A paper size identified by its PWG self-describing name, e.g. `iso_a4_210x297mm`. */
data class MediaSize(
    val id: String,
    val displayName: String,
    val widthPt: Double,
    val heightPt: Double,
) {
    val widthMm: Double get() = widthPt / POINTS_PER_MM
    val heightMm: Double get() = heightPt / POINTS_PER_MM

    fun matches(page: PageSize, tolerancePt: Double = 6.0): Boolean {
        val portrait = abs(widthPt - page.widthPt) <= tolerancePt && abs(heightPt - page.heightPt) <= tolerancePt
        val landscape = abs(widthPt - page.heightPt) <= tolerancePt && abs(heightPt - page.widthPt) <= tolerancePt
        return portrait || landscape
    }

    companion object {
        const val POINTS_PER_INCH = 72.0
        const val POINTS_PER_MM = POINTS_PER_INCH / 25.4

        private val pwgPattern = Regex("""^([a-z0-9]+)_([a-z0-9.\-]+)_([0-9.]+)x([0-9.]+)(mm|in)$""")

        fun fromPwg(id: String): MediaSize? {
            val match = pwgPattern.matchEntire(id.trim().lowercase()) ?: return null
            val (_, name, w, h, unit) = match.destructured
            val factor = if (unit == "mm") POINTS_PER_MM else POINTS_PER_INCH
            val width = w.toDoubleOrNull() ?: return null
            val height = h.toDoubleOrNull() ?: return null
            return MediaSize(id, prettyName(name), width * factor, height * factor)
        }

        private fun prettyName(name: String): String = when (name) {
            "letter" -> "Letter"
            "legal" -> "Legal"
            "executive" -> "Executive"
            "foolscap" -> "Folio"
            else -> name.replace('-', ' ').uppercase()
        }
    }
}

data class PageSize(val widthPt: Double, val heightPt: Double) {
    val isLandscape: Boolean get() = widthPt > heightPt
}
