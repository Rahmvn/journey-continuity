package com.journeycontinuity.app.domain.probableend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

interface ProbableEndGuardReconciliationStore<Request> {
    suspend fun prepare(journeyId: String, trigger: GuardReconciliationTrigger): Request?
    suspend fun apply(request: Request, read: VerificationGuardRead): GuardReconciliationDecision?
}

/** Opportunistic secondary work. Durable request revisions, not this mutex, ensure correctness. */
class ProbableEndGuardCoordinator<Request>(
    private val store: ProbableEndGuardReconciliationStore<Request>,
    private val gateway: ProbableEndVerificationGuardGateway,
) {
    private val inFlight = Mutex()
    suspend fun reconcile(journeyId: String, trigger: GuardReconciliationTrigger) {
        if (!inFlight.tryLock()) return
        try {
            val request = store.prepare(journeyId, trigger) ?: return
            val read = try {
                // Compare epochs in the durable store: a genuine new epoch must establish
                // a fresh baseline, while regression within the same epoch stays UNKNOWN.
                gateway.read(journeyId)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE) }
            store.apply(request, read)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Optional inference unavailable; primary monitoring is independent. */
        } finally { inFlight.unlock() }
    }
}
