package com.journeycontinuity.app.domain.probableend

/** Point-in-time server knowledge. CLEAR is neither a lease nor candidate promotion. */
data class AuthoritativeVerificationGuard(
    val journeyId: String,
    val journeyActive: Boolean,
    val decision: VerificationGuardDecision,
    val verification: VerificationKnowledge,
    val reason: VerificationGuardReason,
    val version: Long,
    val historyBaselineAtMillis: Long,
    val evidenceNotBeforeMillis: Long,
    val evaluatedAtMillis: Long,
    val freshUntilMillis: Long?,
) {
    /** No prior receipt means unbound history, even when the current decision is CLEAR.
     * Comparison alone never binds or promotes a candidate. */
    fun historySince(previous: AuthoritativeVerificationGuard?): VerificationHistoryChange = when {
        previous == null -> VerificationHistoryChange.UNBOUND_BASELINE
        journeyId != previous.journeyId || historyBaselineAtMillis != previous.historyBaselineAtMillis ||
            version < previous.version || evaluatedAtMillis < previous.evaluatedAtMillis ||
            evidenceNotBeforeMillis < previous.evidenceNotBeforeMillis -> VerificationHistoryChange.INCONSISTENT
        version > previous.version -> VerificationHistoryChange.INTERVENING_VERIFICATION
        else -> VerificationHistoryChange.UNCHANGED
    }
    override fun toString() = "AuthoritativeVerificationGuard(decision=$decision, reason=$reason, version=$version)"
}

enum class VerificationGuardDecision { CLEAR, BLOCKED }
enum class VerificationKnowledge { CLEAR, UNRESOLVED, UNKNOWN }
enum class VerificationHistoryChange { UNBOUND_BASELINE, UNCHANGED, INTERVENING_VERIFICATION, INCONSISTENT }
enum class VerificationGuardReason {
    VERIFICATION_CLEAR, JOURNEY_NOT_ACTIVE, MISSING_MONITORING_STATE, INCONSISTENT_STATE,
    VERIFYING, OPEN_VERIFICATION_CASE, STALE_AUTHENTICATED_EVIDENCE,
}
enum class VerificationGuardUnavailableReason { AUTHENTICATION, UNAVAILABLE, INVALID_RESPONSE, INCONSISTENT_HISTORY }

sealed interface VerificationGuardRead {
    data class Available(val guard: AuthoritativeVerificationGuard) : VerificationGuardRead
    /** No trustworthy clearance exists. Never includes server bodies, exception messages or tokens. */
    data class Unknown(val reason: VerificationGuardUnavailableReason) : VerificationGuardRead
}

interface ProbableEndVerificationGuardGateway {
    suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard? = null): VerificationGuardRead
}
