package com.journeycontinuity.app.domain

/** Elapsed durations, independent of time zones and calendar-day changes. */
object JourneyEtaPolicy {
    const val MAX_DURATION_MILLIS = 259_200_000L
    const val UNRESOLVED_WINDOW_MILLIS = 86_400_000L
    const val VERSION = "alabarin-unresolved-boundary-v1"

    enum class Validation { VALID, NOT_FUTURE, OUT_OF_RANGE }

    fun validate(admissionAt: Long, eta: Long): Validation {
        if (eta <= admissionAt) return Validation.NOT_FUTURE
        return try {
            val maximum = Math.addExact(admissionAt, MAX_DURATION_MILLIS)
            Math.addExact(eta, UNRESOLVED_WINDOW_MILLIS)
            if (admissionAt < 0 || eta > maximum) Validation.OUT_OF_RANGE else Validation.VALID
        } catch (_: ArithmeticException) { Validation.OUT_OF_RANGE }
    }

    fun boundary(admissionAt: Long, eta: Long): Long {
        require(validate(admissionAt, eta) == Validation.VALID) { "Invalid admitted ETA." }
        return Math.addExact(eta, UNRESOLVED_WINDOW_MILLIS)
    }
}
