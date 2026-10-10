package com.journeycontinuity.app.service

import com.journeycontinuity.app.auth.OwnerAdmissionGate

/** Hold admission through actual runtime acknowledgement, not merely stop invocation.
 * The runtime stop must ALSO validate its ticket at execution; a timed-out queued stop cannot affect a later Journey.
 * Caller must not hold the dispatch gate (existing account lock order is admission then dispatch).
 */
class ExactJourneyRuntimeTeardown(
    private val admission: OwnerAdmissionGate,
    private val activeJourneyId: suspend () -> String?,
    private val stopExactAndAwait: suspend (String) -> Boolean,
) {
    suspend fun stop(journeyId: String): Boolean = admission.withLock {
        val active = activeJourneyId()
        if (active != null && active != journeyId) true else stopExactAndAwait(journeyId)
    }
}
