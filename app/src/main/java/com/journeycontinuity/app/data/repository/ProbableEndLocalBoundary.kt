package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.data.local.JourneyEntity
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.domain.probableend.ProbableEndJourney

/** Corrupt structured evidence is ineligible; shared Journey mapping remains strict. */
internal fun JourneyEntity.probableEndInput() = ProbableEndJourney.read(id, status, startedAt) {
    try { toDomain().structuredDestination }
    catch (_: IllegalStateException) { throw IllegalArgumentException("Incomplete persisted destination evidence.") }
}
