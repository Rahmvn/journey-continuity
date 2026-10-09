package com.journeycontinuity.app.data.unresolved

import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.domain.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

fun interface UnresolvedBoundaryGateway {
    /** Null means unavailable/invalid authority, never permission or a time proof. */
    suspend fun read(journeyId: String): UnresolvedObservation?
}

class SupabaseUnresolvedBoundaryGateway internal constructor(
    private val requireOwner: suspend () -> String?, private val request: suspend (String) -> String,
) : UnresolvedBoundaryGateway {
    constructor(client: SupabaseClient, identity: TravellerIdentityCoordinator) : this(
        { identity.requireAuthenticatedTraveller().userId },
        { id -> client.postgrest.rpc("get_unresolved_boundary_eligibility_v1",
            buildJsonObject { put("p_journey_id", id) }).data },
    )

    override suspend fun read(journeyId: String): UnresolvedObservation? = try {
        require(UUID.fromString(journeyId).toString() == journeyId)
        val owner = requireNotNull(requireOwner())
        val row = Json.decodeFromString<List<BoundaryRpcRow>>(request(journeyId)).single()
        require(requireOwner() == owner && row.ownerId == owner && row.journeyId == journeyId)
        fun time(value: String?) = value?.let { Instant.parse(it).toEpochMilli() }
        val observation = UnresolvedObservation(row.journeyId, row.ownerId, row.admissionId, row.policyVersion,
            time(row.admittedAt), time(row.eta), time(row.boundary), time(row.acceptedAt),
            requireNotNull(time(row.evaluatedAt)), UnresolvedEligibility.valueOf(row.decision),
            UnresolvedReason.valueOf(row.reason), row.caseId, row.openingEventId, time(row.verifyingStartedAt),
            row.guardVersion, time(row.historyBaselineAt))
        require(observation.coherentEligibility())
        observation
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { null }
}

@Serializable
private data class BoundaryRpcRow(
    @SerialName("journey_id") val journeyId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("admission_id") val admissionId: String?,
    @SerialName("policy_version") val policyVersion: String?,
    @SerialName("admitted_at") val admittedAt: String?,
    @SerialName("original_eta") val eta: String?,
    @SerialName("boundary_at") val boundary: String?,
    @SerialName("accepted_at") val acceptedAt: String?,
    @SerialName("evaluated_at") val evaluatedAt: String,
    val decision: String, val reason: String,
    @SerialName("verification_case_id") val caseId: String?,
    @SerialName("opening_event_id") val openingEventId: Long?,
    @SerialName("verifying_started_at") val verifyingStartedAt: String?,
    @SerialName("guard_version") val guardVersion: Long?,
    @SerialName("history_baseline_at") val historyBaselineAt: String?,
)
