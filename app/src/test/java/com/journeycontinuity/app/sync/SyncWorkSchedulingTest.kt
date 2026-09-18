package com.journeycontinuity.app.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncWorkSchedulingTest {
    @Test
    fun newJourneyWithNoExistingWorkSchedulesPrimaryWorker() {
        assertEquals(
            SyncEnqueueDecision(SyncWorkLane.PRIMARY, ExistingWorkPolicy.KEEP),
            planSyncEnqueue(SyncRequestUrgency.URGENT, emptyList()),
        )
    }

    @Test
    fun repeatedTelemetryRequestsCoalesce() {
        val existing = listOf(enqueued(SyncWorkLane.PRIMARY))

        assertNull(planSyncEnqueue(SyncRequestUrgency.ROUTINE, existing))
        assertNull(planSyncEnqueue(SyncRequestUrgency.ROUTINE, existing))
    }

    @Test
    fun requestDuringRunningWorkerUsesOtherLaneWithoutCancellingIt() {
        assertEquals(
            SyncEnqueueDecision(SyncWorkLane.WAKE, ExistingWorkPolicy.KEEP),
            planSyncEnqueue(
                SyncRequestUrgency.ROUTINE,
                listOf(running(SyncWorkLane.PRIMARY)),
            ),
        )
        assertNull(
            planSyncEnqueue(
                SyncRequestUrgency.ROUTINE,
                listOf(running(SyncWorkLane.PRIMARY), enqueued(SyncWorkLane.WAKE)),
            ),
        )
    }

    @Test
    fun urgentRequestBypassesRetryBackoffUsingFreeWakeLane() {
        assertEquals(
            SyncEnqueueDecision(SyncWorkLane.WAKE, ExistingWorkPolicy.KEEP),
            planSyncEnqueue(
                SyncRequestUrgency.URGENT,
                listOf(enqueued(SyncWorkLane.PRIMARY, attempts = 9)),
            ),
        )
    }

    @Test
    fun telemetryGetsOnePromptPathPastStaleRetryThenCoalescesAtTwoLanes() {
        val stalePrimary = enqueued(SyncWorkLane.PRIMARY, attempts = 9)
        assertEquals(
            SyncEnqueueDecision(SyncWorkLane.WAKE, ExistingWorkPolicy.KEEP),
            planSyncEnqueue(SyncRequestUrgency.ROUTINE, listOf(stalePrimary)),
        )

        assertNull(
            planSyncEnqueue(
                SyncRequestUrgency.ROUTINE,
                listOf(stalePrimary, enqueued(SyncWorkLane.WAKE, attempts = 1)),
            ),
        )
    }

    @Test
    fun completionReplacesOnlyBackedOffWakeWhenBothLanesAreOccupied() {
        assertEquals(
            SyncEnqueueDecision(SyncWorkLane.WAKE, ExistingWorkPolicy.REPLACE),
            planSyncEnqueue(
                SyncRequestUrgency.URGENT,
                listOf(
                    enqueued(SyncWorkLane.PRIMARY, attempts = 9),
                    enqueued(SyncWorkLane.WAKE, attempts = 3),
                ),
            ),
        )
    }

    @Test
    fun changeDuringSynchronizationGuaranteesOneCoalescedFollowUpPass() {
        val running = running(SyncWorkLane.PRIMARY)
        assertEquals(
            SyncEnqueueDecision(SyncWorkLane.WAKE, ExistingWorkPolicy.KEEP),
            planSyncEnqueue(SyncRequestUrgency.ROUTINE, listOf(running)),
        )
        assertNull(
            planSyncEnqueue(
                SyncRequestUrgency.ROUTINE,
                listOf(running, enqueued(SyncWorkLane.WAKE)),
            ),
        )
    }

    @Test
    fun urgentRequestNeverReplacesRunningWork() {
        assertEquals(
            SyncEnqueueDecision(SyncWorkLane.WAKE, ExistingWorkPolicy.REPLACE),
            planSyncEnqueue(
                SyncRequestUrgency.URGENT,
                listOf(
                    running(SyncWorkLane.PRIMARY),
                    enqueued(SyncWorkLane.WAKE, attempts = 2),
                ),
            ),
        )
    }

    private fun running(lane: SyncWorkLane) =
        SyncWorkSnapshot(lane, SyncWorkState.RUNNING, runAttemptCount = 0)

    private fun enqueued(lane: SyncWorkLane, attempts: Int = 0) =
        SyncWorkSnapshot(lane, SyncWorkState.ENQUEUED, runAttemptCount = attempts)
}
