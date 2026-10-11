package com.journeycontinuity.app.telemetry

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class LocationRegistrationLifecycleTest {
    private data class Request(val callback: Any, val success: () -> Unit, val failure: (Exception) -> Unit)
    private data class Removal(val callback: Any, val success: () -> Unit, val failure: (Exception) -> Unit)
    private val requests = mutableListOf<Request>()
    private val removals = mutableListOf<Removal>()
    private val retries = ArrayDeque<() -> Unit>()
    private val evidence = mutableListOf<LocationReleaseEvidence>()
    private val tracker = LocationRegistrationLifecycle<Any>(
        request = { callback, success, failure -> requests += Request(callback, success, failure) },
        remove = { callback, success, failure -> removals += Removal(callback, success, failure) },
        scheduleRetry = { _, action -> retries.addLast(action) },
        onRelease = evidence::add,
    )
    private val a = LocationRegistrationOwner("journey-a", 1)
    private val b = LocationRegistrationOwner("journey-b", 2)

    @Test fun successfulRemovalIsReportedOnlyAfterProviderAcknowledgement() {
        val callback = Any()
        tracker.start(a, callback, {}, {})
        requests.single().success()
        tracker.stop(a)
        assertFalse(tracker.isCurrent(a, callback))
        assertTrue(evidence.isEmpty())
        removals.single().success()
        assertEquals(listOf(LocationReleaseEvidence.Removed(a)), evidence)
    }

    @Test fun failedRemovalRetriesOnlyThreeTimesAndNeverReenablesCallback() {
        val callback = Any()
        tracker.start(a, callback, {}, {})
        requests.single().success()
        tracker.stop(a)
        repeat(3) { attempt ->
            removals[attempt].failure(IllegalStateException("provider unavailable"))
            assertFalse(tracker.isCurrent(a, callback))
            if (attempt < 2) retries.removeFirst().invoke()
        }
        assertTrue(retries.isEmpty())
        assertEquals(3, removals.size)
        assertEquals(LocationReleaseEvidence.RemovalFailed(a, 3, true), evidence.last())
    }

    @Test fun removalBeforeRegistrationSuccessIsNotAcceptedAsReleaseProof() {
        val callback = Any()
        tracker.start(a, callback, {}, {})
        tracker.stop(a)
        assertEquals(1, removals.size)
        removals[0].success()
        assertTrue(evidence.isEmpty())
        requests.single().success()
        assertEquals(2, removals.size)
        assertTrue(evidence.isEmpty())
        removals[1].success()
        assertEquals(listOf(LocationReleaseEvidence.Removed(a)), evidence)
    }

    @Test fun failedEarlyRemovalStillRequiresPostRegistrationAcknowledgement() {
        tracker.start(a, Any(), {}, {})
        tracker.stop(a)
        removals[0].failure(IllegalStateException("early removal failed"))
        assertEquals(LocationReleaseEvidence.RemovalFailed(a, 0, false), evidence.single())
        requests[0].success()
        removals[1].success()
        assertEquals(LocationReleaseEvidence.Removed(a), evidence.last())
    }

    @Test fun duplicateStopDoesNotDuplicateRemoval() {
        tracker.start(a, Any(), {}, {})
        requests.single().success()
        tracker.stop(a)
        tracker.stop(a)
        assertEquals(1, removals.size)
    }

    @Test fun oldJourneyCleanupCannotStopOrReenableNewJourney() {
        val old = Any(); val newer = Any()
        var newReady = 0
        tracker.start(a, old, {}, {})
        requests[0].success()
        tracker.stop(a)
        tracker.start(b, newer, { newReady++ }, {})
        requests[1].success()
        removals.single().success()
        tracker.stop(a)
        assertEquals(1, newReady)
        assertTrue(tracker.isCurrent(b, newer))
        assertFalse(tracker.isCurrent(a, old))
    }

    @Test fun serviceDestructionWhileRegistrationPendingStillRemovesLateRegistration() {
        val old = Any()
        var ready = 0
        tracker.start(a, old, { ready++ }, {})
        tracker.stop(a) // service onDestroy
        removals[0].success() // before request completion, insufficient proof
        requests[0].success() // registration completes after destruction
        assertEquals(0, ready)
        assertFalse(tracker.isCurrent(a, old))
        assertTrue(evidence.isEmpty())
        removals[1].success()
        assertEquals(LocationReleaseEvidence.Removed(a), evidence.single())
    }

    @Test fun canceledRegistrationIsUncertainUntilExactCallbackRemovalSucceeds() {
        val callback = Any()
        var registrationFailures = 0
        tracker.start(a, callback, {}, { registrationFailures++ })
        requests.single().failure(CancellationException("request canceled"))
        assertEquals(1, registrationFailures)
        assertFalse(tracker.isCurrent(a, callback))
        assertTrue(evidence.isEmpty())
        assertEquals(callback, removals.single().callback)
        removals.single().success()
        assertEquals(listOf(LocationReleaseEvidence.Removed(a)), evidence)
    }

    @Test fun canceledRemovalRetriesAndAcknowledgesOnlyLaterSuccess() {
        val callback = Any()
        tracker.start(a, callback, {}, {})
        requests.single().success()
        tracker.stop(a)
        removals[0].failure(CancellationException("remove canceled"))
        assertEquals(LocationReleaseEvidence.RemovalFailed(a, 1, false), evidence.single())
        assertFalse(tracker.isCurrent(a, callback))
        retries.removeFirst().invoke()
        assertEquals(callback, removals[1].callback)
        removals[1].success()
        assertEquals(LocationReleaseEvidence.Removed(a), evidence.last())
        assertTrue(retries.isEmpty())
    }

    @Test fun canceledRemovalExhaustsOnlyThreeAttemptsWithoutFalseAcknowledgement() {
        tracker.start(a, Any(), {}, {})
        requests.single().success()
        tracker.stop(a)
        repeat(3) { attempt ->
            removals[attempt].failure(CancellationException("remove canceled"))
            if (attempt < 2) retries.removeFirst().invoke()
        }
        assertEquals(3, removals.size)
        assertTrue(retries.isEmpty())
        assertTrue(evidence.none { it is LocationReleaseEvidence.Removed })
        assertEquals(LocationReleaseEvidence.RemovalFailed(a, 3, true), evidence.last())
    }

    @Test fun stoppedPendingRegistrationCancellationRequiresPostResolutionRemoval() {
        val callback = Any()
        tracker.start(a, callback, {}, {})
        tracker.stop(a) // service destruction while request is still pending
        removals[0].success() // earlier removal cannot prove a late request did not register
        requests[0].failure(CancellationException("request canceled"))
        assertTrue(evidence.isEmpty())
        assertEquals(2, removals.size)
        removals[1].success()
        assertEquals(LocationReleaseEvidence.Removed(a), evidence.single())
        tracker.stop(a)
        assertEquals(2, removals.size)
    }

    @Test fun oldCanceledRemovalRetryTargetsOnlyOldCallbackAfterNewJourneyStarts() {
        val old = Any(); val newer = Any()
        tracker.start(a, old, {}, {})
        requests[0].success()
        tracker.stop(a)
        tracker.start(b, newer, {}, {})
        requests[1].success()
        removals[0].failure(CancellationException("old removal canceled"))
        retries.removeFirst().invoke()
        assertEquals(old, removals[1].callback)
        removals[1].success()
        assertTrue(tracker.isCurrent(b, newer))
        assertEquals(LocationReleaseEvidence.Removed(a), evidence.last())
    }
}
