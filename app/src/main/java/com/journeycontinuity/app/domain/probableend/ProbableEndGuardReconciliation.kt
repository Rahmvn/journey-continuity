package com.journeycontinuity.app.domain.probableend

/** These are history decisions only. None authorizes presentation or a terminal action. */
enum class GuardReconciliationDecision { UNKNOWN, BLOCKED, REBASE, REFRESH, PROVE_CONTINUITY }
enum class GuardReconciliationTrigger { NEW_CANDIDATE, RESTORE, HEARTBEAT_SUCCEEDED }

object ProbableEndGuardReconciliation {
    fun decide(journeyId: String, previous: AuthoritativeVerificationGuard?, baselineUsable: Boolean,
        candidatePresent: Boolean, candidateBound: Boolean, read: VerificationGuardRead): GuardReconciliationDecision {
        val g = (read as? VerificationGuardRead.Available)?.guard ?: return GuardReconciliationDecision.UNKNOWN
        if (g.journeyId != journeyId || g.version < 0 || g.historyBaselineAtMillis <= 0 ||
            g.historyBaselineAtMillis > g.evidenceNotBeforeMillis || g.evidenceNotBeforeMillis > g.evaluatedAtMillis ||
            g.journeyActive != (g.reason != VerificationGuardReason.JOURNEY_NOT_ACTIVE)) return GuardReconciliationDecision.UNKNOWN
        if (previous != null && g.historyBaselineAtMillis == previous.historyBaselineAtMillis &&
            g.historySince(previous) == VerificationHistoryChange.INCONSISTENT) return GuardReconciliationDecision.UNKNOWN
        if (previous != null && g.evaluatedAtMillis < previous.evaluatedAtMillis) return GuardReconciliationDecision.UNKNOWN
        val clear = g.decision == VerificationGuardDecision.CLEAR
        if (clear && (!g.journeyActive || g.verification != VerificationKnowledge.CLEAR ||
                g.reason != VerificationGuardReason.VERIFICATION_CLEAR ||
                (g.freshUntilMillis ?: 0) <= g.evaluatedAtMillis)) return GuardReconciliationDecision.UNKNOWN
        if (!clear && (g.verification == VerificationKnowledge.CLEAR || g.freshUntilMillis != null ||
                g.reason == VerificationGuardReason.VERIFICATION_CLEAR)) return GuardReconciliationDecision.UNKNOWN
        if (!clear && g.reason in setOf(VerificationGuardReason.VERIFYING, VerificationGuardReason.OPEN_VERIFICATION_CASE) &&
            g.verification != VerificationKnowledge.UNRESOLVED) return GuardReconciliationDecision.UNKNOWN
        if (!clear) return GuardReconciliationDecision.BLOCKED
        if (!baselineUsable || previous == null || g.historyBaselineAtMillis != previous.historyBaselineAtMillis ||
            g.version != previous.version || (candidatePresent && !candidateBound)) return GuardReconciliationDecision.REBASE
        return if (candidatePresent) GuardReconciliationDecision.PROVE_CONTINUITY else GuardReconciliationDecision.REFRESH
    }
}
