package com.journeycontinuity.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.Instant
import java.util.UUID

enum class ExplicitJourneyAction { COMPLETE, STOP }
enum class IncidentReconciliationOutcome {
    TERMINAL, EXPLICIT_PENDING, CLAIM_PENDING, TOO_EARLY, INELIGIBLE, NO_CURRENT_VERIFICATION,
    SUPERSEDED_EPISODE, FINAL_GUARD_BLOCKED, COMPETING_TERMINAL, AUTHENTICATION_REQUIRED, UNAVAILABLE, INCONSISTENT, OBSOLETE,
}

/** Preserve PostgreSQL timestamp precision for the exact episode; epoch milliseconds are not RPC identity. */
@Serializable
data class IncidentClaim(
    val journeyId: String, val ownerId: String, val admissionId: String,
    val policyVersion: String, val admittedAt: String?, val originalEta: String,
    val boundaryAt: String, val verificationCaseId: String, val openingEventId: Long,
    val verifyingStartedAt: String, val guardVersion: Long, val historyBaselineAt: String,
) {
    fun admission() = BoundaryAdmission(journeyId, admissionId, instant(requireNotNull(admittedAt)).toEpochMilli(),
        instant(originalEta).toEpochMilli(), instant(boundaryAt).toEpochMilli(), policyVersion)
    fun validate() {
        listOf(journeyId, ownerId, admissionId, verificationCaseId).forEach(::uuid)
        require(policyVersion == JourneyEtaPolicy.VERSION && openingEventId > 0 && guardVersion >= 0)
        require(instant(boundaryAt) == instant(originalEta).plusMillis(86_400_000))
        if (admittedAt != null) require(admission().valid())
        require(instant(verifyingStartedAt) >= instant(historyBaselineAt))
    }
    private fun canonicalEpisode() = copy(admittedAt = null,
        originalEta = instant(originalEta).toString(), boundaryAt = instant(boundaryAt).toString(),
        verifyingStartedAt = instant(verifyingStartedAt).toString(), historyBaselineAt = instant(historyBaselineAt).toString())
    fun sameEpisode(other: IncidentClaim) = canonicalEpisode() == other.canonicalEpisode()
    override fun toString() = "IncidentClaim(journeyId=$journeyId, episode=redacted)"
}

@Serializable
data class IncidentProvenance(
    val claim: IncidentClaim, val finalizedAt: String, val accessExpiresAt: String,
    val source: String,
) {
    fun validate() {
        claim.validate()
        require(source == "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
        require(instant(finalizedAt) >= instant(claim.boundaryAt))
        require(instant(accessExpiresAt) == instant(finalizedAt).plusSeconds(168 * 60 * 60L))
    }
    override fun toString() = "IncidentProvenance(journeyId=${claim.journeyId}, outcome=UNRESOLVED_INCIDENT)"
}

internal fun instant(value: String): Instant = Instant.parse(value)
internal fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }

/** A server receipt, never a locally presented prompt, countdown, claim or Traveller response. */
@Serializable
data class AutoEndProvenance(
    val decision: String,
    @SerialName("presentation_episode_id") val presentationEpisodeId: String,
    @SerialName("journey_status") val journeyStatus: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("not_before_at") val notBeforeAt: String,
    @SerialName("journey_id") val journeyId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("candidate_episode_id") val candidateEpisodeId: String,
    @SerialName("detector_generation") val detectorGeneration: Long,
    @SerialName("policy_version") val policyVersion: String,
    @SerialName("history_baseline_at") val historyBaselineAt: String,
    @SerialName("guard_version") val guardVersion: Long,
    @SerialName("registered_at") val registeredAt: String,
    @SerialName("finalized_at") val finalizedAt: String,
) {
    fun validate() {
        listOf(journeyId, ownerId, presentationEpisodeId, candidateEpisodeId).forEach(::uuid)
        require(decision == "AUTO_ENDED" && journeyStatus == "AUTO_ENDED")
        require(policyVersion == com.journeycontinuity.app.domain.probableend.ProbableEndPolicy.VERSION)
        require(detectorGeneration >= 0 && guardVersion >= 0)
        listOf(endedAt, notBeforeAt, historyBaselineAt, registeredAt, finalizedAt).forEach {
            require(instant(it).toEpochMilli() > 0)
        }
        require(instant(notBeforeAt) == instant(registeredAt).plusSeconds(30 * 60))
        require(instant(finalizedAt) >= instant(notBeforeAt) && instant(endedAt) == instant(finalizedAt))
    }
    fun sameFacts(other: AutoEndProvenance): Boolean = canonical() == other.canonical()
    private fun canonical() = copy(endedAt = instant(endedAt).toString(),
        notBeforeAt = instant(notBeforeAt).toString(), historyBaselineAt = instant(historyBaselineAt).toString(),
        registeredAt = instant(registeredAt).toString(), finalizedAt = instant(finalizedAt).toString())
    override fun toString() = "AutoEndProvenance(journeyId=$journeyId, outcome=AUTO_ENDED)"
}
