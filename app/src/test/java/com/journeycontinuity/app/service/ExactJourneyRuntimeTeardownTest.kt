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

    @Test fun delayedCutoffEffectForOldJourneyCannotStopNewAdmission() = runTest {
        var active: String? = "journey-b"
        var stopped = 0
        val teardown = ExactJourneyRuntimeTeardown(OwnerAdmissionGate(), { active }, stopExactAndAwait = {
            stopped++
            true
        })
        assertTrue(teardown.stop("journey-a"))
        assertEquals("journey-b", active)
        assertEquals(0, stopped)
    }

    @Test fun lockFreeDelayedPrivacyStopStillHonorsRuntimeGenerationAndStartId() = runTest {
        val signals = JourneyRuntimeSignals()
        signals.serviceStarted()
        signals.startCommand(1)
        signals.begin("journey-a")
        signals.startCommand(2)
        signals.begin("journey-b")
        var stopped = 0
        val teardown = ExactJourneyRuntimeTeardown(OwnerAdmissionGate(), { "journey-b" },
            stopExactAndAwait = { error("Privacy stop must not use ordinary teardown") },
            stopPrivacyExact = { id ->
            val ticket = signals.stopTicket(id)
            if (ticket == null) true else signals.executePrivacyStop(ticket,
                stopCollection = { stopped++ },
                stopSelfResult = { error("Old A must not stop B's service") },
                stopForeground = { error("Old A must not remove B's foreground") })
        })
        assertTrue(teardown.stopPrivacy("journey-a"))
        assertEquals("journey-b", signals.activeRuntimeJourneyId.value)
        assertEquals(0, stopped)
    }
}
