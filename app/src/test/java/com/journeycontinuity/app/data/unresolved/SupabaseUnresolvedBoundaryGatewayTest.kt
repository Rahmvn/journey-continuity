package com.journeycontinuity.app.data.unresolved

import com.journeycontinuity.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SupabaseUnresolvedBoundaryGatewayTest {
    private val id = "20000000-0000-4000-8000-000000000010"
    private val owner = "10000000-0000-4000-8000-000000000010"
    private fun payload(decision: String = "ELIGIBLE", reason: String = "CURRENT_UNRESOLVED") = """[{
        "journey_id":"$id", "owner_id":"$owner", "admission_id":"30000000-0000-4000-8000-000000000010",
        "policy_version":"alabarin-unresolved-boundary-v1", "admitted_at":"2026-10-07T09:00:00Z",
        "original_eta":"2026-10-08T09:00:00Z", "boundary_at":"2026-10-09T09:00:00Z",
        "accepted_at":"2026-10-08T12:00:00Z", "evaluated_at":"2026-10-09T09:00:00Z",
        "decision":"$decision", "reason":"$reason", "verification_case_id":"40000000-0000-4000-8000-000000000010",
        "opening_event_id":1, "verifying_started_at":"2026-10-09T08:00:00Z",
        "guard_version":1, "history_baseline_at":"2026-10-09T08:00:00Z"}]"""
    @Test fun exactCoherentCurrentEpisodeMapped() = runBlocking {
        val value = SupabaseUnresolvedBoundaryGateway({ owner }, { payload() }).read(id)!!
        assertEquals(UnresolvedEligibility.ELIGIBLE, value.eligibility)
        assertEquals("40000000-0000-4000-8000-000000000010", value.verificationCaseId)
    }
    @Test fun incoherentPositivePayloadRejected() = runBlocking {
        assertNull(SupabaseUnresolvedBoundaryGateway({ owner }, {
            payload().replace("\"opening_event_id\":1", "\"opening_event_id\":null") }).read(id))
        assertNull(SupabaseUnresolvedBoundaryGateway({ owner }, {
            payload(reason = "NOT_VERIFYING") }).read(id))
    }
    @Test fun wrongJourneyAndOwnerRejected() = runBlocking {
        assertNull(SupabaseUnresolvedBoundaryGateway({ "other" }, { payload() }).read(id))
        assertNull(SupabaseUnresolvedBoundaryGateway({ owner }, { payload().replace(id, owner) }).read(id))
    }
    @Test fun unavailableAuthAndNetworkRemainUnknown() = runBlocking {
        assertNull(SupabaseUnresolvedBoundaryGateway({ null }, { error("not called") }).read(id))
        assertNull(SupabaseUnresolvedBoundaryGateway({ owner }, { error("unavailable") }).read(id))
    }
    @Test fun ownerChangeDuringRequestRejected() = runBlocking {
        var identity = owner
        assertNull(SupabaseUnresolvedBoundaryGateway({ identity }, { identity = "changed"; payload() }).read(id))
    }
    @Test fun resolvedInconsistentAndBeforeBoundaryAreNotLeases() = runBlocking {
        listOf("NOT_VERIFYING", "BOUNDARY_NOT_REACHED", "SUPERSEDED_EPISODE").forEach { reason ->
            assertEquals(UnresolvedEligibility.NOT_ELIGIBLE,
                SupabaseUnresolvedBoundaryGateway({ owner }, { payload("NOT_ELIGIBLE", reason) }).read(id)!!.eligibility)
        }
        assertEquals(UnresolvedEligibility.UNKNOWN,
            SupabaseUnresolvedBoundaryGateway({ owner }, { payload("UNKNOWN", "INCONSISTENT_STATE") }).read(id)!!.eligibility)
    }
    @Test fun malformedAndDuplicateRowsRejected() = runBlocking {
        assertNull(SupabaseUnresolvedBoundaryGateway({ owner }, { "[]" }).read(id))
        assertNull(SupabaseUnresolvedBoundaryGateway({ owner }, { "invalid" }).read(id))
        assertNull(SupabaseUnresolvedBoundaryGateway({ owner }, { "[${payload().trim('[',']')},${payload().trim('[',']')}]" }).read(id))
    }
    @Test(expected = CancellationException::class) fun cancellationPreserved() = runBlocking {
        SupabaseUnresolvedBoundaryGateway({ owner }, { throw CancellationException() }).read(id)
        Unit
    }
}
