package com.journeycontinuity.app.domain

/** Device operations only. Neither value is a Journey status or server-time proof. */
enum class OperationalBoundaryState { MONITORING, PENDING_AUTHORITY }

enum class OperationalPauseReason {
    AUTHENTICATED_BOUNDARY_PROOF,
    APPROXIMATE_MONOTONIC_CAP,
    LOST_OR_INVALID_BOOT_ANCHOR,
}

enum class OperationalBoundaryAssessment {
    NOT_TRACKED,
    NOT_ACTIVE,
    INSUFFICIENT_AUTHORITY,
    BEFORE_APPROXIMATE_CAP,
    PAUSED,
    ALREADY_PAUSED,
}

/** The local cap is deliberately approximate; it never changes BoundaryProof. */
fun approximateOperationalCapReached(
    admission: BoundaryAdmission,
    anchor: BoundaryReceipt,
    current: BoundaryReceipt,
): Boolean? {
    if (!admission.valid() || !anchor.sameBoot(current)) return null
    return runCatching {
        val duration = Math.subtractExact(admission.boundaryMillis, admission.admittedAtMillis)
        val elapsed = Math.subtractExact(current.elapsedRealtimeMillis, anchor.elapsedRealtimeMillis)
        duration >= 0 && elapsed >= duration
    }.getOrNull()
}
