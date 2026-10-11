package com.journeycontinuity.app

import com.journeycontinuity.app.domain.BoundaryAdmission
import com.journeycontinuity.app.domain.BoundaryReceipt

/** Isolated instrumentation clock; the production app always uses SystemClock.elapsedRealtime. */
class BoundaryAcceptanceApplication : JourneyContinuityApplication() {
    @Volatile private var elapsedOffsetMillis = 0L

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
