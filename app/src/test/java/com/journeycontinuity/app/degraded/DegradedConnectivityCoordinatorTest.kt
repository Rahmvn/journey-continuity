package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.TelemetryObservation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DegradedConnectivityCoordinatorTest {
    private var now = 1_000L
    private val store = FakeStore()
    private val actions = mutableListOf<DegradedConnectivityAction>()

    @Test
    fun healthyLossAndProcessRestorationRetainInterruptionTiming() = runBlocking {
        coordinator().activate(JOURNEY_ID, validatedInternetAvailable = true)
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID)
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
        coordinator().freshHeartbeatSucceeded(JOURNEY_ID)
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

    private fun coordinator() = DegradedConnectivityCoordinator(
        store = store,
        latestTelemetryReader = { null },
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

        override suspend fun updateAtomically(
            journeyId: String,
            transform: (DegradedConnectivityState?) -> DegradedConnectivityReduction?,
        ): DegradedConnectivityReduction? {
            val reduction = transform(state) ?: return null
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
