package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.TelemetryObservation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedConnectivityCoordinatorTest {
    private var now = 1_000L
    private val store = FakeStore()
    private val actions = mutableListOf<DegradedConnectivityAction>()

    @Test
    fun healthyLossAndProcessRestorationRetainInterruptionTiming() = runBlocking {
        coordinator().activate(JOURNEY_ID, validatedInternetAvailable = true)
        now += 1
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID, now)
        assertEquals(ConnectivityPhase.HEALTHY, store.state?.connectivityPhase)

        now = 2_000L
        coordinator().validatedInternetLost(JOURNEY_ID)
        assertEquals(ConnectivityPhase.INTERRUPTED, store.state?.connectivityPhase)
        assertEquals(2_000L, store.state?.interruptionStartedAtMillis)

        now = 100_000L
        coordinator().activate(JOURNEY_ID, validatedInternetAvailable = false)

        assertEquals(ConnectivityPhase.INTERRUPTED, store.state?.connectivityPhase)
        assertEquals(2_000L, store.state?.interruptionStartedAtMillis)
        assertEquals(1, store.rowsWritten)
    }

    @Test
    fun elapsedEvaluationDegradesWithUnavailableProductionTransport() = runBlocking {
        coordinator().activate(JOURNEY_ID, validatedInternetAvailable = false)
        coordinator().telemetryObserved(telemetry(sequence = 1))

        now += DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS
        coordinator().timeAdvanced(JOURNEY_ID)

        assertEquals(ConnectivityPhase.DEGRADED, store.state?.connectivityPhase)
        assertEquals(FallbackDisposition.UNAVAILABLE, store.state?.fallbackDisposition)
        assertEquals(1L, store.state?.degradationEpisodeId)
        assertEquals(emptyList<DegradedConnectivityAction>(), actions)
    }

    @Test
    fun recoveryRequiresFreshHeartbeatAndFailureResumesSameEpisode() = runBlocking {
        coordinator().activate(JOURNEY_ID, validatedInternetAvailable = false)
        now += DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS
        coordinator().timeAdvanced(JOURNEY_ID)
        val episode = store.state?.degradationEpisodeId

        now += 1
        coordinator().validatedInternetAvailable(JOURNEY_ID)
        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)

        coordinator().retryableCloudFailure(JOURNEY_ID)
        assertEquals(ConnectivityPhase.DEGRADED, store.state?.connectivityPhase)
        assertEquals(episode, store.state?.degradationEpisodeId)

        coordinator().validatedInternetAvailable(JOURNEY_ID)
        now += 1
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID, now)
        assertEquals(ConnectivityPhase.HEALTHY, store.state?.connectivityPhase)
        assertNull(store.state?.degradationEpisodeId)
    }

    @Test
    fun degradedTelemetryPersistsAndCompletionStopsEvaluation() = runBlocking {
        coordinator().activate(JOURNEY_ID, validatedInternetAvailable = false)
        now += DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS
        coordinator().timeAdvanced(JOURNEY_ID)
        coordinator().telemetryObserved(telemetry(sequence = 7))
        assertEquals(7L, store.state?.latestTelemetrySequence)

        coordinator().journeyCompleted(JOURNEY_ID, ++now)
        val episode = store.state?.degradationEpisodeId
        now += DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS
        coordinator().timeAdvanced(JOURNEY_ID)

        assertFalse(requireNotNull(store.state).journeyActive)
        assertEquals(FallbackDisposition.INACTIVE, store.state?.fallbackDisposition)
        assertEquals(episode, store.state?.degradationEpisodeId)
        assertEquals(1, actions.filterIsInstance<DegradedConnectivityAction.StopOrdinaryFallback>().size)
    }

    @Test
    fun processAndServiceRestartDoNotReplayFirstFallback() = runBlocking {
        val coordinator = coordinator(fallbackAvailable = true)
        coordinator.activate(JOURNEY_ID, validatedInternetAvailable = false)
        coordinator.telemetryObserved(telemetry(sequence = 1))
        now += DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS
        coordinator.timeAdvanced(JOURNEY_ID)
        val first = actions.single() as DegradedConnectivityAction.AllocateFallbackAttempt
        store.state = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
            .reduce(
                requireNotNull(store.state),
                DegradedConnectivityEvent.FallbackAttemptAllocated(
                    first.envelopeSequence,
                    first.telemetrySequence,
                    now,
                ),
            ).state
        actions.clear()

        coordinator(fallbackAvailable = true).activate(JOURNEY_ID, validatedInternetAvailable = false)
        coordinator(fallbackAvailable = true).timeAdvanced(JOURNEY_ID)

        assertEquals(ConnectivityPhase.DEGRADED, store.state?.connectivityPhase)
        assertEquals(FallbackDisposition.ALLOCATED, store.state?.fallbackDisposition)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun recoveryActivationCompletesBeforeTicksWithoutAllocating() = runBlocking {
        val coordinator = coordinator(fallbackAvailable = true)
        coordinator.activate(JOURNEY_ID, validatedInternetAvailable = false)
        coordinator.telemetryObserved(telemetry(sequence = 1))
        now += DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS
        coordinator.timeAdvanced(JOURNEY_ID)
        val first = actions.single() as DegradedConnectivityAction.AllocateFallbackAttempt
        store.state = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
            .reduce(
                requireNotNull(store.state),
                DegradedConnectivityEvent.FallbackAttemptAllocated(
                    first.envelopeSequence,
                    first.telemetrySequence,
                    now,
                ),
            ).state
        actions.clear()

        coordinator(fallbackAvailable = true).activate(JOURNEY_ID, validatedInternetAvailable = true)
        repeat(3) {
            now += DegradedConnectivityLabConfiguration.EVALUATION_INTERVAL_MILLIS
            coordinator(fallbackAvailable = true).timeAdvanced(JOURNEY_ID)
            coordinator(fallbackAvailable = true).telemetryObserved(telemetry(sequence = 2L + it))
        }

        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, store.state?.fallbackDisposition)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun finiteBacklogTargetRequiresLaterHeartbeatAndSurvivesRecreation() = runBlocking {
        store.latestSequence = 10
        store.checkpoint = 2
        coordinator().activate(JOURNEY_ID, true)
        assertEquals(10L, store.state?.recoveryTargetTelemetrySequence)
        now += 10
        val earlyHeartbeatStartedAt = now
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID, earlyHeartbeatStartedAt)
        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)
        assertTrue(actions.isEmpty())

        store.latestSequence = 20 // New telemetry does not move the captured target.
        now += 10
        coordinator().activate(JOURNEY_ID, true)
        assertEquals(10L, store.state?.recoveryTargetTelemetrySequence)
        store.checkpoint = 10
        coordinator().timeAdvanced(JOURNEY_ID)
        val satisfiedAt = store.state?.recoveryBacklogSatisfiedAtMillis
        assertEquals(now, satisfiedAt)
        now += 10
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID, earlyHeartbeatStartedAt)
        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)
        assertTrue(actions.isEmpty())
        coordinator().activate(JOURNEY_ID, true)
        assertEquals(satisfiedAt, store.state?.recoveryBacklogSatisfiedAtMillis)
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID, now)
        assertEquals(ConnectivityPhase.HEALTHY, store.state?.connectivityPhase)
        assertEquals(1, actions.count { it is DegradedConnectivityAction.SupersedeUnsentFallback })
        assertTrue(actions.none { it is DegradedConnectivityAction.AllocateFallbackAttempt })
    }

    @Test
    fun incompleteCheckpointCannotBeBypassedByTicksOrHeartbeatWithoutStartTime() = runBlocking {
        store.latestSequence = 10
        coordinator().activate(JOURNEY_ID, true)
        repeat(5) {
            now += 60_000
            coordinator().timeAdvanced(JOURNEY_ID)
            coordinator().freshHeartbeatSucceeded(JOURNEY_ID, now)
            coordinator().activate(JOURNEY_ID, true)
        }
        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)
        assertNull(store.state?.recoveryBacklogSatisfiedAtMillis)
        assertTrue(actions.isEmpty())
        store.checkpoint = 10
        coordinator().timeAdvanced(JOURNEY_ID)
        now++
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID)
        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)
    }

    @Test
    fun validatedNetworkReconciliationRestartsRecoveryAfterCloudFailureWithoutNetworkTransition() = runBlocking {
        store.latestSequence = 10
        store.checkpoint = 2
        coordinator().activate(JOURNEY_ID, true)
        coordinator().retryableCloudFailure(JOURNEY_ID)
        now++
        coordinator().validatedInternetAvailable(JOURNEY_ID)
        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)
        assertEquals(10L, store.state?.recoveryTargetTelemetrySequence)
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID, now)
        assertEquals(ConnectivityPhase.RECOVERING, store.state?.connectivityPhase)
        store.checkpoint = 10
        coordinator().timeAdvanced(JOURNEY_ID)
        now++
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID, now)
        assertEquals(ConnectivityPhase.HEALTHY, store.state?.connectivityPhase)
    }

    private fun coordinator(fallbackAvailable: Boolean = false) = DegradedConnectivityCoordinator(
        store = store,
        latestTelemetryReader = { null },
        fallbackCapabilityReader = FallbackCapabilityReader { fallbackAvailable },
        policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig()),
        actionObserver = { actions += it },
        clock = { now },
    )

    private fun telemetry(sequence: Long) = TelemetryObservation(
        id = sequence,
        journeyId = JOURNEY_ID,
        sequence = sequence,
        eventTime = now,
        latitude = 9.0,
        longitude = 7.0,
        accuracyMeters = 10f,
        batteryPercent = 70,
        isCharging = false,
        connectivity = ConnectivityState.NONE,
    )

    private class FakeStore : DegradedConnectivityStateStore {
        var state: DegradedConnectivityState? = null
        var rowsWritten = 0
        var latestSequence = 0L
        var checkpoint = 0L

        override suspend fun updateAtomically(
            journeyId: String,
            transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?,
        ): DegradedConnectivityReduction? {
            val reduction = transform(state, RecoveryBacklogSnapshot(latestSequence, checkpoint)) ?: return null
            state = reduction.state
            rowsWritten = 1
            return reduction
        }

        override fun observe(journeyId: String): Flow<DegradedConnectivityState?> = flowOf(state)
    }

    private companion object {
        const val JOURNEY_ID = "journey"
    }
}
