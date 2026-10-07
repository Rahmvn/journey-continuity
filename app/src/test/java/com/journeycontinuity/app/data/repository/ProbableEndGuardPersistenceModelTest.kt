package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.domain.probableend.*
import org.junit.Assert.*
import org.junit.Test

class ProbableEndGuardPersistenceModelTest {
    private fun binding() = ProbableEndGuardBindingEntity("journey", "BOUND", 1000, 7, 2000,
        4, 2000, 1, 0, 2000, 1000, 302000, "CLEAR", "CLEAR", "VERIFICATION_CLEAR")
    private fun candidate() = ProbableEndEpisodeEntity("candidate", "journey", ProbableEndPolicy.VERSION,
        createdAtMillis = 3000, generation = 1, evidenceFloorSequence = 4, evidenceFloorEventTimeMillis = 2000,
        first = ObservationEvidenceReference(5, 3000, "a".repeat(64)),
        final = ObservationEvidenceReference(8, 183000, "b".repeat(64)), observationCount = 4,
        finalDistanceMeters = 0.0, finalAccuracyMeters = 8.0, destinationSlot = DestinationEvidenceReference("journey").slot)
    @Test fun onlyEvidenceStrictlyAfterBoundFloorsAttachesToBaseline() {
        val candidate = candidate()
        assertEquals("PENDING_GUARD", candidate.continuity(binding()).status)
        assertEquals(7L, candidate.continuity(binding()).attachedGuardVersion)
        assertEquals("UNBOUND", candidate.copy(first = candidate.first.copy(observedAtMillis = 2000)).continuity(binding()).status)
        assertEquals("UNBOUND", candidate.copy(first = candidate.first.copy(telemetrySequence = 4)).continuity(binding()).status)
        assertEquals("UNBOUND", candidate.continuity(null).status)
        assertEquals("UNBOUND", candidate.continuity(binding().copy(journeyId = "other")).status)
    }
    @Test fun corruptPersistedReceiptsCannotBecomeUsableBaselines() {
        for (row in listOf(binding().copy(guardVersion = -1), binding().copy(status = "synthetic"),
            binding().copy(establishedServerAtMillis = 0), binding().copy(evidenceFloorEventTimeMillis = 0),
            binding().copy(lastFreshUntilMillis = null), binding().copy(historyBaselineAtMillis = 3000))) {
            assertEquals("UNBOUND", candidate().continuity(row).status)
            try { row.receipt(); fail("Corrupt receipt accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun newPersistenceDoesNotRenderPrivateFieldsOrKeepCoordinates() {
        assertEquals("ProbableEndGuardBindingEntity(redacted)", binding().toString())
        assertEquals("ProbableEndCandidateContinuityEntity(redacted)", candidate().continuity(binding()).toString())
        for (type in listOf(ProbableEndGuardBindingEntity::class.java, ProbableEndCandidateContinuityEntity::class.java))
            assertFalse(type.declaredFields.any { it.name.contains("latitude", true) || it.name.contains("longitude", true) ||
                it.name.contains("provider", true) || it.name.contains("token", true) || it.name.contains("destination", true) })
    }
}
