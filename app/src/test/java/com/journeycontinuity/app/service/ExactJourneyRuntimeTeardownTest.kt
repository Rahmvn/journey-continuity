package com.journeycontinuity.app.service

import com.journeycontinuity.app.auth.OwnerAdmissionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ExactJourneyRuntimeTeardownTest {
    @Test fun admissionWaitsForRuntimeAcknowledgementNotJustInvocation() = runTest {
        val gate = OwnerAdmissionGate()
        val entered = CompletableDeferred<Unit>(); val acknowledge = CompletableDeferred<Unit>()
        var active: String? = "a"
        val teardown = ExactJourneyRuntimeTeardown(gate, { active }, {
            assertEquals("a", it); entered.complete(Unit); acknowledge.await(); true
        })
        val old = async { teardown.stop("a") }; entered.await()
        val admission = async { gate.withLock { active = "b" } }
        yield(); assertFalse(admission.isCompleted)
        acknowledge.complete(Unit); assertTrue(old.await()); admission.await()
        val restored = ExactJourneyRuntimeTeardown(gate, { active }, { error("Old effect cannot stop B") })
        repeat(2) { assertTrue(restored.stop("a")) }
        assertEquals("b", active)
    }
}
