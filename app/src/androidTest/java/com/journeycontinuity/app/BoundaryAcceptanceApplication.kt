package com.journeycontinuity.app

import com.journeycontinuity.app.domain.BoundaryAdmission
import com.journeycontinuity.app.domain.BoundaryReceipt
import com.journeycontinuity.app.data.repository.AuthorityWakeResult

/** Isolated instrumentation clock; the production app always uses SystemClock.elapsedRealtime. */
class BoundaryAcceptanceApplication : JourneyContinuityApplication() {
    @Volatile private var elapsedOffsetMillis = 0L
    @Volatile var interceptAuthorityWakes = false
    @Volatile var authorityWakeCount = 0
    @Volatile var authorityWorkerResult: AuthorityWakeResult? = null
    @Volatile var authorityWorkerFailure: Exception? = null
    @Volatile var authorityWorkerCalls = 0
    @Volatile var authorityWorkerBeforeResult: suspend () -> Unit = {}
    @Volatile var overrideAuthorityGate = false
    @Volatile var testInternetUsable = false
    @Volatile var testBoundOwner: String? = null
    @Volatile var testSessionOwner: String? = null

    override fun wakePendingAuthority() {
        if (interceptAuthorityWakes) authorityWakeCount++ else super.wakePendingAuthority()
    }

    override suspend fun reconcilePendingAuthorityNow(journeyId: String?, scheduledToken: String?): AuthorityWakeResult {
        authorityWorkerFailure?.let { throw it }
        val scripted = authorityWorkerResult ?: return super.reconcilePendingAuthorityNow(journeyId, scheduledToken)
        authorityWorkerBeforeResult()
        authorityWorkerCalls++
        return scripted
    }

    override fun authorityInternetUsable(): Boolean =
        if (overrideAuthorityGate) testInternetUsable else super.authorityInternetUsable()

    override fun authorityBoundOwner(): String? =
        if (overrideAuthorityGate) testBoundOwner else super.authorityBoundOwner()

    override fun authorityAuthenticatedOwnerMatches(owner: String): Boolean =
        if (overrideAuthorityGate) testSessionOwner == owner else super.authorityAuthenticatedOwnerMatches(owner)

    override fun operationalBoundaryReceipt(): BoundaryReceipt {
        val actual = super.operationalBoundaryReceipt()
        return actual.copy(elapsedRealtimeMillis = Math.addExact(actual.elapsedRealtimeMillis, elapsedOffsetMillis))
    }

    fun setRemaining(admission: BoundaryAdmission, anchor: BoundaryReceipt, remainingMillis: Long) {
        require(remainingMillis > 0)
        val actual = super.operationalBoundaryReceipt()
        elapsedOffsetMillis = Math.subtractExact(
            Math.subtractExact(Math.addExact(anchor.elapsedRealtimeMillis,
                admission.boundaryMillis - admission.admittedAtMillis), remainingMillis),
            actual.elapsedRealtimeMillis)
    }

    fun resetElapsed() { elapsedOffsetMillis = 0 }
}
