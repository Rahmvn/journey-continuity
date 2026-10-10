package com.journeycontinuity.app.data.unresolved

import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.SyncFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SupabaseIncidentAuthorityGatewayTest {
    private val id = "20000000-0000-4000-8000-000000000010"
    private val owner = "10000000-0000-4000-8000-000000000010"
    private val admission = "30000000-0000-4000-8000-000000000010"
    private val case = "40000000-0000-4000-8000-000000000010"
    private val started = "2026-10-09T08:00:00.123456Z"
    private fun claim() = IncidentClaim(id, owner, admission, JourneyEtaPolicy.VERSION,
        "2026-10-07T09:00:00Z", "2026-10-08T09:00:00Z", "2026-10-09T09:00:00Z",
        case, 1, started, 1, started)
    private fun eligibility(decision: String = "ELIGIBLE", reason: String = "CURRENT_UNRESOLVED") = """[{
        "journey_id":"$id","owner_id":"$owner","admission_id":"$admission",
        "policy_version":"${JourneyEtaPolicy.VERSION}","admitted_at":"2026-10-07T09:00:00Z",
        "original_eta":"2026-10-08T09:00:00Z","boundary_at":"2026-10-09T09:00:00Z",
        "accepted_at":"2026-10-08T12:00:00Z","evaluated_at":"2026-10-09T09:00:00Z",
        "decision":"$decision","reason":"$reason","verification_case_id":"$case",
        "opening_event_id":1,"verifying_started_at":"$started","guard_version":1,
        "history_baseline_at":"$started"}]"""
    private fun provenance() = """[{
        "decision":"UNRESOLVED_INCIDENT","journey_status":"UNRESOLVED_INCIDENT",
        "ended_at":"2026-10-09T09:01:00Z","boundary_at":"2026-10-09T09:00:00Z",
        "verification_case_id":"$case","access_expires_at":"2026-10-16T09:01:00Z",
        "journey_id":"$id","owner_id":"$owner","admission_id":"$admission",
        "policy_version":"${JourneyEtaPolicy.VERSION}","original_eta":"2026-10-08T09:00:00Z",
        "opening_event_id":1,"verifying_started_at":"$started","guard_version":1,
        "history_baseline_at":"$started","finalized_at":"2026-10-09T09:01:00Z",
        "source":"AUTHORITATIVE_UNRESOLVED_FINALIZER_V1"}]"""

    @Test fun currentEligibilityPreservesMicrosecondEpisodeIdentity() = runBlocking {
        val value = SupabaseIncidentAuthorityGateway({ owner }, { name, body ->
            assertEquals("get_unresolved_boundary_eligibility_v1", name)
            assertEquals(setOf("p_journey_id"), body.keys); eligibility()
        }).eligibility(id) as AuthorityRead.Value
        assertEquals(started, value.value.claim!!.verifyingStartedAt)
        assertEquals(started, value.value.claim!!.historyBaselineAt)
    }
    @Test fun finalizerUsesExactDeployedArgumentsAndNoPrivateEvidence() = runBlocking {
        var sent: JsonObject? = null
        val response = SupabaseIncidentAuthorityGateway({ owner }, { name, body ->
            assertEquals("finalize_unresolved_incident_v1", name); sent = body
            """[{"decision":"UNRESOLVED_INCIDENT","journey_status":"UNRESOLVED_INCIDENT",
                "ended_at":"2026-10-09T09:01:00Z","boundary_at":"2026-10-09T09:00:00Z",
                "verification_case_id":"$case","access_expires_at":"2026-10-16T09:01:00Z"}]"""
        }).finalize(claim())
        assertTrue(response is AuthorityRead.Value)
        assertEquals(started, sent!!["p_verifying_started_at"]!!.jsonPrimitive.content)
        assertEquals(started, sent!!["p_history_baseline_at"]!!.jsonPrimitive.content)
        assertEquals(9, sent!!.size)
        assertFalse(sent.toString().contains("latitude"))
    }
    @Test fun ownerReadNeedsNoOriginalClaimAndDoesNotInventAdmissionTime() = runBlocking {
        val read = SupabaseIncidentAuthorityGateway({ owner }, { name, body ->
            assertEquals("get_owner_unresolved_incident_finalization_v1", name)
            assertEquals(setOf("p_journey_id"), body.keys); provenance()
        }).provenance(id) as AuthorityRead.Value
        assertNull(read.value.claim.admittedAt)
        assertTrue(read.value.claim.sameEpisode(claim()))
        assertTrue(read.value.claim.sameEpisode(claim().copy(verifyingStartedAt = started.replace("Z", "+00:00"))))
        read.value.validate()
    }
    @Test fun wrongIdentityAndOwnerChangeFailClosed() = runBlocking {
        assertTrue(SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> provenance().replace(id, owner) })
            .provenance(id) is AuthorityRead.Failed)
        var identity = owner
        assertTrue(SupabaseIncidentAuthorityGateway({ identity }, { _, _ -> identity = id; provenance() })
            .provenance(id) is AuthorityRead.Failed)
    }
    @Test fun incompleteProvenanceOrChangedExpiryNeverBecomesIncidentTruth() = runBlocking {
        listOf("[]", "invalid", provenance().replace("2026-10-16T09:01:00Z", "2026-10-17T09:01:00Z"),
            provenance().replace("\"opening_event_id\":1", "\"opening_event_id\":null"),
            provenance().replace("2026-10-09T09:01:00Z", "2026-10-09T08:00:00Z")).forEach { payload ->
            assertTrue(SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> payload }).provenance(id) is AuthorityRead.Failed)
        }
    }
    @Test fun nonEligibleAndInconsistentFactsStayTypedWithoutClaim() = runBlocking {
        listOf("NOT_VERIFYING", "BOUNDARY_NOT_REACHED", "SUPERSEDED_EPISODE").forEach { reason ->
            val read = SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> eligibility("NOT_ELIGIBLE", reason) })
                .eligibility(id) as AuthorityRead.Value
            assertNull(read.value.claim)
            assertEquals(UnresolvedReason.valueOf(reason), read.value.reason)
        }
        assertTrue(SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> eligibility(reason = "NOT_VERIFYING") })
            .eligibility(id) is AuthorityRead.Failed)
        assertTrue(SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> eligibility().replace(
            "\"accepted_at\":\"2026-10-08T12:00:00Z\"", "\"accepted_at\":null") })
            .eligibility(id) is AuthorityRead.Failed)
    }
    @Test fun tooEarlyAndBlockedAreActualRefusals() = runBlocking {
        listOf("TOO_EARLY", "BLOCKED").forEach { decision ->
            val read = SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> """[{"decision":"$decision",
                "journey_status":"ACTIVE","ended_at":null,"boundary_at":"2026-10-09T09:00:00Z",
                "verification_case_id":null,"access_expires_at":null}]""" }).finalize(claim()) as AuthorityRead.Value
            assertEquals(decision, read.value.decision)
        }
    }
    @Test fun authenticationNetworkAndMalformedFailuresDiffer() = runBlocking {
        val auth = SupabaseIncidentAuthorityGateway({ throw CloudSyncException(SyncFailureKind.AUTHENTICATION, "Expired") },
            { _, _ -> error("must not call") }).provenance(id) as AuthorityRead.Failed
        assertEquals(IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED, auth.outcome)
        val offline = SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> throw IOException() }).provenance(id) as AuthorityRead.Failed
        assertEquals(IncidentReconciliationOutcome.UNAVAILABLE, offline.outcome)
        val malformed = SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> "[]" }).provenance(id) as AuthorityRead.Failed
        assertEquals(IncidentReconciliationOutcome.INCONSISTENT, malformed.outcome)
    }
    @Test(expected = CancellationException::class) fun cancellationPreservesTheDurableUnknownClaim() = runBlocking {
        SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> throw CancellationException() }).finalize(claim()); Unit
    }

    private fun autoProvenance() = """[{
        "decision":"AUTO_ENDED","presentation_episode_id":"$admission","journey_status":"AUTO_ENDED",
        "ended_at":"2026-10-09T09:01:00.654321+00:00","not_before_at":"2026-10-09T09:00:00.123456+00:00",
        "journey_id":"$id","owner_id":"$owner","candidate_episode_id":"$case","detector_generation":17,
        "policy_version":"alabarin-probable-end-v1","history_baseline_at":"$started","guard_version":9,
        "registered_at":"2026-10-09T08:30:00.123456+00:00","finalized_at":"2026-10-09T09:01:00.654321+00:00"}]"""

    @Test fun automaticOwnerReadMapsAllFourteenFieldsWithoutAnOriginalLocalClaim() = runBlocking {
        val value = SupabaseIncidentAuthorityGateway({ owner }, { name, body ->
            assertEquals("get_owner_probable_end_auto_end_finalization_v1", name)
            assertEquals(setOf("p_journey_id"), body.keys)
            assertEquals(id, body["p_journey_id"]!!.jsonPrimitive.content)
            autoProvenance()
        }).autoEndProvenance(id) as AuthorityRead.Value
        val r = value.value
        assertEquals("AUTO_ENDED", r.decision); assertEquals("AUTO_ENDED", r.journeyStatus)
        assertEquals(id, r.journeyId); assertEquals(owner, r.ownerId)
        assertEquals(admission, r.presentationEpisodeId); assertEquals(case, r.candidateEpisodeId)
        assertEquals(17L, r.detectorGeneration); assertEquals(9L, r.guardVersion)
        assertEquals("alabarin-probable-end-v1", r.policyVersion); assertEquals(started, r.historyBaselineAt)
        assertEquals("2026-10-09T08:30:00.123456+00:00", r.registeredAt)
        assertEquals("2026-10-09T09:00:00.123456+00:00", r.notBeforeAt)
        assertEquals("2026-10-09T09:01:00.654321+00:00", r.finalizedAt); assertEquals(r.finalizedAt, r.endedAt)
        r.validate()
        assertTrue(r.sameFacts(r.copy(endedAt = r.endedAt.replace("+00:00", "Z"),
            finalizedAt = r.finalizedAt.replace("+00:00", "Z"))))
    }

    @Test fun automaticReadWrongOwnerJourneyOrChangedSessionFailsClosed() = runBlocking {
        listOf(autoProvenance().replace(id, admission), autoProvenance().replace(owner, admission)).forEach { json ->
            assertTrue(SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> json }).autoEndProvenance(id) is AuthorityRead.Failed)
        }
        var sessionOwner = owner
        assertTrue(SupabaseIncidentAuthorityGateway({ sessionOwner }, { _, _ ->
            sessionOwner = admission; autoProvenance()
        }).autoEndProvenance(id) is AuthorityRead.Failed)
    }

    @Test fun automaticReadMissingAmbiguousNonfiniteAndInconsistentFactsAreRejected() = runBlocking {
        listOf("[]", "invalid", autoProvenance().replace("\"detector_generation\":17", "\"detector_generation\":-1"),
            autoProvenance().replace("08:30:00.123456", "08:31:00.123456"),
            autoProvenance().replace("\"AUTO_ENDED\"", "\"COMPLETED\""),
            autoProvenance().replace("2026-10-09T09:01:00.654321+00:00", "infinity"),
            autoProvenance().replace("\"guard_version\":9", "\"guard_version\":null"),
            autoProvenance().replace("\"finalized_at\":\"2026-10-09T09:01:00.654321+00:00\"",
                "\"finalized_at\":\"2026-10-09T09:01:00.654322+00:00\""),
            "[" + autoProvenance().removePrefix("[").removeSuffix("]") + "," +
                autoProvenance().removePrefix("[").removeSuffix("]") + "]").forEach { json ->
            assertTrue(SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> json }).autoEndProvenance(id) is AuthorityRead.Failed)
        }
    }

    @Test fun automaticReadAuthenticationOfflineAndMalformedFailuresAreTyped() = runBlocking {
        val session = SupabaseIncidentAuthorityGateway({ throw CloudSyncException(SyncFailureKind.AUTHENTICATION, "Expired") },
            { _, _ -> error("No unauthenticated RPC") }).autoEndProvenance(id) as AuthorityRead.Failed
        assertEquals(IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED, session.outcome)
        val denied = SupabaseIncidentAuthorityGateway({ owner }, { _, _ ->
            throw CloudSyncException(SyncFailureKind.AUTHORIZATION, "Denied") }).autoEndProvenance(id) as AuthorityRead.Failed
        assertEquals(IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED, denied.outcome)
        val offline = SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> throw IOException() })
            .autoEndProvenance(id) as AuthorityRead.Failed
        assertEquals(IncidentReconciliationOutcome.UNAVAILABLE, offline.outcome)
        val missing = SupabaseIncidentAuthorityGateway({ owner }, { _, _ -> "[]" }).autoEndProvenance(id) as AuthorityRead.Failed
        assertEquals(IncidentReconciliationOutcome.INCONSISTENT, missing.outcome)
    }
}
