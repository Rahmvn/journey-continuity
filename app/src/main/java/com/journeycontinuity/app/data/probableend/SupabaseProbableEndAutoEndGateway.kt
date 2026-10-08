package com.journeycontinuity.app.data.probableend

import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class ProbableEndServerWindowRequest(
    val journeyId: String,
    val presentationEpisodeId: String,
    val candidateEpisodeId: String,
    val generation: Long,
    val policyVersion: String,
    val historyBaselineAtMillis: Long,
    val guardVersion: Long,
) {
    override fun toString() = "ProbableEndServerWindowRequest(redacted)"
}

sealed interface ProbableEndRegistration {
    data class Registered(val registeredAtMillis: Long, val notBeforeAtMillis: Long) : ProbableEndRegistration
    data object Blocked : ProbableEndRegistration
    data object Unknown : ProbableEndRegistration
}

sealed interface ProbableEndFinalization {
    data class Ended(val endedAtMillis: Long) : ProbableEndFinalization
    data class TooEarly(val notBeforeAtMillis: Long) : ProbableEndFinalization
    data object Blocked : ProbableEndFinalization
    data object Unknown : ProbableEndFinalization
}

interface ProbableEndAutoEndGateway {
    suspend fun register(request: ProbableEndServerWindowRequest): ProbableEndRegistration
    suspend fun finalize(journeyId: String, presentationEpisodeId: String): ProbableEndFinalization
}

class SupabaseProbableEndAutoEndGateway internal constructor(
    private val requireOwner: suspend () -> String,
    private val registerRpc: suspend (ProbableEndServerWindowRequest) -> String,
    private val finalizeRpc: suspend (String, String) -> String,
) : ProbableEndAutoEndGateway {
    constructor(client: SupabaseClient, identity: TravellerIdentityCoordinator) : this(
        { identity.requireAuthenticatedTraveller().userId },
        { request -> client.postgrest.rpc("register_probable_end_confirmation_window_v1", buildJsonObject {
            put("p_journey_id", request.journeyId)
            put("p_presentation_episode_id", request.presentationEpisodeId)
            put("p_candidate_episode_id", request.candidateEpisodeId)
            put("p_detector_generation", request.generation)
            put("p_policy_version", request.policyVersion)
            put("p_history_baseline_at", Instant.ofEpochMilli(request.historyBaselineAtMillis).toString())
            put("p_guard_version", request.guardVersion)
        }).data },
        { journeyId, episodeId -> client.postgrest.rpc("finalize_probable_end_auto_end_v1", buildJsonObject {
            put("p_journey_id", journeyId)
            put("p_presentation_episode_id", episodeId)
        }).data },
    )

    override suspend fun register(request: ProbableEndServerWindowRequest): ProbableEndRegistration = try {
        if (!canonical(request.journeyId) || !canonical(request.presentationEpisodeId) ||
            !canonical(request.candidateEpisodeId) || request.generation < 0 || request.guardVersion < 0 ||
            request.historyBaselineAtMillis <= 0) return ProbableEndRegistration.Unknown
        val owner = requireOwner()
        val row = Json.decodeFromString<List<RegistrationRow>>(registerRpc(request)).single()
        if (requireOwner() != owner || row.presentationEpisodeId != request.presentationEpisodeId)
            return ProbableEndRegistration.Unknown
        when (row.decision) {
            "BLOCKED" -> if (row.registeredAt == null && row.notBeforeAt == null &&
                row.historyBaselineAt == null && row.guardVersion == null) ProbableEndRegistration.Blocked
                else ProbableEndRegistration.Unknown
            "REGISTERED" -> {
                val registered = time(row.registeredAt)
                val notBefore = time(row.notBeforeAt)
                if (row.historyBaselineAt?.let(::time) != request.historyBaselineAtMillis ||
                    row.guardVersion != request.guardVersion ||
                    notBefore != Math.addExact(registered, 30 * 60 * 1000L)) ProbableEndRegistration.Unknown
                else ProbableEndRegistration.Registered(registered, notBefore)
            }
            else -> ProbableEndRegistration.Unknown
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { ProbableEndRegistration.Unknown }

    override suspend fun finalize(journeyId: String, presentationEpisodeId: String): ProbableEndFinalization = try {
        if (!canonical(journeyId) || !canonical(presentationEpisodeId)) return ProbableEndFinalization.Unknown
        val owner = requireOwner()
        val row = Json.decodeFromString<List<FinalizationRow>>(finalizeRpc(journeyId, presentationEpisodeId)).single()
        if (requireOwner() != owner || row.presentationEpisodeId != presentationEpisodeId)
            return ProbableEndFinalization.Unknown
        val boundary = time(row.notBeforeAt)
        when (row.decision) {
            "AUTO_ENDED" -> if (row.journeyStatus == "AUTO_ENDED" && row.endedAt != null &&
                time(row.endedAt) >= boundary) ProbableEndFinalization.Ended(time(row.endedAt))
                else ProbableEndFinalization.Unknown
            "TOO_EARLY" -> if (row.journeyStatus == "ACTIVE" && row.endedAt == null)
                ProbableEndFinalization.TooEarly(boundary) else ProbableEndFinalization.Unknown
            "BLOCKED" -> ProbableEndFinalization.Blocked
            else -> ProbableEndFinalization.Unknown
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { ProbableEndFinalization.Unknown }
}

private fun canonical(id: String) = try { UUID.fromString(id).toString() == id }
catch (_: IllegalArgumentException) { false }
private fun time(value: String?) = Instant.parse(requireNotNull(value)).toEpochMilli().also { require(it > 0) }

@Serializable
private data class RegistrationRow(
    val decision: String,
    @SerialName("presentation_episode_id") val presentationEpisodeId: String,
    @SerialName("registered_at") val registeredAt: String?,
    @SerialName("not_before_at") val notBeforeAt: String?,
    @SerialName("history_baseline_at") val historyBaselineAt: String?,
    @SerialName("guard_version") val guardVersion: Long?,
)

@Serializable
private data class FinalizationRow(
    val decision: String,
    @SerialName("presentation_episode_id") val presentationEpisodeId: String,
    @SerialName("journey_status") val journeyStatus: String,
    @SerialName("ended_at") val endedAt: String?,
    @SerialName("not_before_at") val notBeforeAt: String,
)
