package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.domain.JourneyStatus
import org.junit.Assert.*
import org.junit.Test
import com.journeycontinuity.app.domain.IncidentClaim
import com.journeycontinuity.app.domain.IncidentProvenance
import com.journeycontinuity.app.domain.JourneyEtaPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class TravellerAuthorityPresentationTest {
    private val id = "20000000-0000-4000-8000-000000000204"
    private val journey = JourneyEntity(id, "Ilorin", 1_790_003_600_000L,
        1_790_000_000_000L, JourneyStatus.ACTIVE, null, 1)
    private fun record(reason: String = "APPROXIMATE_MONOTONIC_CAP",
        reconciliation: JourneyTerminalReconciliationEntity? = null) = TravellerAuthorityRecord(
        journey, OperationalBoundaryEntity(id, "30000000-0000-4000-8000-000000000204",
            state = "PENDING_AUTHORITY", pauseReason = reason), reconciliation, null, null, emptyList())

    @Test fun approvedBoundaryCopyRequiresVerificationAtBoundary() {
        val eta = Instant.ofEpochMilli(journey.expectedArrivalAt)
        val boundary = eta.plusSeconds(86_400)
        for (offset in listOf(-1L, 0L, 1L)) {
            val episodeAt = boundary.plusSeconds(offset).toString()
            val finalized = boundary.plusSeconds(60)
            val proof = IncidentProvenance(IncidentClaim(id,
                "10000000-0000-4000-8000-000000000204", "30000000-0000-4000-8000-000000000204",
                JourneyEtaPolicy.VERSION, null, eta.toString(), boundary.toString(),
                "40000000-0000-4000-8000-000000000204", 1, episodeAt, 1, episodeAt),
                finalized.toString(), finalized.plusSeconds(604_800).toString(), "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
            proof.validate()
            val result = UnresolvedJourneyResult(id, journey.destination, journey.expectedArrivalAt,
                finalized.toString(), Json.encodeToString(proof))
            assertEquals(offset <= 0, result.boundaryExplanation().contains("24 hours after"))
            if (offset > 0) assertTrue(result.boundaryExplanation().contains("when the server finalized it"))
        }
    }

    @Test fun pendingCompleteIsNeverSelectedAsTerminalResult() {
        val pending = record(reconciliation = JourneyTerminalReconciliationEntity(id,
            explicitAction = "COMPLETE", explicitState = "PENDING"))
            .copy(journey = journey.copy(status = JourneyStatus.COMPLETED, completedAt = journey.startedAt + 1))
        assertTrue(pending.isPendingAuthority())
        assertNull(selectTravellerTerminalResult(listOf(pending)))
    }

    @Test fun offlineTimingAndAuthorityAreIndependentFacts() {
        val copy = pendingAuthorityCopy(record(), offline = true)
        assertTrue(copy.timing.contains("local operational limit"))
        assertTrue(copy.authority.contains("Offline"))
        assertNull(copy.intent)
        assertTrue(record().canRecordExplicitAction())
        assertNull(record().unresolvedResult())
    }

    @Test fun uncertaintyNeverGrantsAnExplicitActionOverAClaim() {
        val row = record(reconciliation = JourneyTerminalReconciliationEntity(id,
            claimState = "PENDING", claimDispatch = "MAY_HAVE_DISPATCHED"))
        assertFalse(row.canRecordExplicitAction())
        assertTrue(pendingAuthorityCopy(row).authority.contains("acknowledgement is uncertain"))
        val offline = pendingAuthorityCopy(row, offline = true).authority
        assertTrue(offline.contains("Offline"))
        assertTrue(offline.contains("acknowledgement is uncertain"))
    }

    @Test fun completeAndStopIntentsHaveDifferentUnacknowledgedCopy() {
        val complete = record(reconciliation = JourneyTerminalReconciliationEntity(id,
            explicitAction = "COMPLETE", explicitState = "PENDING"))
        val stop = record(reconciliation = JourneyTerminalReconciliationEntity(id,
            explicitAction = "STOP", explicitState = "PENDING"))
        assertFalse(complete.canRecordExplicitAction())
        assertFalse(stop.canRecordExplicitAction())
        assertTrue(pendingAuthorityCopy(complete).intent!!.contains("Complete intent"))
        assertTrue(pendingAuthorityCopy(stop).intent!!.contains("Stop intent"))
        assertTrue(pendingAuthorityCopy(stop).intent!!.contains("not confirmed cancelled"))
    }

    @Test fun serverRefusalsDoNotBecomeFinalResults() {
        for (outcome in listOf("TOO_EARLY", "NO_CURRENT_VERIFICATION", "SUPERSEDED_EPISODE",
            "FINAL_GUARD_BLOCKED", "UNAVAILABLE", "AUTHENTICATION_REQUIRED", "INCONSISTENT")) {
            val row = record(reconciliation = JourneyTerminalReconciliationEntity(id, lastOutcome = outcome))
            assertNull(row.unresolvedResult())
            assertNotEquals("The final Journey outcome is awaiting authoritative confirmation.",
                pendingAuthorityCopy(row).authority)
        }
    }
}
