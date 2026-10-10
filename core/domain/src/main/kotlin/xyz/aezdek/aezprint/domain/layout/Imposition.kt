package xyz.aezdek.aezprint.domain.layout

data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    operator fun contains(point: Pair<Double, Double>): Boolean =
        point.first in left..right && point.second in top..bottom

    fun intersect(other: Rect): Rect? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (l < r && t < b) Rect(l, t, r, b) else null
    }

    companion object {
        fun ofSize(left: Double, top: Double, width: Double, height: Double) = Rect(left, top, left + width, top + height)
    }
}

/** Where one PDF page lands on a sheet face, in points from the face's top-left corner. */
data class Placement(
    val page: Int,
    val frame: Rect,
    val clip: Rect? = null,
)

data class Face(
    val sheetNumber: Int,
    val isBack: Boolean,
    val widthPt: Double,
    val heightPt: Double,
    val placements: List<Placement>,
    val borders: List<Rect> = emptyList(),
    val cutMarks: Rect? = null,
    val label: String? = null,
) {
    val isLandscape: Boolean get() = widthPt > heightPt

    /** Resolves a tap at fractional face coordinates (0..1) to the page printed there. */
    fun pageAt(fx: Double, fy: Double): Int? {
        val point = fx * widthPt to fy * heightPt
        return placements.lastOrNull { placement -> point in (placement.clip?.let(placement.frame::intersect) ?: placement.frame) }?.page
    }
}

data class Imposition(
    val sides: Int,
    val faces: List<Face>,
) {
    val sheetCount: Int get() = if (faces.isEmpty()) 0 else (faces.size + sides - 1) / sides

    fun totalSheets(copies: Int): Int = sheetCount * copies

    companion object {
        val EMPTY = Imposition(sides = 1, faces = emptyList())
    }
}
