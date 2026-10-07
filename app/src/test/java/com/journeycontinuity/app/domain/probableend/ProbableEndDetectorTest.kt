package com.journeycontinuity.app.domain.probableend

import com.journeycontinuity.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class ProbableEndDetectorTest {
    private val start = 1_700_000_000_000L
    private val firstTime = start + 60_000L
    private val point = DestinationEvidenceFixtures.mapPin()
    private val journey = Journey("journey-1", "Destination", start + 3_600_000L, start,
        JourneyStatus.ACTIVE, null, structuredDestination = point)
    private val input = ProbableEndJourney.from(journey)

    private fun observation(sequence: Long = 1, time: Long = firstTime, distance: Double = 0.0,
        accuracy: Float = 8f): TelemetryObservation = TelemetryObservation(sequence, journey.id, sequence, time,
        requireNotNull(point.latitude) + Math.toDegrees(distance / GeographicDistance.EARTH_RADIUS_METERS),
        requireNotNull(point.longitude), accuracy, null, null, ConnectivityState.UNKNOWN)

    private fun evaluate(observation: TelemetryObservation = observation(), now: Long = observation.eventTime,
        previous: ProbableEndDetectorState? = null, input: ProbableEndJourney = this.input) =
        ProbableEndDetector.evaluate(input, observation, now, previous)

    private fun sequence(times: List<Long>, distance: Double = 0.0): ProbableEndResult {
        var state: ProbableEndDetectorState? = null
        var result: ProbableEndResult? = null
        times.forEachIndexed { index, time ->
            result = evaluate(observation(index + 1L, firstTime + time, distance), previous = state)
            state = result.state
        }
        return requireNotNull(result)
    }

    private fun assertReset(reason: ProbableEndReason, result: ProbableEndResult) {
        assertTrue(result.toString(), result is ProbableEndResult.Reset)
        assertEquals(reason, (result as ProbableEndResult.Reset).reason)
        assertNull(result.state.proximity)
        assertNull(result.state.exit)
    }

    @Test fun pointMapPinEligible() = assertTrue(evaluate() is ProbableEndResult.Accumulating)
    @Test fun pointPlaceSearchEligible() = assertTrue(evaluate(input = ProbableEndJourney.from(
        journey.copy(structuredDestination = DestinationEvidenceFixtures.placeSearch()))) is ProbableEndResult.Accumulating)

    @Test fun localityIneligible() = assertEquals(ProbableEndReason.LOCALITY,
        (evaluate(input = ProbableEndJourney.from(journey.copy(structuredDestination =
            DestinationEvidenceFixtures.locality()))) as ProbableEndResult.Ineligible).reason)

    @Test fun legacyIneligible() = assertEquals(ProbableEndReason.LEGACY_DESTINATION,
        (evaluate(input = ProbableEndJourney.from(journey.copy(structuredDestination = null))) as ProbableEndResult.Ineligible).reason)

    @Test fun partialPointFailsClosedAtDecodingBoundary() {
        val malformed = ProbableEndJourney.read(journey.id, JourneyStatus.ACTIVE, start) { point.copy(longitude = null) }
        assertEquals(ProbableEndReason.INCOMPLETE_DESTINATION, (evaluate(input = malformed) as ProbableEndResult.Ineligible).reason)
    }

    @Test fun missingAndContradictoryEvidenceFailsClosed() {
        val decoders = listOf<() -> StructuredDestination?>(
            { point.copy(boundaryDatasetVersion = null) },
            { point.copy(selectionSource = DestinationSelectionSource.PLACE_SEARCH) },
            { point.copy(selectionSource = DestinationSelectionSource.LOCALITY_SELECTION) },
            { point.copy(latitude = Double.NaN) },
            { point.copy(localUnitType = AdminLocalUnitType.AREA_COUNCIL) },
            { throw IllegalArgumentException("Unknown persisted destination kind") },
        )
        decoders.forEach {
            assertEquals(ProbableEndReason.INCOMPLETE_DESTINATION,
                (evaluate(input = ProbableEndJourney.read(journey.id, JourneyStatus.ACTIVE, start, it))
                    as ProbableEndResult.Ineligible).reason)
        }
    }

    @Test fun terminalJourneysIneligible() {
        listOf(JourneyStatus.COMPLETED, JourneyStatus.CANCELLED).forEach {
            assertEquals(ProbableEndReason.TERMINAL_JOURNEY,
                (evaluate(input = ProbableEndJourney.from(journey.copy(status = it))) as ProbableEndResult.Ineligible).reason)
        }
    }

    @Test fun invalidJourneyCannotAccumulate() {
        assertEquals(ProbableEndReason.INVALID_JOURNEY, ProbableEndJourney.read("", JourneyStatus.ACTIVE, start) { point }.ineligibleReason)
        assertEquals(ProbableEndReason.INVALID_JOURNEY, ProbableEndJourney.read(journey.id, JourneyStatus.ACTIVE, 0) { point }.ineligibleReason)
    }

    @Test fun exactDestinationAndInsideQualifyButEdgeAndOutsideDoNot() {
        assertTrue(evaluate(observation(distance = 0.0)) is ProbableEndResult.Accumulating)
        assertTrue(evaluate(observation(distance = 35.0)) is ProbableEndResult.Accumulating)
        assertReset(ProbableEndReason.OUTSIDE_PROXIMITY, evaluate(observation(distance = 100.0)))
        assertTrue(evaluate(observation(distance = 200.0)) is ProbableEndResult.Accumulating) // exit evidence only
        assertNull(evaluate(observation(distance = 200.0)).state!!.proximity)
    }

    @Test fun invalidDeviceCoordinatesReset() {
        listOf(observation().copy(latitude = Double.NaN), observation().copy(longitude = 181.0),
            observation().copy(latitude = Double.POSITIVE_INFINITY), observation().copy(latitude = -91.0)).forEach {
            assertReset(ProbableEndReason.BAD_COORDINATE, evaluate(it))
        }
    }

    @Test fun invalidAccuracyResets() {
        listOf(0f, -1f, 50.001f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertReset(ProbableEndReason.INVALID_ACCURACY, evaluate(observation(accuracy = it)))
        }
        assertTrue(evaluate(observation(accuracy = 50f)) is ProbableEndResult.Accumulating)
    }

    @Test fun freshAndExactlyNinetySecondsOldAccepted() {
        assertTrue(evaluate(now = firstTime + 89_999) is ProbableEndResult.Accumulating)
        assertTrue(evaluate(now = firstTime + 90_000) is ProbableEndResult.Accumulating)
    }

    @Test fun olderThanNinetySecondsRejected() = assertReset(ProbableEndReason.STALE, evaluate(now = firstTime + 90_001))
    @Test fun futureUpToThirtySecondsAccepted() = assertTrue(evaluate(now = firstTime - 30_000) is ProbableEndResult.Accumulating)
    @Test fun futureBeyondThirtySecondsRejected() = assertReset(ProbableEndReason.FUTURE_DATED, evaluate(now = firstTime - 30_001))
    @Test fun preJourneyRejected() = assertReset(ProbableEndReason.PRE_JOURNEY, evaluate(observation(time = start - 1), now = start))
    @Test fun journeyStartIsInclusive() = assertTrue(evaluate(observation(time = start)) is ProbableEndResult.Accumulating)
    @Test fun nonpositiveEventTimesRejected() {
        listOf(0L, -1L).forEach { assertReset(ProbableEndReason.INVALID_EVENT_TIME, evaluate(observation(time = it), now = firstTime)) }
    }
    @Test fun evaluationTimeIsExplicitAndMustBeValid() = assertReset(ProbableEndReason.INVALID_EVALUATION_TIME, evaluate(now = 0))

    @Test fun oneFixCannotQualify() {
        val result = evaluate()
        assertTrue(result is ProbableEndResult.Accumulating)
        assertEquals(1, result.state!!.proximity!!.count)
        assertTrue(sequence(listOf(0, 90_000, 180_000)) is ProbableEndResult.Accumulating)
    }
    @Test fun fourFixesWithoutThreeMinutesCannotQualify() {
        assertTrue(sequence(listOf(0, 30_000, 60_000, 90_000)) is ProbableEndResult.Accumulating)
        assertTrue(sequence(listOf(0, 60_000, 120_000, 179_999)) is ProbableEndResult.Accumulating)
    }

    @Test fun identicalCoordinatesAtAdvancingTimesQualifyAndSummarizeDistinctEvidence() {
        val result = sequence(listOf(0, 60_000, 120_000, 180_000)) as ProbableEndResult.QualifyingCandidate
        with(result.evidence) {
            assertEquals(journey.id, journeyId)
            assertEquals(ProbableEndPolicy.VERSION, policyVersion)
            assertEquals(ProbableEndEvidenceKind.PROXIMITY, kind)
            assertEquals(DestinationEvidenceReference(journey.id), destination)
            assertEquals(1L, first.telemetrySequence)
            assertEquals(firstTime, first.observedAtMillis)
            assertEquals(4L, final.telemetrySequence)
            assertEquals(firstTime + 180_000, final.observedAtMillis)
            assertNotEquals(first.fingerprint, final.fingerprint)
            assertEquals(4, observationCount)
            assertEquals(180_000L, observedSpanMillis)
            assertEquals(0.0, finalDistanceMeters, 0.0)
            assertEquals(8.0, finalAccuracyMeters, 0.0)
        }
    }

    @Test fun normalThirtySecondCadenceNeedsSevenFixes() {
        assertTrue(sequence((0L..5L).map { it * 30_000 }) is ProbableEndResult.Accumulating)
        assertEquals(7, (sequence((0L..6L).map { it * 30_000 }) as ProbableEndResult.QualifyingCandidate).evidence.observationCount)
    }

    @Test fun duplicateEvaluationCannotAdvance() {
        val state = evaluate().state!!
        val duplicate = evaluate(now = firstTime + 30_000, previous = state) as ProbableEndResult.Ignored
        assertEquals(ProbableEndReason.DUPLICATE, duplicate.reason)
        assertEquals(state, duplicate.state)
    }

    @Test fun samePhysicalFixWithNewRowAndSequenceCannotAdvance() {
        val state = evaluate().state!!
        val replay = observation().copy(id = 900, sequence = 2, connectivity = ConnectivityState.WIFI)
        val result = evaluate(replay, previous = state) as ProbableEndResult.Ignored
        assertEquals(ProbableEndReason.DUPLICATE, result.reason)
        assertEquals(state.proximity, result.state.proximity)
        assertEquals(state.latestAdvancingObservation, result.state.latestAdvancingObservation)
    }

    @Test fun staleReplayCannotDestroyNewerProximityEvidence() {
        var state = sequence(listOf(0, 60_000, 120_000)).state!!
        // Both the original row and a separately persisted replay are historical, not new failures.
        listOf(observation(), observation().copy(id = 900, sequence = 4)).forEach { replay ->
            val result = evaluate(replay, now = firstTime + 120_000, previous = state)
            assertTrue(result is ProbableEndResult.Ignored)
            assertEquals(state.proximity, result.state!!.proximity)
            assertEquals(state.latestAdvancingObservation, result.state!!.latestAdvancingObservation)
            state = result.state!!
        }
        val candidate = evaluate(observation(5, firstTime + 180_000), previous = state)
            as ProbableEndResult.QualifyingCandidate
        assertEquals(4, candidate.evidence.observationCount)
        assertEquals(180_000L, candidate.evidence.observedSpanMillis)
    }

    @Test fun staleReplayCannotDestroyNewerReliableExitEvidence() {
        val near = evaluate().state!!
        val exit = evaluate(observation(2, firstTime + 120_000, distance = 200.0), previous = near).state!!
        val replay = evaluate(observation(), now = firstTime + 150_000, previous = exit)
        assertTrue(replay is ProbableEndResult.Ignored)
        assertEquals(exit, replay.state)
        val departure = evaluate(observation(3, firstTime + 180_000, distance = 200.0), previous = replay.state)
            as ProbableEndResult.ReliableDeparture
        assertEquals(2, departure.evidence.observationCount)
        assertEquals(60_000L, departure.evidence.observedSpanMillis)
    }

    @Test fun reevaluatingExpiredLatestFixCannotEmitCandidateOrManufactureDwell() {
        val candidate = sequence(listOf(0, 60_000, 120_000, 180_000)) as ProbableEndResult.QualifyingCandidate
        val replay = evaluate(observation(4, firstTime + 180_000), now = firstTime + 270_001,
            previous = candidate.state)
        assertTrue(replay is ProbableEndResult.Ignored)
        assertEquals(candidate.state, replay.state)
        val next = evaluate(observation(5, firstTime + 300_000), previous = replay.state) as ProbableEndResult.Reset
        assertEquals(ProbableEndReason.EVIDENCE_GAP, next.reason)
        assertEquals(1, next.state.proximity!!.count)
    }

    @Test fun nonAdvancingTimeCannotAdvanceEvenWithChangedCoordinate() {
        val state = evaluate().state!!
        val result = evaluate(observation(2, distance = 1.0), previous = state) as ProbableEndResult.Ignored
        assertEquals(ProbableEndReason.NON_ADVANCING_TIME, result.reason)
        assertEquals(1, result.state.proximity!!.count)
        val older = evaluate(observation(3, firstTime - 1), now = firstTime, previous = result.state) as ProbableEndResult.Ignored
        assertEquals(ProbableEndReason.NON_ADVANCING_TIME, older.reason)
    }

    @Test fun reusedSequenceWithNewTimeCannotAdvance() {
        val state = evaluate().state!!
        val result = evaluate(observation(1, firstTime + 60_000), previous = state) as ProbableEndResult.Ignored
        assertEquals(ProbableEndReason.DUPLICATE, result.reason)
        assertEquals(state.proximity, result.state.proximity)
    }

    @Test fun ninetySecondGapAcceptedButLargerGapResetsAndSeedsNewStreak() {
        assertTrue(sequence(listOf(0, 90_000, 180_000, 270_000)) is ProbableEndResult.QualifyingCandidate)
        val result = sequence(listOf(0, 90_001)) as ProbableEndResult.Reset
        assertEquals(ProbableEndReason.EVIDENCE_GAP, result.reason)
        assertEquals(1, result.state.proximity!!.count)
        assertEquals(firstTime + 90_001, result.state.proximity.first.observedAtMillis)
    }

    @Test fun poorAccuracyResetsExistingDwell() {
        val state = sequence(listOf(0, 60_000, 120_000)).state!!
        assertReset(ProbableEndReason.INVALID_ACCURACY, evaluate(observation(4, firstTime + 180_000, accuracy = 51f), previous = state))
    }

    @Test fun outsideFixResetsExistingDwell() {
        val state = sequence(listOf(0, 60_000, 120_000)).state!!
        assertReset(ProbableEndReason.OUTSIDE_PROXIMITY, evaluate(observation(4, firstTime + 180_000, distance = 100.0), previous = state))
    }

    @Test fun departureResetsProximityWhileStartingExitEvidence() {
        val state = sequence(listOf(0, 60_000, 120_000)).state!!
        val result = evaluate(observation(4, firstTime + 180_000, distance = 200.0), previous = state)
        assertNull(result.state!!.proximity)
        assertEquals(1, result.state!!.exit!!.count)
    }

    @Test fun staleFinalFixCannotQualify() {
        val state = sequence(listOf(0, 60_000, 120_000)).state!!
        assertReset(ProbableEndReason.STALE, evaluate(observation(4, firstTime + 180_000),
            now = firstTime + 270_001, previous = state))
    }

    @Test fun rejectedFutureTimestampDoesNotPoisonLaterValidTime() {
        val rejected = evaluate(observation(time = firstTime + 1_000_000), now = firstTime)
        val result = evaluate(observation(2, firstTime + 60_000), previous = rejected.state)
        assertTrue(result is ProbableEndResult.Accumulating)
    }

    @Test fun replayCannotRebuildStreakAfterReset() {
        val initial = evaluate().state!!
        val reset = evaluate(observation(2, firstTime + 60_000, accuracy = 100f), previous = initial)
        val replay = evaluate(observation().copy(sequence = 3), now = firstTime + 60_000, previous = reset.state)
        assertTrue(replay is ProbableEndResult.Ignored)
        assertNull(replay.state!!.proximity)
    }

    @Test fun subsequentCandidatesShareOneSequenceIdentity() {
        val first = sequence(listOf(0, 60_000, 120_000, 180_000)) as ProbableEndResult.QualifyingCandidate
        val next = evaluate(observation(5, firstTime + 240_000), previous = first.state) as ProbableEndResult.QualifyingCandidate
        assertEquals(first.evidence.sequenceKey, next.evidence.sequenceKey)
        assertEquals(5, next.evidence.observationCount)
        assertTrue(evaluate(observation(5, firstTime + 240_000), previous = next.state) is ProbableEndResult.Ignored)
    }

    @Test fun uncertaintyOverlapCannotStartExitStreak() = assertReset(ProbableEndReason.OUTSIDE_PROXIMITY,
        evaluate(observation(distance = 104.0, accuracy = 8f)))
    @Test fun oneExitFixInsufficient() {
        val result = sequence(listOf(0), distance = 200.0) as ProbableEndResult.Accumulating
        assertEquals(ProbableEndEvidenceKind.RELIABLE_EXIT, result.kind)
    }
    @Test fun twoExitFixesWithLessThanMinuteInsufficient() = assertTrue(
        sequence(listOf(0, 59_999), distance = 200.0) is ProbableEndResult.Accumulating)
    @Test fun twoFreshExitFixesSpanningMinuteEstablishReliableDeparture() {
        val result = sequence(listOf(0, 60_000), distance = 200.0) as ProbableEndResult.ReliableDeparture
        assertEquals(ProbableEndReason.RELIABLE_DEPARTURE, result.reason)
        assertEquals(ProbableEndEvidenceKind.RELIABLE_EXIT, result.evidence.kind)
        assertEquals(2, result.evidence.observationCount)
        assertEquals(60_000L, result.evidence.observedSpanMillis)
        assertNull(result.state.proximity)
    }
    @Test fun exitGapResets() {
        assertTrue(sequence(listOf(0, 90_000), distance = 200.0) is ProbableEndResult.ReliableDeparture)
        val result = sequence(listOf(0, 90_001), distance = 200.0) as ProbableEndResult.Reset
        assertEquals(ProbableEndReason.EVIDENCE_GAP, result.reason)
        assertEquals(1, result.state.exit!!.count)
    }
    @Test fun badAccuracyCannotEstablishExit() {
        val state = sequence(listOf(0), distance = 200.0).state!!
        assertReset(ProbableEndReason.INVALID_ACCURACY, evaluate(observation(2, firstTime + 60_000,
            distance = 200.0, accuracy = 51f), previous = state))
    }
    @Test fun exitDuplicateAndStaleFixCannotEstablishExit() {
        val state = sequence(listOf(0), distance = 200.0).state!!
        assertTrue(evaluate(observation(distance = 200.0).copy(sequence = 2), previous = state) is ProbableEndResult.Ignored)
        assertReset(ProbableEndReason.STALE, evaluate(observation(2, firstTime + 60_000, distance = 200.0),
            now = firstTime + 150_001, previous = state))
    }
    @Test fun returnClearsExitStreak() {
        val state = sequence(listOf(0), distance = 200.0).state!!
        val result = evaluate(observation(2, firstTime + 60_000), previous = state)
        assertNull(result.state!!.exit)
        assertEquals(1, result.state!!.proximity!!.count)
    }

    @Test fun unrelatedJourneyObservationFailsClosed() = assertReset(ProbableEndReason.JOURNEY_MISMATCH,
        evaluate(observation().copy(journeyId = "other")))
    @Test fun invalidSequenceFailsClosed() = assertReset(ProbableEndReason.INVALID_OBSERVATION_IDENTITY,
        evaluate(observation(sequence = 0)))

    @Test fun anotherJourneyCannotInheritAccumulation() {
        val state = sequence(listOf(0, 60_000, 120_000)).state!!
        val otherJourney = journey.copy(id = "journey-2")
        val result = evaluate(observation(4, firstTime + 180_000).copy(journeyId = otherJourney.id),
            previous = state, input = ProbableEndJourney.from(otherJourney))
        assertEquals(1, result.state!!.proximity!!.count)
    }

    @Test fun policyVersionCannotSilentlyReuseOldState() {
        val state = sequence(listOf(0, 60_000, 120_000)).state!!.copy(policyVersion = "old-policy")
        val result = evaluate(observation(4, firstTime + 180_000), previous = state)
        assertEquals(1, result.state!!.proximity!!.count)
    }

    @Test fun connectivityDoesNotGateLocalEvidence() {
        ConnectivityState.entries.forEach { connection ->
            var state: ProbableEndDetectorState? = null
            var result: ProbableEndResult? = null
            (0..3).forEach { index ->
                result = evaluate(observation(index + 1L, firstTime + index * 60_000L).copy(connectivity = connection), previous = state)
                state = result.state
            }
            assertTrue(result is ProbableEndResult.QualifyingCandidate)
        }
    }

    @Test fun decisionIsDeterministicAndToStringContainsNoRawCoordinates() {
        val result = sequence(listOf(0, 60_000, 120_000, 180_000)) as ProbableEndResult.QualifyingCandidate
        assertEquals(result, sequence(listOf(0, 60_000, 120_000, 180_000)))
        val outputs = listOf(input, result, result.state, result.evidence, result.evidence.destination,
            result.evidence.first, result.evidence.final, result.state.proximity,
            sequence(listOf(0, 60_000), distance = 200.0), evaluate(observation(accuracy = 100f)))
        outputs.forEach {
            assertFalse(it.toString().contains(point.latitude.toString()))
            assertFalse(it.toString().contains(point.longitude.toString()))
            assertFalse(it.toString().contains("provider-place"))
        }
    }
}
