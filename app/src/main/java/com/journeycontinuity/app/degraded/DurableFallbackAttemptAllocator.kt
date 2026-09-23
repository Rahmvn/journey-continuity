package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.data.local.DegradedConnectivityDao
import com.journeycontinuity.app.data.local.FallbackAttemptDao
import com.journeycontinuity.app.data.local.FallbackAttemptEntity
import com.journeycontinuity.app.data.local.JourneyDao
import com.journeycontinuity.app.data.local.TelemetryDao
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.domain.JourneyStatus
import kotlin.math.roundToInt

sealed interface FallbackAllocationResult {
    data class Available(
        val attempt: FallbackAttemptEntity,
        val newlyAllocated: Boolean,
    ) : FallbackAllocationResult

    data object Unavailable : FallbackAllocationResult
}

class DurableFallbackAttemptAllocator(
    private val journeyDao: JourneyDao,
    private val telemetryDao: TelemetryDao,
    private val degradationDao: DegradedConnectivityDao,
    private val fallbackAttemptDao: FallbackAttemptDao,
    private val keyMaterialStore: FallbackKeyMaterialStore,
    private val nonceSource: FallbackNonceSource = SecureFallbackNonceSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun allocate(action: DegradedConnectivityAction.AllocateFallbackAttempt): FallbackAllocationResult {
        fallbackAttemptDao.getLogicalAttempt(
            action.journeyId,
            action.degradationEpisodeId,
            action.telemetrySequence,
        )?.let { existing ->
            require(existing.envelopeSequence == action.envelopeSequence) {
                "A logical fallback attempt cannot be assigned a different envelope sequence."
            }
            return FallbackAllocationResult.Available(existing, newlyAllocated = false)
        }

        fallbackAttemptDao.getByEnvelopeSequence(action.journeyId, action.envelopeSequence)?.let {
            error("A fallback envelope sequence is already allocated to another logical attempt.")
        }

        val state = degradationDao.get(action.journeyId)?.toDomain()
            ?: return FallbackAllocationResult.Unavailable
        if (!state.journeyActive ||
            state.fallbackDisposition != FallbackDisposition.ELIGIBLE ||
            state.degradationEpisodeId != action.degradationEpisodeId ||
            state.nextFallbackEnvelopeSequence != action.envelopeSequence ||
            state.latestTelemetrySequence != action.telemetrySequence
        ) return FallbackAllocationResult.Unavailable

        if (journeyDao.getById(action.journeyId)?.status != JourneyStatus.ACTIVE) {
            return FallbackAllocationResult.Unavailable
        }
        val observation = telemetryDao.getBySequence(action.journeyId, action.telemetrySequence)
            ?: return FallbackAllocationResult.Unavailable
        val binding = fallbackAttemptDao.getBinding(action.journeyId)
            ?.takeIf { it.status == FallbackBindingStatus.PROVISIONED }
            ?: return FallbackAllocationResult.Unavailable
        if (binding.journeyHandle.size != FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES ||
            !keyMaterialStore.hasKey(binding.keyId)
        ) return FallbackAllocationResult.Unavailable

        val nonce = nonceSource.nextNonce()
        require(nonce.size == FallbackEnvelopeV1.NONCE_BYTES)
        val header = FallbackEnvelopeV1.Header(
            eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
            keyId = binding.keyId,
            journeyHandle = binding.journeyHandle,
            envelopeSequence = action.envelopeSequence,
            nonce = nonce,
        )
        val body = FallbackEnvelopeV1.Body(
            telemetrySequence = observation.sequence,
            observationEventTimeUnixSeconds = observation.eventTime / 1_000,
            latitudeE7 = (observation.latitude * 10_000_000).roundToInt(),
            longitudeE7 = (observation.longitude * 10_000_000).roundToInt(),
            accuracyDecimeters = (observation.accuracyMeters * 10).roundToInt().coerceIn(0, 0xffff),
            batteryPercent = observation.batteryPercent,
            charging = observation.isCharging,
            connectivity = observation.connectivity,
        )
        val encoded = keyMaterialStore.useKey(binding.keyId) { installationKey ->
            AesGcmEnvelopeProtector(installationKey).use { protector ->
                FallbackEnvelopeV1.encode(FallbackEnvelopeV1.protect(header, body, protector)).value
            }
        } ?: return FallbackAllocationResult.Unavailable
        val attempt = FallbackAttemptEntity(
            journeyId = action.journeyId,
            degradationEpisodeId = action.degradationEpisodeId,
            envelopeSequence = action.envelopeSequence,
            telemetrySequence = observation.sequence,
            observationEventTime = observation.eventTime,
            eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
            protectedPayloadText = encoded,
            nonce = nonce,
            payloadSha256 = AesGcmEnvelopeProtector.sha256(encoded.toByteArray(Charsets.UTF_8)),
            allocatedAt = clock(),
        )
        return FallbackAllocationResult.Available(
            attempt.copy(localAttemptId = fallbackAttemptDao.insert(attempt)),
            newlyAllocated = true,
        )
    }
}
