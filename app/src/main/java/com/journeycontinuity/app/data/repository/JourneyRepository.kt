package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.domain.TelemetrySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

interface JourneyRepository {
    val activeJourney: Flow<Journey?>
    /** Exact persisted Journey, including its terminal state, for a foreground result. */
    fun observeJourney(journeyId: String): Flow<Journey?> = flowOf(null)
    fun observeAcknowledgedStop(journeyId: String): Flow<Journey?> = flowOf(null)
    suspend fun createIfNoActive(journey: Journey): Boolean
    suspend fun completeActive(completedAt: Long): Journey?
    suspend fun completeExact(journeyId: String, completedAt: Long): Journey? =
        if (activeJourney.first()?.id == journeyId) completeActive(completedAt) else null
    /** Persist only after the owner-authenticated cloud terminal transition is acknowledged. */
    suspend fun stopMonitoringActive(journeyId: String, endedAt: Long): Journey?
    fun observeTelemetry(journeyId: String): Flow<TelemetrySummary>
    suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation?
    fun observeSyncState(journeyId: String): Flow<JourneySyncState?>
    fun observeMonitoringState(journeyId: String): Flow<CloudMonitoringState?>
}
