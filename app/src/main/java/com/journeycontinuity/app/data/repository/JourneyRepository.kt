package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.domain.TelemetrySummary
import kotlinx.coroutines.flow.Flow

interface JourneyRepository {
    val activeJourney: Flow<Journey?>
    suspend fun createIfNoActive(journey: Journey): Boolean
    suspend fun completeActive(completedAt: Long): Journey?
    fun observeTelemetry(journeyId: String): Flow<TelemetrySummary>
    suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation?
}
