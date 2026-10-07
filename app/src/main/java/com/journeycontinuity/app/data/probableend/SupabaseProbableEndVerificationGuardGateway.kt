package com.journeycontinuity.app.data.probableend

import com.journeycontinuity.app.auth.TravellerAuthException
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.domain.probableend.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Existing authenticated Traveller only. Not wired to local candidates or presentation. */
class SupabaseProbableEndVerificationGuardGateway internal constructor(
    private val requireOwner: suspend () -> String?,
    private val request: suspend (String) -> String,
) : ProbableEndVerificationGuardGateway {
    constructor(client: SupabaseClient, identity: TravellerIdentityCoordinator) : this(
        { identity.requireAuthenticatedTraveller().userId },
        { journeyId -> client.postgrest.rpc("get_probable_end_verification_guard_v1", buildJsonObject {
            put("p_journey_id", journeyId)
        }).data },
    )

    override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?): VerificationGuardRead {
        if (!canonicalJourneyId(journeyId)) return unknown(VerificationGuardUnavailableReason.INVALID_RESPONSE)
        try {
            val owner = requireOwner()?.takeIf(String::isNotBlank)
                ?: return unknown(VerificationGuardUnavailableReason.AUTHENTICATION)
            val body = request(journeyId)
            if (requireOwner() != owner) return unknown(VerificationGuardUnavailableReason.AUTHENTICATION)
            val guard = try { parseGuard(journeyId, body) }
                catch (_: Exception) { return unknown(VerificationGuardUnavailableReason.INVALID_RESPONSE) }
            if (previous != null && guard.historySince(previous) == VerificationHistoryChange.INCONSISTENT) {
                return unknown(VerificationGuardUnavailableReason.INCONSISTENT_HISTORY)
            }
            return VerificationGuardRead.Available(guard)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: TravellerAuthException) { return unknown(VerificationGuardUnavailableReason.AUTHENTICATION)
        } catch (_: Exception) { return unknown(VerificationGuardUnavailableReason.UNAVAILABLE) }
    }

    private fun unknown(reason: VerificationGuardUnavailableReason) = VerificationGuardRead.Unknown(reason)
}

private fun canonicalJourneyId(value: String): Boolean = try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) { false }

@Serializable
private data class GuardRpcRow(
    @SerialName("journey_id") val journeyId: String,
    @SerialName("journey_active") val journeyActive: Boolean,
    val decision: String,
    val reason: String,
    @SerialName("verification_state") val verificationState: String,
    @SerialName("guard_version") val guardVersion: Long?,
    @SerialName("history_baseline_at") val historyBaselineAt: String?,
    @SerialName("evidence_not_before") val evidenceNotBefore: String?,
    @SerialName("evaluated_at") val evaluatedAt: String,
    @SerialName("fresh_until") val freshUntil: String?,
)

private fun parseGuard(journeyId: String, body: String): AuthoritativeVerificationGuard {
    // Required fields, strict types and one exact row. Unknown payload fields are rejected.
    val shape = Json.parseToJsonElement(body).jsonArray.single().jsonObject
    val numericVersion = shape.getValue("guard_version").jsonPrimitive
    require(!numericVersion.isString && numericVersion.longOrNull != null)
    val active = shape.getValue("journey_active").jsonPrimitive
    require(!active.isString && active.booleanOrNull != null)
    val row = Json.decodeFromString<List<GuardRpcRow>>(body).single()
    require(row.journeyId == journeyId)
    val decision = VerificationGuardDecision.valueOf(row.decision)
    val verification = VerificationKnowledge.valueOf(row.verificationState)
    val reason = VerificationGuardReason.valueOf(row.reason)
    val version = requireNotNull(row.guardVersion).also { require(it >= 0) }
    fun time(value: String?) = Instant.parse(requireNotNull(value)).toEpochMilli().also { require(it > 0) }
    val baseline = time(row.historyBaselineAt)
    val barrier = time(row.evidenceNotBefore)
    val evaluated = time(row.evaluatedAt)
    val freshUntil = row.freshUntil?.let(::time)
    require(baseline <= barrier && barrier <= evaluated)
    require(row.journeyActive == (reason != VerificationGuardReason.JOURNEY_NOT_ACTIVE))
    if (decision == VerificationGuardDecision.CLEAR) {
        require(row.journeyActive && verification == VerificationKnowledge.CLEAR &&
            reason == VerificationGuardReason.VERIFICATION_CLEAR && freshUntil != null && freshUntil > evaluated)
    } else {
        require(reason != VerificationGuardReason.VERIFICATION_CLEAR &&
            verification != VerificationKnowledge.CLEAR && freshUntil == null)
        if (reason in setOf(VerificationGuardReason.VERIFYING, VerificationGuardReason.OPEN_VERIFICATION_CASE)) {
            require(verification == VerificationKnowledge.UNRESOLVED)
        }
    }
    return AuthoritativeVerificationGuard(journeyId, row.journeyActive, decision, verification, reason,
        version, baseline, barrier, evaluated, freshUntil)
}
