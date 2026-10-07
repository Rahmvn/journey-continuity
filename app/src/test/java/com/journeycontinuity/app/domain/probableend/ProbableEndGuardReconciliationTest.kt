package com.journeycontinuity.app.domain.probableend

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProbableEndGuardReconciliationTest {
    private fun guard(version: Long = 4, baseline: Long = 1000, at: Long = 2000,
        reason: VerificationGuardReason = VerificationGuardReason.VERIFICATION_CLEAR) = AuthoritativeVerificationGuard(
        "journey", reason != VerificationGuardReason.JOURNEY_NOT_ACTIVE,
        if (reason == VerificationGuardReason.VERIFICATION_CLEAR) VerificationGuardDecision.CLEAR else VerificationGuardDecision.BLOCKED,
        when (reason) { VerificationGuardReason.VERIFICATION_CLEAR -> VerificationKnowledge.CLEAR
            VerificationGuardReason.VERIFYING, VerificationGuardReason.OPEN_VERIFICATION_CASE -> VerificationKnowledge.UNRESOLVED
            else -> VerificationKnowledge.UNKNOWN }, reason, version, baseline, baseline, at,
        if (reason == VerificationGuardReason.VERIFICATION_CLEAR) at + 300_000 else null)
    private fun decision(g: AuthoritativeVerificationGuard, previous: AuthoritativeVerificationGuard? = guard(),
        usable: Boolean = true, present: Boolean = true, bound: Boolean = true) =
        ProbableEndGuardReconciliation.decide("journey", previous, usable, present, bound, VerificationGuardRead.Available(g))

    @Test fun firstClearEstablishesBaselineEvenWithAdvancedVersion() {
        for (version in listOf(0L, 100L)) assertEquals(GuardReconciliationDecision.REBASE,
            decision(guard(version), null, usable = false, bound = false))
    }
    @Test fun onlyLaterBoundCandidateWithSameVersionCanProveContinuity() {
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, decision(guard(at = 2100)))
        assertEquals(GuardReconciliationDecision.REBASE, decision(guard(), bound = false))
        assertEquals(GuardReconciliationDecision.REFRESH, decision(guard(), present = false))
    }
    @Test fun higherVersionInvalidatesRegardlessOfNumericDelta() {
        for (version in listOf(5L, 50L, Long.MAX_VALUE))
            assertEquals(GuardReconciliationDecision.REBASE, decision(guard(version)))
    }
    @Test fun everyBlockedReasonInvalidatesWithoutNeedingVersionAdvance() {
        for (reason in VerificationGuardReason.entries.filter { it != VerificationGuardReason.VERIFICATION_CLEAR })
            assertEquals(GuardReconciliationDecision.BLOCKED, decision(guard(reason = reason)))
        assertEquals(GuardReconciliationDecision.REBASE, decision(guard(), usable = false))
    }
    @Test fun unknownCannotSupplyAnyBaselineOrContinuity() {
        for (reason in VerificationGuardUnavailableReason.entries) assertEquals(GuardReconciliationDecision.UNKNOWN,
            ProbableEndGuardReconciliation.decide("journey", guard(), true, true, true, VerificationGuardRead.Unknown(reason)))
    }
    @Test fun epochReplacementRebasesButSameEpochRegressionIsUnknown() {
        assertEquals(GuardReconciliationDecision.REBASE, decision(guard(0, baseline = 1500)))
        assertEquals(GuardReconciliationDecision.UNKNOWN, decision(guard(3)))
        assertEquals(GuardReconciliationDecision.UNKNOWN, decision(guard(at = 1999)))
    }
    @Test fun wrongJourneyAndMalformedReceiptFailClosed() {
        for (g in listOf(guard().copy(journeyId = "other"), guard().copy(version = -1),
            guard().copy(historyBaselineAtMillis = 0), guard().copy(evidenceNotBeforeMillis = 2001),
            guard().copy(journeyActive = false), guard().copy(freshUntilMillis = null),
            guard().copy(verification = VerificationKnowledge.UNKNOWN)))
            assertEquals(GuardReconciliationDecision.UNKNOWN, decision(g))
    }

    private class Store : ProbableEndGuardReconciliationStore<String> {
        var pending = true; var prepared = 0; var applied = 0
        override suspend fun prepare(journeyId: String, trigger: GuardReconciliationTrigger): String? {
            if (!pending) return null
            prepared++; pending = false; return journeyId
        }
        override suspend fun apply(request: String, read: VerificationGuardRead): GuardReconciliationDecision {
            applied++; return GuardReconciliationDecision.UNKNOWN
        }
    }
    @Test fun locationSignalsDoNotRepeatUnknownAttemptsAndLifecycleRetryCanResume() = runTest {
        val store = Store(); var calls = 0
        val coordinator = ProbableEndGuardCoordinator(store, object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?): VerificationGuardRead {
                calls++; return VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE)
            }
        })
        repeat(30) { coordinator.reconcile("journey", GuardReconciliationTrigger.NEW_CANDIDATE) }
        assertEquals(1, calls); assertEquals(1, store.applied)
        store.pending = true
        coordinator.reconcile("journey", GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED)
        assertEquals(2, calls)
    }
    @Test fun overlappingSignalsCoalesceAndCancellationPropagates() = runTest {
        val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val coordinator = ProbableEndGuardCoordinator(store, object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?): VerificationGuardRead {
                calls++; entered.complete(Unit); release.await(); throw CancellationException()
            }
        })
        val first = async { coordinator.reconcile("journey", GuardReconciliationTrigger.RESTORE) }
        entered.await(); coordinator.reconcile("journey", GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED)
        assertEquals(1, calls); release.complete(Unit)
        try { first.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, store.applied)
    }
    @Test fun secondaryDatabaseAndGatewayFailureCannotEscape() = runTest {
        val store = object : ProbableEndGuardReconciliationStore<String> {
            override suspend fun prepare(journeyId: String, trigger: GuardReconciliationTrigger) = journeyId
            override suspend fun apply(request: String, read: VerificationGuardRead): GuardReconciliationDecision {
                assertTrue(read is VerificationGuardRead.Unknown); throw IllegalStateException("synthetic database failure")
            }
        }
        ProbableEndGuardCoordinator(store, object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?): VerificationGuardRead =
                throw IllegalStateException("synthetic network failure")
        }).reconcile("journey", GuardReconciliationTrigger.RESTORE)
    }
}
