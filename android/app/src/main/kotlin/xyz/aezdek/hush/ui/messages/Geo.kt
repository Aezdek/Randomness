package xyz.aezdek.hush.ui.messages

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Metres between two points on Earth (haversine). */
internal fun distanceMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val rad = Math.PI / 180
    val a = sin((lat2 - lat1) * rad / 2).pow(2) + cos(lat1 * rad) * cos(lat2 * rad) * sin((lng2 - lng1) * rad / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
