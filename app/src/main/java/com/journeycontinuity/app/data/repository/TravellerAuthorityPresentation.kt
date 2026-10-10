package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.data.local.TravellerAuthorityRecord
import com.journeycontinuity.app.data.local.ProbableEndCompletedResult
import com.journeycontinuity.app.domain.IncidentProvenance
import com.journeycontinuity.app.domain.JourneyStatus
import kotlinx.serialization.json.Json
import java.time.Instant

data class UnresolvedJourneyResult(
    val journeyId: String,
    val destination: String,
    val expectedArrivalAt: Long,
    val finalizedAt: String,
    val provenanceJson: String,
)

fun UnresolvedJourneyResult.boundaryExplanation(): String {
    val proof = runCatching {
        Json.decodeFromString<IncidentProvenance>(provenanceJson).also { it.validate() }
    }.getOrNull()
    return if (proof != null && Instant.parse(proof.claim.verifyingStartedAt) <= Instant.parse(proof.claim.boundaryAt))
        "Alabarin stopped continuous monitoring because this Journey remained unresolved 24 hours after the expected arrival time."
    else "Continuous monitoring has ended. This Journey reached its applicable monitoring boundary, and verification was unresolved when the server finalized it."
}

data class TravellerTerminalResult(
    val journeyId: String,
    val endedAt: Instant,
    val unresolved: UnresolvedJourneyResult? = null,
    val automatic: ProbableEndCompletedResult? = null,
)

fun selectTravellerTerminalResult(records: List<TravellerAuthorityRecord>): TravellerTerminalResult? =
    records.mapNotNull { record ->
        record.unresolvedResult()?.let {
            return@mapNotNull TravellerTerminalResult(it.journeyId, Instant.parse(it.finalizedAt), unresolved = it)
        }
        val journey = record.journey
        if (journey.status != JourneyStatus.AUTO_ENDED || journey.activeSlot != null ||
            journey.completedAt != null || record.incident != null) return@mapNotNull null
        val presentation = record.presentations.filter {
            it.journeyId == journey.id && it.status == "AUTO_ENDED" &&
                it.automaticResultAcknowledgedAtMillis == null && it.automaticEndedAtMillis == journey.endedAt &&
                runCatching { it.validate() }.isSuccess
        }.sortedBy { it.id }.firstOrNull() ?: return@mapNotNull null
        TravellerTerminalResult(journey.id, Instant.ofEpochMilli(requireNotNull(presentation.automaticEndedAtMillis)),
            automatic = ProbableEndCompletedResult(presentation.id, journey.id, journey.destination, journey.expectedArrivalAt))
    }.sortedWith(compareByDescending<TravellerTerminalResult> { it.endedAt }.thenBy { it.journeyId })
        .firstOrNull()

fun TravellerAuthorityRecord.unresolvedResult(): UnresolvedJourneyResult? = runCatching {
    val receipt = incident ?: return null
    if (receipt.resultAcknowledgedAtMillis != null || receipt.journeyId != journey.id ||
        automatic != null ||
        journey.status != JourneyStatus.UNRESOLVED_INCIDENT || journey.activeSlot != null ||
        journey.completedAt != null) return null
    val proof = Json.decodeFromString<IncidentProvenance>(receipt.provenanceJson)
    proof.validate()
    if (proof.claim.journeyId != journey.id || Instant.parse(proof.finalizedAt).toEpochMilli() != journey.endedAt ||
        Instant.parse(proof.claim.originalEta).toEpochMilli() != journey.expectedArrivalAt) return null
    UnresolvedJourneyResult(journey.id, journey.destination, journey.expectedArrivalAt,
        proof.finalizedAt, receipt.provenanceJson)
}.getOrNull()

fun TravellerAuthorityRecord.isPendingAuthority(): Boolean =
    (journey.status == JourneyStatus.ACTIVE && journey.activeSlot == 1 &&
        (operational?.state == "PENDING_AUTHORITY" ||
            reconciliation?.let { it.explicitAction == "STOP" && it.explicitState == "PENDING" } == true)) ||
        (journey.status == JourneyStatus.COMPLETED && reconciliation?.explicitAction == "COMPLETE" &&
            reconciliation.explicitState == "PENDING")

/** Match 10C-2 admission guards. The domain checks these again in its write transaction. */
fun TravellerAuthorityRecord.canRecordExplicitAction(): Boolean =
    isPendingAuthority() && journey.status == JourneyStatus.ACTIVE && journey.activeSlot == 1 &&
    incident == null && automatic == null &&
    reconciliation?.claimState !in setOf("PENDING", "COMMITTED") &&
        (reconciliation?.explicitAction == null || reconciliation.explicitState == "REFUSED") &&
        presentations.none { it.status == "AUTO_END_CLAIMED" }

data class PendingAuthorityCopy(val timing: String, val authority: String, val intent: String?)

