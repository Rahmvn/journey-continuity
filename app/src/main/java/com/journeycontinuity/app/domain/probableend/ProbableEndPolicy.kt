package com.journeycontinuity.app.domain.probableend

/** Changing any evidence rule requires a new policy identifier. */
object ProbableEndPolicy {
    const val VERSION = "alabarin-probable-end-v1"
    const val DESTINATION_PROXIMITY_RADIUS_METERS = 100.0
    const val MAX_HORIZONTAL_ACCURACY_METERS = 50.0
    const val MAX_OBSERVATION_AGE_MILLIS = 90_000L
    const val MAX_FUTURE_SKEW_MILLIS = 30_000L
    const val MAX_OBSERVATION_GAP_MILLIS = 90_000L
    const val MIN_PROXIMITY_OBSERVATIONS = 4
    const val MIN_PROXIMITY_SPAN_MILLIS = 180_000L
    const val MIN_EXIT_OBSERVATIONS = 2
    const val MIN_EXIT_SPAN_MILLIS = 60_000L

    fun hasQualifyingAccuracy(accuracyMeters: Double): Boolean =
        accuracyMeters.isFinite() && accuracyMeters > 0 && accuracyMeters <= MAX_HORIZONTAL_ACCURACY_METERS

    fun isWithinProximity(distanceMeters: Double, accuracyMeters: Double): Boolean =
        distanceMeters.isFinite() && distanceMeters >= 0 && hasQualifyingAccuracy(accuracyMeters) &&
            distanceMeters + accuracyMeters <= DESTINATION_PROXIMITY_RADIUS_METERS

    fun isReliableExit(distanceMeters: Double, accuracyMeters: Double): Boolean =
        distanceMeters.isFinite() && distanceMeters >= 0 && hasQualifyingAccuracy(accuracyMeters) &&
            distanceMeters - accuracyMeters > DESTINATION_PROXIMITY_RADIUS_METERS
}
