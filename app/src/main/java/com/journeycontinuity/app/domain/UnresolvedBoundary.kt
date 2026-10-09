package com.journeycontinuity.app.domain

enum class UnresolvedEligibility { ELIGIBLE, NOT_ELIGIBLE, UNKNOWN }
enum class UnresolvedReason {
    CURRENT_UNRESOLVED, BOUNDARY_NOT_REACHED, JOURNEY_NOT_ACTIVE, NO_ADMISSION_PROVENANCE,
    NOT_VERIFYING, MISSING_MONITORING_STATE, INCONSISTENT_STATE, SUPERSEDED_EPISODE,
    AUTHORITY_UNAVAILABLE,
}
enum class BoundaryProof { BOUNDARY_REACHED_WITH_PROOF, INSUFFICIENT_PROOF }

/** Server receipt freezes owner-attested facts; it does not authenticate a phone's admission clock. */
data class BoundaryAdmission(
    val journeyId: String, val admissionId: String, val admittedAtMillis: Long,
    val etaMillis: Long, val boundaryMillis: Long,
    val policyVersion: String = JourneyEtaPolicy.VERSION,
) {
    fun valid(): Boolean = policyVersion == JourneyEtaPolicy.VERSION &&
        runCatching { java.util.UUID.fromString(journeyId); java.util.UUID.fromString(admissionId)
            JourneyEtaPolicy.boundary(admittedAtMillis, etaMillis) == boundaryMillis }.getOrDefault(false)
}

/** A read-point observation, never a finalization lease. Payload contains IDs/times only. */
data class UnresolvedObservation(
    val journeyId: String, val ownerId: String, val admissionId: String?, val policyVersion: String?,
    val admittedAtMillis: Long?, val etaMillis: Long?, val boundaryMillis: Long?,
    val acceptedAtMillis: Long?, val evaluatedAtMillis: Long,
    val eligibility: UnresolvedEligibility, val reason: UnresolvedReason,
    val verificationCaseId: String?, val openingEventId: Long?, val verifyingStartedAtMillis: Long?,
    val guardVersion: Long?, val historyBaselineAtMillis: Long?,
) {
    fun matches(admission: BoundaryAdmission, owner: String): Boolean = admission.valid() &&
        journeyId == admission.journeyId && ownerId == owner && admissionId == admission.admissionId &&
        policyVersion == admission.policyVersion && admittedAtMillis == admission.admittedAtMillis &&
        etaMillis == admission.etaMillis && boundaryMillis == admission.boundaryMillis &&
        acceptedAtMillis != null && evaluatedAtMillis >= acceptedAtMillis

    fun coherentEligibility(): Boolean = eligibility != UnresolvedEligibility.ELIGIBLE ||
        (reason == UnresolvedReason.CURRENT_UNRESOLVED && boundaryMillis != null &&
            evaluatedAtMillis >= boundaryMillis && verificationCaseId != null && openingEventId != null &&
            verifyingStartedAtMillis != null && guardVersion != null && historyBaselineAtMillis != null)
}

/** Receipt metadata is diagnostic/scheduling provenance ONLY; no UTC rate bound is assumed. */
data class BoundaryReceipt(val elapsedRealtimeMillis: Long, val bootCount: Int?, val installationId: String) {
    fun sameBoot(other: BoundaryReceipt): Boolean = bootCount != null && bootCount == other.bootCount &&
        installationId == other.installationId && other.elapsedRealtimeMillis >= elapsedRealtimeMillis
}

fun boundaryProof(admission: BoundaryAdmission, authenticatedObservationAtMillis: Long?): BoundaryProof =
    if (admission.valid() && authenticatedObservationAtMillis != null &&
        authenticatedObservationAtMillis >= admission.boundaryMillis)
        BoundaryProof.BOUNDARY_REACHED_WITH_PROOF else BoundaryProof.INSUFFICIENT_PROOF
