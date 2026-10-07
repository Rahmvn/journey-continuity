package com.journeycontinuity.app.domain.probableend

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Local spherical distance. Fixed IUGG mean Earth radius; not a survey-grade ellipsoid model. */
object GeographicDistance {
    const val EARTH_RADIUS_METERS = 6_371_008.8

    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0

    fun meters(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double {
        require(isValidCoordinate(latitudeA, longitudeA)) { "Invalid first geographic coordinate." }
        require(isValidCoordinate(latitudeB, longitudeB)) { "Invalid second geographic coordinate." }
        val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
        val longitudeDelta = Math.toRadians(((longitudeB - longitudeA + 540.0) % 360.0) - 180.0)
        val a = (sin(latitudeDelta / 2).let { it * it } +
            cos(Math.toRadians(latitudeA)) * cos(Math.toRadians(latitudeB)) *
            sin(longitudeDelta / 2).let { it * it }).coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
