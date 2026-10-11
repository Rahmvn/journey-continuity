package com.journeycontinuity.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentAuthorityRetryPolicyTest {
    @Test fun uncertainResultsGetADeferredWake() {
        listOf("UNAVAILABLE", "CLAIM_PENDING", "EXPLICIT_PENDING", "TOO_EARLY",
            "AUTHENTICATION_REQUIRED").forEach {
            assertTrue(it, RoomIncidentReconciliationCoordinator.shouldScheduleRetry(it))
        }
    }

    @Test fun definitiveOrUnverifiedResultsDoNotStartABackgroundPollingChain() {
        listOf(null, "TERMINAL", "INELIGIBLE", "NO_CURRENT_VERIFICATION", "SUPERSEDED_EPISODE",
            "FINAL_GUARD_BLOCKED", "INCONSISTENT", "COMPETING_TERMINAL", "OBSOLETE").forEach {
            assertFalse("$it", RoomIncidentReconciliationCoordinator.shouldScheduleRetry(it))
        }
    }

    @Test fun schedulerBoundsForwardAndBackwardWallClockDelays() {
        val max = WorkManagerAuthorityRetryScheduler.MAX_INITIAL_DELAY_MILLIS
        assertTrue(max >= RoomIncidentReconciliationCoordinator.RETRY_MILLIS)
        assertTrue(WorkManagerAuthorityRetryScheduler.boundedInitialDelay(1_000, 2_000) == 0L)
        assertTrue(WorkManagerAuthorityRetryScheduler.boundedInitialDelay(2_000, 1_000) == 1_000L)
        assertTrue(WorkManagerAuthorityRetryScheduler.boundedInitialDelay(Long.MAX_VALUE, 1_000) == max)
        assertTrue(WorkManagerAuthorityRetryScheduler.boundedInitialDelay(Long.MAX_VALUE, Long.MIN_VALUE) == max)
    }

    @Test fun deadlinePolicyRecoversBothClockDirectionsWithoutAnImmediateRepeat() {
        val interval = RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        val deadline = 10 * interval
        assertFalse(RoomIncidentReconciliationCoordinator.retryDue(deadline, deadline - interval))
        assertTrue(RoomIncidentReconciliationCoordinator.retryDue(deadline, deadline - interval - 1))
        assertTrue(RoomIncidentReconciliationCoordinator.retryDue(deadline, deadline + interval))
        assertFalse(RoomIncidentReconciliationCoordinator.retryDue(deadline + 2 * interval, deadline + interval))
        assertTrue(RoomIncidentReconciliationCoordinator.retryDue(null, deadline))
        assertTrue(RoomIncidentReconciliationCoordinator.retryDue(Long.MAX_VALUE, Long.MIN_VALUE))
    }
}
