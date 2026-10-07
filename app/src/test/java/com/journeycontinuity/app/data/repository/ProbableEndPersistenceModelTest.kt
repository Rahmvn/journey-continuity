package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import org.junit.Assert.*
import org.junit.Test

class ProbableEndPersistenceModelTest {
    private val start = 1_700_000_000_000L
    private val journey = Journey("test", "Private label", start + 3_600_000, start, JourneyStatus.ACTIVE, null,
        structuredDestination = DestinationEvidenceFixtures.mapPin())
    private fun accumulated(): ProbableEndDetectorEntity {
        val point = journey.structuredDestination!!
        val observation = TelemetryObservation(1, journey.id, 1, start + 60_000, point.latitude!!,
            point.longitude!!, 8f, null, null, ConnectivityState.NONE)
        val state = ProbableEndDetector.evaluate(ProbableEndJourney.from(journey), observation, observation.eventTime).state!!
        return ProbableEndDetectorEntity(journey.id, start, lastEvaluationAtMillis = observation.eventTime)
            .withDetector(state, observation.eventTime)
    }
    @Test fun persistedStreakRestoresExactlyWithoutTimeAdvancement() {
        val entity = accumulated()
        assertEquals(1, entity.restore().proximity!!.count)
        assertEquals(0L, entity.restore().proximity!!.observedSpanMillis)
        assertEquals(entity.restore(), entity.restore())
        assertEquals(1L, entity.restore().highestTelemetrySequence)
    }
    @Test fun malformedCountsAndFingerprintsFailClosed() {
        val row = accumulated()
        listOf(row.copy(proximity = row.proximity!!.copy(count = -1)),
            row.copy(proximity = row.proximity.copy(firstFingerprint = "invalid")),
            row.copy(consumedSequence = 0), row.copy(policyVersion = "other-policy"),
            row.copy(exit = row.proximity), row.copy(evidenceFloorSequence = 1),
            row.copy(proximity = null, evidenceFloorEventTimeMillis = Long.MAX_VALUE)).forEach {
            try { it.restore(); fail("Invalid state accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun malformedDestinationIsIneligibleWithoutChangingSharedDomainTruth() {
        val raw = journey.toEntity().copy(destinationLongitude = null)
        assertEquals(ProbableEndReason.INCOMPLETE_DESTINATION, raw.probableEndInput().ineligibleReason)
        with(raw) {
            assertEquals(journey.id, id); assertEquals(journey.destination, destination)
            assertEquals(JourneyStatus.ACTIVE, status); assertEquals("POINT", destinationKind)
            assertNull(destinationLongitude)
        }
        try { raw.toDomain(); fail("Shared mapper must remain strict") } catch (_: IllegalArgumentException) { }
    }
    @Test fun PartialLegacyEvidenceIsNotMisclassifiedAsLegacy() {
        val raw = journey.copy(structuredDestination = null).toEntity().copy(destinationStateId = "ng-kwara")
        assertEquals(ProbableEndReason.INCOMPLETE_DESTINATION, raw.probableEndInput().ineligibleReason)
        try { raw.toDomain(); fail("Partial facts must not become legacy") } catch (_: IllegalStateException) { }
    }
    @Test fun genuineLegacyAndLocalityRemainIneligible() {
        assertEquals(ProbableEndReason.LEGACY_DESTINATION,
            journey.copy(structuredDestination = null).toEntity().probableEndInput().ineligibleReason)
        assertEquals(ProbableEndReason.LOCALITY,
            journey.copy(structuredDestination = DestinationEvidenceFixtures.locality()).toEntity().probableEndInput().ineligibleReason)
    }
    @Test fun validDestinationStillMapsExactly() = assertEquals(journey, journey.toEntity().toDomain())
    @Test fun persistenceRenderingDoesNotExposeCoordinatesOrLabels() {
        val row = accumulated()
        val first = row.lastObservation!!
        val episode = ProbableEndEpisodeEntity("episode", journey.id, ProbableEndPolicy.VERSION,
            createdAtMillis = first.observedAtMillis + 180_000, generation = 0,
            evidenceFloorSequence = 0, evidenceFloorEventTimeMillis = 0,
            first = first, final = first.copy(telemetrySequence = 4, observedAtMillis = first.observedAtMillis + 180_000),
            observationCount = 4, finalDistanceMeters = 0.0, finalAccuracyMeters = 8.0,
            destinationSlot = DestinationEvidenceReference(journey.id).slot)
        listOf(row, row.proximity, episode, episode.evidence()).forEach {
            val text = it.toString()
            assertFalse(text.contains(journey.destination))
            assertFalse(text.contains(journey.structuredDestination!!.latitude.toString()))
            assertFalse(text.contains(journey.structuredDestination.longitude.toString()))
        }
    }
}
