package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.degraded.FallbackDisposition

@Entity(
    tableName = "journey_degradation_states",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class JourneyDegradationStateEntity(
    @PrimaryKey val journeyId: String,
    val journeyActive: Boolean,
    val connectivityPhase: ConnectivityPhase,
    val fallbackDisposition: FallbackDisposition,
    val validatedInternetAvailable: Boolean,
    val fallbackBindingProvisioned: Boolean,
    val transportAvailable: Boolean,
    val degradationEpisodeId: Long?,
    val lastDegradationEpisodeId: Long,
    val interruptionStartedAtMillis: Long?,
    val lastAuthenticatedCloudSuccessAtMillis: Long?,
    val consecutiveRetryableCloudFailures: Int,
    val recoveryStartedAtMillis: Long?,
    val nextFallbackEnvelopeSequence: Long,
    val lastFallbackAttemptAtMillis: Long?,
    val lastFallbackAttemptEpisodeId: Long?,
    val lastCoveredTelemetrySequence: Long?,
    val rateWindowStartedAtMillis: Long?,
    val fallbackAttemptsInRateWindow: Int,
    val latestTelemetrySequence: Long?,
    val latestBatteryPercent: Int?,
    val recoveryTargetTelemetrySequence: Long? = null,
    val recoveryBacklogSatisfiedAtMillis: Long? = null,
)

fun JourneyDegradationStateEntity.toDomain() = DegradedConnectivityState(
    journeyId = journeyId,
    journeyActive = journeyActive,
    connectivityPhase = connectivityPhase,
    fallbackDisposition = fallbackDisposition,
    validatedInternetAvailable = validatedInternetAvailable,
    fallbackBindingProvisioned = fallbackBindingProvisioned,
    transportAvailable = transportAvailable,
    degradationEpisodeId = degradationEpisodeId,
    lastDegradationEpisodeId = lastDegradationEpisodeId,
    interruptionStartedAtMillis = interruptionStartedAtMillis,
    lastAuthenticatedCloudSuccessAtMillis = lastAuthenticatedCloudSuccessAtMillis,
    consecutiveRetryableCloudFailures = consecutiveRetryableCloudFailures,
    recoveryStartedAtMillis = recoveryStartedAtMillis,
    nextFallbackEnvelopeSequence = nextFallbackEnvelopeSequence,
    lastFallbackAttemptAtMillis = lastFallbackAttemptAtMillis,
    lastFallbackAttemptEpisodeId = lastFallbackAttemptEpisodeId,
    lastCoveredTelemetrySequence = lastCoveredTelemetrySequence,
    rateWindowStartedAtMillis = rateWindowStartedAtMillis,
    fallbackAttemptsInRateWindow = fallbackAttemptsInRateWindow,
    latestTelemetrySequence = latestTelemetrySequence,
    latestBatteryPercent = latestBatteryPercent,
    recoveryTargetTelemetrySequence = recoveryTargetTelemetrySequence,
    recoveryBacklogSatisfiedAtMillis = recoveryBacklogSatisfiedAtMillis,
)

fun DegradedConnectivityState.toEntity(): JourneyDegradationStateEntity {
    val persistedJourneyId = requireNotNull(journeyId) { "A persisted degradation state requires a Journey." }
    return JourneyDegradationStateEntity(
        journeyId = persistedJourneyId,
        journeyActive = journeyActive,
        connectivityPhase = connectivityPhase,
        fallbackDisposition = fallbackDisposition,
        validatedInternetAvailable = validatedInternetAvailable,
        fallbackBindingProvisioned = fallbackBindingProvisioned,
        transportAvailable = transportAvailable,
        degradationEpisodeId = degradationEpisodeId,
        lastDegradationEpisodeId = lastDegradationEpisodeId,
        interruptionStartedAtMillis = interruptionStartedAtMillis,
        lastAuthenticatedCloudSuccessAtMillis = lastAuthenticatedCloudSuccessAtMillis,
        consecutiveRetryableCloudFailures = consecutiveRetryableCloudFailures,
        recoveryStartedAtMillis = recoveryStartedAtMillis,
        nextFallbackEnvelopeSequence = nextFallbackEnvelopeSequence,
        lastFallbackAttemptAtMillis = lastFallbackAttemptAtMillis,
        lastFallbackAttemptEpisodeId = lastFallbackAttemptEpisodeId,
        lastCoveredTelemetrySequence = lastCoveredTelemetrySequence,
        rateWindowStartedAtMillis = rateWindowStartedAtMillis,
        fallbackAttemptsInRateWindow = fallbackAttemptsInRateWindow,
        latestTelemetrySequence = latestTelemetrySequence,
        latestBatteryPercent = latestBatteryPercent,
        recoveryTargetTelemetrySequence = recoveryTargetTelemetrySequence,
        recoveryBacklogSatisfiedAtMillis = recoveryBacklogSatisfiedAtMillis,
    )
}
