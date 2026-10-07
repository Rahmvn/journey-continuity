package com.journeycontinuity.app.domain.probableend

import com.journeycontinuity.app.domain.DestinationKind
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.StructuredDestination
import com.journeycontinuity.app.domain.StructuredDestinationValidator

enum class ProbableEndReason {
    TERMINAL_JOURNEY, INVALID_JOURNEY, LOCALITY, LEGACY_DESTINATION, INCOMPLETE_DESTINATION,
    JOURNEY_MISMATCH, INVALID_OBSERVATION_IDENTITY, BAD_COORDINATE, INVALID_ACCURACY,
    INVALID_EVALUATION_TIME, INVALID_EVENT_TIME, STALE, FUTURE_DATED, PRE_JOURNEY,
    DUPLICATE, NON_ADVANCING_TIME, EVIDENCE_GAP, OUTSIDE_PROXIMITY, RELIABLE_DEPARTURE,
}

/** Safe decoding seam for a later persistence adapter; does not perform any I/O. */
class ProbableEndJourney private constructor(
    val id: String,
    val startedAtMillis: Long,
    internal val destination: StructuredDestination?,
    val ineligibleReason: ProbableEndReason?,
) {
    override fun toString() = "ProbableEndJourney(redacted)"

    companion object {
        fun from(journey: Journey): ProbableEndJourney = read(journey.id, journey.status, journey.startedAt) {
            journey.structuredDestination
        }

        /** The adapter must decode malformed/unknown enum evidence inside this boundary. */
        fun read(
            id: String,
            status: JourneyStatus,
            startedAtMillis: Long,
            readDestination: () -> StructuredDestination?,
        ): ProbableEndJourney {
            fun rejected(reason: ProbableEndReason) = ProbableEndJourney(id, startedAtMillis, null, reason)
            if (status != JourneyStatus.ACTIVE) return rejected(ProbableEndReason.TERMINAL_JOURNEY)
            if (id.isBlank() || startedAtMillis <= 0) return rejected(ProbableEndReason.INVALID_JOURNEY)
            val destination = try {
                readDestination()?.also { StructuredDestinationValidator.validate(it) }
            } catch (_: IllegalArgumentException) {
                return rejected(ProbableEndReason.INCOMPLETE_DESTINATION)
            }
            if (destination == null) return rejected(ProbableEndReason.LEGACY_DESTINATION)
            if (destination.kind != DestinationKind.POINT) return rejected(ProbableEndReason.LOCALITY)
            return ProbableEndJourney(id, startedAtMillis, destination, null)
        }
    }
}

/** References the existing immutable creation-time destination, not a second coordinate snapshot. */
data class DestinationEvidenceReference(
    val journeyId: String,
    val slot: String = "structured-destination-at-creation",
)

/** Fingerprint excludes Room row ID/sequence; these identify storage, not physical acquisition. */
data class ObservationEvidenceReference(
    val telemetrySequence: Long,
    val observedAtMillis: Long,
    val fingerprint: String,
)

enum class ProbableEndEvidenceKind { PROXIMITY, RELIABLE_EXIT }

/** No raw coordinates or provider data. References resolve against existing Journey telemetry. */
data class ProbableEndEvidenceSummary(
    val journeyId: String,
    val policyVersion: String,
    val destination: DestinationEvidenceReference,
    val kind: ProbableEndEvidenceKind,
    val first: ObservationEvidenceReference,
    val final: ObservationEvidenceReference,
    val observationCount: Int,
    val observedSpanMillis: Long,
    val finalDistanceMeters: Double,
    val finalAccuracyMeters: Double,
) {
    /** Stable throughout one uninterrupted sequence; later orchestration must upsert one episode. */
    val sequenceKey: String get() = "$policyVersion:$journeyId:${kind.name}:${first.fingerprint}"
}

@ConsistentCopyVisibility
data class ProbableEndStreak internal constructor(
    val first: ObservationEvidenceReference,
    val latest: ObservationEvidenceReference,
    val count: Int,
) {
    val observedSpanMillis: Long get() = latest.observedAtMillis - first.observedAtMillis
}

/** Immutable reducer state. Later slices own durable storage, transactions and episode suppression. */
@ConsistentCopyVisibility
data class ProbableEndDetectorState internal constructor(
    val journeyId: String,
    val journeyStartedAtMillis: Long,
    val destination: DestinationEvidenceReference,
    val policyVersion: String = ProbableEndPolicy.VERSION,
    val highestTelemetrySequence: Long = 0,
    val latestAdvancingObservation: ObservationEvidenceReference? = null,
    val proximity: ProbableEndStreak? = null,
    val exit: ProbableEndStreak? = null,
)

sealed interface ProbableEndResult {
    val state: ProbableEndDetectorState?

    data class Ineligible(val reason: ProbableEndReason) : ProbableEndResult {
        override val state: ProbableEndDetectorState? = null
    }
    data class Accumulating(
        val kind: ProbableEndEvidenceKind,
        override val state: ProbableEndDetectorState,
    ) : ProbableEndResult
    data class QualifyingCandidate(
        val evidence: ProbableEndEvidenceSummary,
        override val state: ProbableEndDetectorState,
    ) : ProbableEndResult
    data class ReliableDeparture(
        val evidence: ProbableEndEvidenceSummary,
        override val state: ProbableEndDetectorState,
        val reason: ProbableEndReason = ProbableEndReason.RELIABLE_DEPARTURE,
    ) : ProbableEndResult
    /** A gap resets the previous sequence and seeds a new one with the current qualifying fix. */
    data class Reset(val reason: ProbableEndReason, override val state: ProbableEndDetectorState) : ProbableEndResult
    /** Replay is a no-op for both streaks; it never extends dwell or emits a candidate. */
    data class Ignored(val reason: ProbableEndReason, override val state: ProbableEndDetectorState) : ProbableEndResult
}
