package com.journeycontinuity.app.data.unresolved

import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.sync.CloudStage
import com.journeycontinuity.app.sync.SyncFailureKind
import com.journeycontinuity.app.sync.toCloudSyncException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AuthorityRead<out T> {
    data class Value<T>(val value: T) : AuthorityRead<T>
    data class Failed(val outcome: IncidentReconciliationOutcome) : AuthorityRead<Nothing>
}
data class IncidentEligibility(val eligibility: UnresolvedEligibility, val reason: UnresolvedReason,
                               val claim: IncidentClaim?)
data class IncidentFinalizerResponse(val decision: String, val status: JourneyStatus,
    val endedAt: String?, val boundaryAt: String?, val caseId: String?, val accessExpiresAt: String?)

interface IncidentAuthorityGateway {
    suspend fun eligibility(journeyId: String): AuthorityRead<IncidentEligibility>
    suspend fun finalize(claim: IncidentClaim, beforeDispatch: suspend () -> Boolean = { true }): AuthorityRead<IncidentFinalizerResponse>
    suspend fun provenance(journeyId: String): AuthorityRead<IncidentProvenance>
    suspend fun autoEndProvenance(journeyId: String): AuthorityRead<AutoEndProvenance> =
        AuthorityRead.Failed(IncidentReconciliationOutcome.INCONSISTENT)
}

/** Ordinary established Traveller JWT only. Original SQL timestamps survive every retry unchanged. */
class SupabaseIncidentAuthorityGateway internal constructor(
    private val requireOwner: suspend () -> String,
    private val request: suspend (String, JsonObject) -> String,
) : IncidentAuthorityGateway {
    constructor(client: SupabaseClient, identity: TravellerIdentityCoordinator) : this(
        { identity.requireAuthenticatedTraveller().userId },
        { name, body -> client.postgrest.rpc(name, body).data },
    )

    override suspend fun eligibility(journeyId: String): AuthorityRead<IncidentEligibility> = call { owner ->
        uuid(journeyId)
        val row = Json.decodeFromString<List<EligibilityRow>>(request(
            "get_unresolved_boundary_eligibility_v1", buildJsonObject { put("p_journey_id", journeyId) })).single()
        require(row.journeyId == journeyId && row.ownerId == owner)
        val eligibility = UnresolvedEligibility.valueOf(row.decision)
        val reason = UnresolvedReason.valueOf(row.reason)
        val claim = if (eligibility == UnresolvedEligibility.ELIGIBLE) {
            require(reason == UnresolvedReason.CURRENT_UNRESOLVED)
            require(instant(row.evaluatedAt) >= instant(requireNotNull(row.acceptedAt)))
            IncidentClaim(journeyId, owner, requireNotNull(row.admissionId), requireNotNull(row.policy),
                requireNotNull(row.admittedAt), requireNotNull(row.eta), requireNotNull(row.boundary),
                requireNotNull(row.caseId), requireNotNull(row.eventId), requireNotNull(row.verifyingAt),
                requireNotNull(row.version), requireNotNull(row.baseline)).also {
                it.validate()
                require(instant(row.evaluatedAt) >= instant(it.boundaryAt))
            }
        } else null
        IncidentEligibility(eligibility, reason, claim)
    }

    override suspend fun finalize(claim: IncidentClaim, beforeDispatch: suspend () -> Boolean): AuthorityRead<IncidentFinalizerResponse> = call { owner ->
        claim.validate(); require(claim.admission().valid() && owner == claim.ownerId)
        if (!beforeDispatch()) throw DispatchObsolete()
        val row = Json.decodeFromString<List<FinalizerRow>>(request("finalize_unresolved_incident_v1",
            buildJsonObject {
                put("p_journey_id", claim.journeyId); put("p_admission_id", claim.admissionId)
                put("p_boundary_at", claim.boundaryAt); put("p_policy_version", claim.policyVersion)
                put("p_verification_case_id", claim.verificationCaseId); put("p_opening_event_id", claim.openingEventId)
                put("p_verifying_started_at", claim.verifyingStartedAt); put("p_guard_version", claim.guardVersion)
                put("p_history_baseline_at", claim.historyBaselineAt)
            })).single()
        val status = JourneyStatus.valueOf(row.status)
        require(row.decision in setOf("UNRESOLVED_INCIDENT", "BLOCKED", "TOO_EARLY"))
        if (row.decision == "UNRESOLVED_INCIDENT") {
            require(status == JourneyStatus.UNRESOLVED_INCIDENT && instant(requireNotNull(row.boundary)) == instant(claim.boundaryAt) &&
                row.caseId == claim.verificationCaseId)
            require(instant(requireNotNull(row.endedAt)) >= instant(claim.boundaryAt))
            require(instant(requireNotNull(row.expiry)) == instant(row.endedAt).plusSeconds(604_800))
        }
        if (row.decision == "TOO_EARLY") require(status == JourneyStatus.ACTIVE &&
            instant(requireNotNull(row.boundary)) == instant(claim.boundaryAt) && row.endedAt == null)
        IncidentFinalizerResponse(row.decision, status, row.endedAt, row.boundary, row.caseId, row.expiry)
    }

    override suspend fun provenance(journeyId: String): AuthorityRead<IncidentProvenance> = call { owner ->
        uuid(journeyId)
        val r = Json.decodeFromString<List<ProvenanceRow>>(request(
            "get_owner_unresolved_incident_finalization_v1", buildJsonObject { put("p_journey_id", journeyId) })).single()
        require(r.journeyId == journeyId && r.ownerId == owner && r.decision == "UNRESOLVED_INCIDENT" &&
            r.status == "UNRESOLVED_INCIDENT" && instant(r.endedAt) == instant(r.finalizedAt))
        // admitted_at is intentionally absent from this read. It remains the existing local admission fact.
        // The coordinator binds original_eta/boundary/admission identity to Room before persisting.
        IncidentProvenance(IncidentClaim(r.journeyId, r.ownerId, r.admissionId, r.policy,
            null, r.eta, r.boundary, r.caseId, r.eventId,
            r.verifyingAt, r.version, r.baseline), r.finalizedAt, r.expiry, r.source).also { it.validate() }
    }

    override suspend fun autoEndProvenance(journeyId: String): AuthorityRead<AutoEndProvenance> = call { owner ->
        uuid(journeyId)
        Json.decodeFromString<List<AutoEndProvenance>>(request(
            "get_owner_probable_end_auto_end_finalization_v1",
            buildJsonObject { put("p_journey_id", journeyId) })).single().also {
            it.validate()
            require(it.journeyId == journeyId && it.ownerId == owner)
        }
    }

    private suspend fun <T> call(block: suspend (String) -> T): AuthorityRead<T> = try {
        val owner = requireOwner(); uuid(owner)
        val value = block(owner)
        require(requireOwner() == owner)
        AuthorityRead.Value(value)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: Exception) {
        val kind = (error as? com.journeycontinuity.app.sync.CloudSyncException)?.kind
            ?: error.toCloudSyncException(CloudStage.JOURNEY_UPSERT).kind
        AuthorityRead.Failed(if (error is DispatchObsolete) IncidentReconciliationOutcome.OBSOLETE else when (kind) {
            SyncFailureKind.AUTHENTICATION, SyncFailureKind.AUTHORIZATION -> IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED
            SyncFailureKind.TRANSIENT -> IncidentReconciliationOutcome.UNAVAILABLE
            else -> IncidentReconciliationOutcome.INCONSISTENT
        })
    }
}

