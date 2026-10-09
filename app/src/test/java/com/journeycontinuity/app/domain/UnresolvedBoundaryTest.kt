package com.journeycontinuity.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UnresolvedBoundaryTest {
    private val start = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
    private val admission = BoundaryAdmission("20000000-0000-4000-8000-000000000010",
        "30000000-0000-4000-8000-000000000010", start, start + 3_600_000,
        start + 3_600_000 + JourneyEtaPolicy.UNRESOLVED_WINDOW_MILLIS)

    @Test fun pastAndEqualEtaRejected() {
        assertEquals(JourneyEtaPolicy.Validation.NOT_FUTURE, JourneyEtaPolicy.validate(start, start - 1))
        assertEquals(JourneyEtaPolicy.Validation.NOT_FUTURE, JourneyEtaPolicy.validate(start, start))
    }
    @Test fun shortAndExactly72HoursValid() {
        assertEquals(JourneyEtaPolicy.Validation.VALID, JourneyEtaPolicy.validate(start, start + 1))
        assertEquals(JourneyEtaPolicy.Validation.VALID, JourneyEtaPolicy.validate(start, start + 259_200_000))
        assertEquals(start + 345_600_000, JourneyEtaPolicy.boundary(start, start + 259_200_000))
    }
    @Test fun over72HoursAndYear2030Rejected() {
        assertEquals(JourneyEtaPolicy.Validation.OUT_OF_RANGE, JourneyEtaPolicy.validate(start, start + 259_200_001))
        assertEquals(JourneyEtaPolicy.Validation.OUT_OF_RANGE,
            JourneyEtaPolicy.validate(start, Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()))
    }
    @Test fun checkedOverflowRejected() {
        assertEquals(JourneyEtaPolicy.Validation.OUT_OF_RANGE, JourneyEtaPolicy.validate(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(JourneyEtaPolicy.Validation.OUT_OF_RANGE, JourneyEtaPolicy.validate(-1, 1))
    }
    @Test fun exact24HoursIndependentOfDst() {
        val spring = Instant.parse("2026-03-08T06:00:00Z").toEpochMilli()
        assertEquals(spring + 86_400_000, JourneyEtaPolicy.boundary(spring - 1, spring))
    }
    @Test fun delayedOfflineAdmissionUsesOriginalStartNotReceipt() {
        assertTrue(admission.valid()) // ETA now in past relative to receipt, still valid original admission.
        assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF,
            boundaryProof(admission, admission.boundaryMillis + 1))
    }
    @Test fun missingEarlierAndJustBeforeObservationsInsufficient() {
        listOf(null, start, admission.boundaryMillis - 1).forEach {
            assertEquals(BoundaryProof.INSUFFICIENT_PROOF, boundaryProof(admission, it))
        }
    }
    @Test fun exactAndLaterObservationsEstablishFact() {
        listOf(admission.boundaryMillis, admission.boundaryMillis + 1).forEach {
            assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF, boundaryProof(admission, it))
        }
    }
    @Test fun noWallClockOrMonotonicExtrapolation() {
        // Advancing receipt clocks, including an unbounded fast rate, cannot advance server proof.
        val earlier = admission.boundaryMillis - 1
        listOf(0L, 86_400_000L, Long.MAX_VALUE).forEach { elapsed ->
            BoundaryReceipt(elapsed, 1, "install")
            assertEquals(BoundaryProof.INSUFFICIENT_PROOF, boundaryProof(admission, earlier))
        }
    }
    @Test fun rebootInvalidatesTentativeMappingButNotEstablishedFact() {
        val old = BoundaryReceipt(100, 1, "install")
        assertTrue(old.sameBoot(BoundaryReceipt(200, 1, "install")))
        assertFalse(old.sameBoot(BoundaryReceipt(200, 2, "install")))
        assertFalse(old.sameBoot(BoundaryReceipt(50, 1, "install")))
        assertFalse(old.sameBoot(BoundaryReceipt(200, null, "install")))
        assertFalse(old.sameBoot(BoundaryReceipt(200, 1, "another")))
        assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF, boundaryProof(admission, admission.boundaryMillis))
    }
    @Test fun corruptAndMissingProvenanceCannotProveBoundary() {
        listOf(admission.copy(boundaryMillis = start), admission.copy(policyVersion = "wrong"),
            admission.copy(admissionId = "not-uuid")).forEach {
            assertEquals(BoundaryProof.INSUFFICIENT_PROOF, boundaryProof(it, Long.MAX_VALUE))
        }
    }
}