/** Independent facts remain separate: an offline device can also have uncertain timing and an intent. */
fun pendingAuthorityCopy(record: TravellerAuthorityRecord, checking: Boolean = false,
    offline: Boolean = false, checkedOutcome: String? = null): PendingAuthorityCopy {
    val op = record.operational
    val r = record.reconciliation
    val timing = when {
        op?.lastAssessment == "BOUNDARY_REACHED_WITH_PROOF" || op?.pauseReason == "AUTHENTICATED_BOUNDARY_PROOF" ->
            "Authenticated timing evidence confirms the monitoring boundary was reached. It does not confirm a final Journey outcome."
        op?.pauseReason == "LOST_OR_INVALID_BOOT_ANCHOR" ->
            "Timing could not be verified after the offline cutoff. Monitoring remains stopped while the final outcome is unconfirmed."
        op?.pauseReason == "APPROXIMATE_MONOTONIC_CAP" ->
            "The local operational limit was reached. This device estimate does not prove that the server monitoring boundary was reached."
        op?.pauseReason == "EXPLICIT_STOP_INTENT" -> "Monitoring stopped on this device after your Stop request."
        else -> "Monitoring remains stopped on this device."
    }
    val outcome = checkedOutcome ?: r?.lastOutcome
    val authority = when {
        checking -> "Checking authoritative status…"
        offline && r?.claimState == "PENDING" && r.claimDispatch == "MAY_HAVE_DISPATCHED" ->
            "Offline — awaiting connectivity. Finalizer acknowledgement is uncertain; a request may have reached the server."
        offline && outcome == "AUTHENTICATION_REQUIRED" ->
            "Offline — awaiting connectivity. Authentication is also needed before this Journey’s status can be checked."
        offline -> "Offline — awaiting connectivity. Connect to the internet to check the authoritative status."
        outcome == "AUTHENTICATION_REQUIRED" -> "Authentication needed. Reconnect your account to check this Journey’s authoritative status."
        r?.claimState == "PENDING" && r.claimDispatch == "MAY_HAVE_DISPATCHED" ->
            "Finalizer acknowledgement is uncertain. A request may have reached the server; no final outcome has been confirmed on this device."
        outcome == "TOO_EARLY" -> "The server reports that the monitoring boundary has not yet been reached. Monitoring remains paused."
        outcome == "NO_CURRENT_VERIFICATION" -> "The server reports no current unresolved verification episode. An unresolved incident has not been confirmed."
        outcome == "INELIGIBLE" -> "The server has not authorized an unresolved incident for this Journey. Monitoring remains paused."
        outcome == "SUPERSEDED_EPISODE" -> "The verification episode has changed. The earlier episode cannot establish a final outcome."
        outcome == "FINAL_GUARD_BLOCKED" -> "The server refused finalization because the verification conditions changed. No unresolved incident has been confirmed."
        outcome == "COMPETING_TERMINAL" -> "The server reports a different terminal outcome. Its exact details still need to be reconciled."
        outcome == "EXPLICIT_PENDING" -> "The server has not acknowledged the recorded Complete or Stop request. Its final outcome remains pending."
        outcome == "CLAIM_PENDING" -> "An end request is pending. Its authoritative outcome has not been confirmed on this device."
        outcome == "UNAVAILABLE" -> "The network or server is temporarily unavailable. No final outcome was confirmed by this check."
        outcome == "INCONSISTENT" -> "The available records could not be verified together. The final Journey outcome remains unconfirmed."
        outcome == "ELIGIBLE" -> "The server reports an unresolved verification episode at the monitoring boundary. A final outcome has not been confirmed."
        r?.claimState == "PENDING" -> "An authoritative outcome is pending. No final result has been confirmed."
        record.presentations.any { it.status == "AUTO_END_CLAIMED" } ->
            "An automatic-end request is pending. Its authoritative outcome must be checked before another end action can be accepted."
        else -> "The final Journey outcome is awaiting authoritative confirmation."
    }
    val intent = when {
        r?.explicitState in setOf("ACCOUNT_RESERVED", "ACCOUNT_UNCERTAIN") ->
            "An Account Stop-and-sign-out operation is pending. Continue that operation from Account; this screen does not sign you out."
        r?.explicitState == "PENDING" && r.explicitAction == "COMPLETE" ->
            "Complete intent recorded on this device. Server acknowledgement is still pending; this does not confirm arrival or safety."
        r?.explicitState == "PENDING" && r.explicitAction == "STOP" ->
            "Stop intent recorded on this device. Server acknowledgement is still pending; the Journey is not confirmed cancelled."
        r?.explicitState == "SUPERSEDED" -> "Your earlier request was superseded by the server’s authoritative outcome."
        else -> null
    }
    return PendingAuthorityCopy(timing, authority, intent)
}
