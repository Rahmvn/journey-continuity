package com.journeycontinuity.app.auth

import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.sync.RemoteJourneyState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.plugins.ResponseException
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Uses the established user JWT and an exact owner/RLS read; never uses a service credential. */
class SupabaseActiveJourneyStopRemote(
    private val client: SupabaseClient,
    private val authBackend: TravellerAuthBackend,
) : ActiveJourneyStopRemote {
    override suspend fun stop(journeyId: String, ownerId: String): MonitoringStopAcknowledgement {
        requireAuthenticatedOwner(ownerId)
        val response = try {
            client.postgrest.rpc("stop_journey_monitoring_v1", buildJsonObject {
                put("p_journey_id", journeyId)
            }).decodeSingle<StopMonitoringRpcRow>()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error.isAmbiguousStopTransport()) throw AmbiguousMonitoringStopException(error)
            throw error
        }
        requireAuthenticatedOwner(ownerId)
        return MonitoringStopAcknowledgement(
            JourneyStatus.valueOf(response.status),
            response.endedAt?.let { Instant.parse(it).toEpochMilli() },
        )
    }

    override suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState? {
        requireAuthenticatedOwner(ownerId)
        val row = client.from("journeys").select(
            Columns.list("id", "owner_id", "status", "ended_at", "completed_at"),
        ) {
            filter {
                eq("id", journeyId)
                eq("owner_id", ownerId)
            }
        }.decodeList<OwnerStoppedJourneyRow>().singleOrNull()
        requireAuthenticatedOwner(ownerId)
        return row?.let {
            RemoteJourneyState(
                journeyId = it.id,
                ownerId = it.ownerId,
                status = JourneyStatus.valueOf(it.status),
                endedAt = it.endedAt?.let { value -> Instant.parse(value).toEpochMilli() },
                completedAt = it.completedAt?.let { value -> Instant.parse(value).toEpochMilli() },
            )
        }
    }

    private fun requireAuthenticatedOwner(ownerId: String) {
        val state = authBackend.sessionState() as? TravellerSessionState.Authenticated
        if (state?.userId != ownerId || state.accessTokenExpired) {
            throw IllegalStateException("The established Traveller session is unavailable.")
        }
    }
}

private fun Throwable.isAmbiguousStopTransport(): Boolean {
    val causes = generateSequence(this as Throwable?) { it.cause }.toList()
    if (causes.any { it is IOException }) return true
    val status = causes.filterIsInstance<RestException>().firstOrNull()?.statusCode
        ?: causes.filterIsInstance<ResponseException>().firstOrNull()?.response?.status?.value
    return status == 408 || status == 425 || status == 429 || status != null && status >= 500
}

@Serializable
private data class StopMonitoringRpcRow(
    @SerialName("journey_status") val status: String,
    @SerialName("ended_at") val endedAt: String?,
)

@Serializable
private data class OwnerStoppedJourneyRow(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val status: String,
    @SerialName("ended_at") val endedAt: String?,
    @SerialName("completed_at") val completedAt: String?,
)
