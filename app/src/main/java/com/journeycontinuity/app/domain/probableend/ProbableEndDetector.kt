package com.journeycontinuity.app.domain.probableend

import com.journeycontinuity.app.domain.TelemetryObservation
import java.security.MessageDigest

/** Pure, sequential reducer. No clock reads, Android APIs, connectivity gate or Journey mutations. */
object ProbableEndDetector {
    fun evaluate(
        journey: ProbableEndJourney,
        observation: TelemetryObservation,
        evaluatedAtMillis: Long,
        previous: ProbableEndDetectorState? = null,
    ): ProbableEndResult {
        journey.ineligibleReason?.let { return ProbableEndResult.Ineligible(it) }
        val destination = requireNotNull(journey.destination)
        val reference = DestinationEvidenceReference(journey.id)
        var state = previous?.takeIf {
            it.journeyId == journey.id && it.journeyStartedAtMillis == journey.startedAtMillis &&
                it.destination == reference && it.policyVersion == ProbableEndPolicy.VERSION
        } ?: ProbableEndDetectorState(journey.id, journey.startedAtMillis, reference)

        fun reset(reason: ProbableEndReason) = ProbableEndResult.Reset(reason, state.copy(proximity = null, exit = null))
        if (observation.journeyId != journey.id) return reset(ProbableEndReason.JOURNEY_MISMATCH)
        if (observation.sequence <= 0) return reset(ProbableEndReason.INVALID_OBSERVATION_IDENTITY)
        if (evaluatedAtMillis <= 0) return reset(ProbableEndReason.INVALID_EVALUATION_TIME)

        // Consume sequence IDs even for rejected telemetry. A rejected future time must not poison
        // the advancing observed-time watermark. Rejection never deletes the underlying telemetry.
        val priorSequence = state.highestTelemetrySequence
        state = state.copy(highestTelemetrySequence = maxOf(priorSequence, observation.sequence))
        // Replaying old evidence is not a new continuity failure, even if it is now stale.
        if (observation.sequence <= priorSequence) {
            return ProbableEndResult.Ignored(ProbableEndReason.DUPLICATE, state)
        }
        val time = observation.eventTime
        if (time <= 0) return reset(ProbableEndReason.INVALID_EVENT_TIME)
        if (time < journey.startedAtMillis) return reset(ProbableEndReason.PRE_JOURNEY)
        val identity = observation.evidenceReference()
        val latest = state.latestAdvancingObservation
        if (latest != null && time <= latest.observedAtMillis) {
            val reason = if (identity.fingerprint == latest.fingerprint) ProbableEndReason.DUPLICATE
                else ProbableEndReason.NON_ADVANCING_TIME
            return ProbableEndResult.Ignored(reason, state)
        }
        if (time > evaluatedAtMillis && time - evaluatedAtMillis > ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS) {
            return reset(ProbableEndReason.FUTURE_DATED)
        }
        if (time <= evaluatedAtMillis && evaluatedAtMillis - time > ProbableEndPolicy.MAX_OBSERVATION_AGE_MILLIS) {
            return reset(ProbableEndReason.STALE)
        }

        state = state.copy(latestAdvancingObservation = identity)
        if (!GeographicDistance.isValidCoordinate(observation.latitude, observation.longitude)) {
            return reset(ProbableEndReason.BAD_COORDINATE)
        }
        val accuracy = observation.accuracyMeters.toDouble()
        if (!ProbableEndPolicy.hasQualifyingAccuracy(accuracy)) return reset(ProbableEndReason.INVALID_ACCURACY)
        val distance = GeographicDistance.meters(observation.latitude, observation.longitude,
            requireNotNull(destination.latitude), requireNotNull(destination.longitude))
        val kind = when {
            ProbableEndPolicy.isWithinProximity(distance, accuracy) -> ProbableEndEvidenceKind.PROXIMITY
            ProbableEndPolicy.isReliableExit(distance, accuracy) -> ProbableEndEvidenceKind.RELIABLE_EXIT
            else -> return reset(ProbableEndReason.OUTSIDE_PROXIMITY)
        }
        val previousStreak = if (kind == ProbableEndEvidenceKind.PROXIMITY) state.proximity else state.exit
        val gap = previousStreak != null && time - previousStreak.latest.observedAtMillis >
            ProbableEndPolicy.MAX_OBSERVATION_GAP_MILLIS
        val streak = if (previousStreak == null || gap) ProbableEndStreak(identity, identity, 1)
            else previousStreak.copy(latest = identity, count = previousStreak.count + 1)
        state = state.copy(proximity = streak.takeIf { kind == ProbableEndEvidenceKind.PROXIMITY },
            exit = streak.takeIf { kind == ProbableEndEvidenceKind.RELIABLE_EXIT })
        if (gap) return ProbableEndResult.Reset(ProbableEndReason.EVIDENCE_GAP, state)

        val sufficient = when (kind) {
            ProbableEndEvidenceKind.PROXIMITY -> streak.count >= ProbableEndPolicy.MIN_PROXIMITY_OBSERVATIONS &&
                streak.observedSpanMillis >= ProbableEndPolicy.MIN_PROXIMITY_SPAN_MILLIS
            ProbableEndEvidenceKind.RELIABLE_EXIT -> streak.count >= ProbableEndPolicy.MIN_EXIT_OBSERVATIONS &&
                streak.observedSpanMillis >= ProbableEndPolicy.MIN_EXIT_SPAN_MILLIS
        }
        if (!sufficient) return ProbableEndResult.Accumulating(kind, state)
        val evidence = ProbableEndEvidenceSummary(journey.id, ProbableEndPolicy.VERSION, reference, kind,
            streak.first, streak.latest, streak.count, streak.observedSpanMillis, distance, accuracy)
        return if (kind == ProbableEndEvidenceKind.PROXIMITY) ProbableEndResult.QualifyingCandidate(evidence, state)
            else ProbableEndResult.ReliableDeparture(evidence, state)
    }
}

private fun TelemetryObservation.evidenceReference(): ObservationEvidenceReference {
    // Fixed, locale-independent representation. Zero and the +/-180 longitude seam are canonical.
    fun bits(value: Double) = java.lang.Double.doubleToLongBits(if (value == 0.0) 0.0 else value).toString(16)
    val longitude = if (longitude == 180.0) -180.0 else longitude
    val content = "$journeyId|$eventTime|${bits(latitude)}|${bits(longitude)}|${bits(accuracyMeters.toDouble())}"
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return ObservationEvidenceReference(sequence, eventTime, fingerprint)
}