private class DispatchObsolete : Exception()

@Serializable private data class EligibilityRow(
    @SerialName("journey_id") val journeyId: String, @SerialName("owner_id") val ownerId: String,
    @SerialName("admission_id") val admissionId: String?, @SerialName("policy_version") val policy: String?,
    @SerialName("admitted_at") val admittedAt: String?, @SerialName("original_eta") val eta: String?,
    @SerialName("boundary_at") val boundary: String?, @SerialName("accepted_at") val acceptedAt: String?,
    @SerialName("evaluated_at") val evaluatedAt: String, val decision: String, val reason: String,
    @SerialName("verification_case_id") val caseId: String?, @SerialName("opening_event_id") val eventId: Long?,
    @SerialName("verifying_started_at") val verifyingAt: String?, @SerialName("guard_version") val version: Long?,
    @SerialName("history_baseline_at") val baseline: String?,
)
@Serializable private data class FinalizerRow(val decision: String,
    @SerialName("journey_status") val status: String, @SerialName("ended_at") val endedAt: String?,
    @SerialName("boundary_at") val boundary: String?, @SerialName("verification_case_id") val caseId: String?,
    @SerialName("access_expires_at") val expiry: String?)
@Serializable private data class ProvenanceRow(val decision: String,
    @SerialName("journey_status") val status: String, @SerialName("ended_at") val endedAt: String,
    @SerialName("boundary_at") val boundary: String, @SerialName("verification_case_id") val caseId: String,
    @SerialName("access_expires_at") val expiry: String, @SerialName("journey_id") val journeyId: String,
    @SerialName("owner_id") val ownerId: String, @SerialName("admission_id") val admissionId: String,
    @SerialName("policy_version") val policy: String, @SerialName("original_eta") val eta: String,
    @SerialName("opening_event_id") val eventId: Long, @SerialName("verifying_started_at") val verifyingAt: String,
    @SerialName("guard_version") val version: Long, @SerialName("history_baseline_at") val baseline: String,
    @SerialName("finalized_at") val finalizedAt: String, val source: String)
