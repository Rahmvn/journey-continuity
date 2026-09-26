package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.TelemetryObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedConnectivityPolicyTest {
    private val config = DegradedConnectivityPolicyConfig(
        degradationAfterMillis = 1_000,
        minimumRetryableCloudFailures = 2,
        recoveryGraceMillis = 500,
        minimumFallbackIntervalMillis = 100,
        fallbackRateWindowMillis = 1_000,
        maximumFallbackAttemptsPerWindow = 2,
    )
    private val policy = DegradedConnectivityPolicy(config)

    @Test
    fun briefNetworkLossRemainsInterrupted() {
        var state = healthyState()
        state = reduce(state, DegradedConnectivityEvent.ValidatedInternetLost(100)).state
        val result = reduce(state, DegradedConnectivityEvent.TimeAdvanced(1_099))

        assertEquals(ConnectivityPhase.INTERRUPTED, result.state.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, result.state.fallbackDisposition)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun elapsedInterruptionBecomesDegraded() {
        var state = healthyState()
        state = reduce(state, DegradedConnectivityEvent.ValidatedInternetLost(100)).state
        state = reduce(state, telemetry(sequence = 1, atMillis = 200)).state
        state = reduce(
            state,
            DegradedConnectivityEvent.TransportAvailabilityChanged(true, 200),
        ).state
        val result = reduce(state, DegradedConnectivityEvent.TimeAdvanced(1_100))

        assertEquals(ConnectivityPhase.DEGRADED, result.state.connectivityPhase)
        assertEquals(1L, result.state.degradationEpisodeId)
        assertEquals(FallbackDisposition.ELIGIBLE, result.state.fallbackDisposition)
        assertEquals(1, result.actions.size)
    }

    @Test
    fun repeatedCloudFailuresBecomeDegradedBeforeElapsedThreshold() {
        var state = healthyState()
        state = reduce(state, DegradedConnectivityEvent.RetryableCloudFailure(100)).state
        val result = reduce(state, DegradedConnectivityEvent.RetryableCloudFailure(200))

        assertEquals(ConnectivityPhase.DEGRADED, result.state.connectivityPhase)
        assertEquals(1L, result.state.degradationEpisodeId)
    }

    @Test
    fun degradedWithoutProvisionedFallbackCapabilityIsExplicitlyUnavailable() {
        var state = reduce(
            interruptedWithTelemetry(),
            DegradedConnectivityEvent.FallbackCapabilityChanged(false, 1),
        ).state
        val result = reduce(state, DegradedConnectivityEvent.TimeAdvanced(1_000))

        assertEquals(ConnectivityPhase.DEGRADED, result.state.connectivityPhase)
        assertEquals(FallbackDisposition.UNAVAILABLE, result.state.fallbackDisposition)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun fallbackCapabilityBecomingAvailableCreatesOneEligibleFirstAttempt() {
        var state = reduce(
            reduce(
                interruptedWithTelemetry(),
                DegradedConnectivityEvent.FallbackCapabilityChanged(false, 1),
            ).state,
            DegradedConnectivityEvent.TimeAdvanced(1_000),
        ).state
        val result = reduce(
            state,
            DegradedConnectivityEvent.FallbackCapabilityChanged(true, 1_001),
        )

        assertEquals(FallbackDisposition.ELIGIBLE, result.state.fallbackDisposition)
        val action = result.actions.single() as DegradedConnectivityAction.AllocateFallbackAttempt
        assertEquals(1L, action.envelopeSequence)
        assertEquals(1L, action.telemetrySequence)
    }

    @Test
    fun ordinaryTimeEvaluationDoesNotCreateOneFallbackPerHeartbeatInterval() {
        var state = firstAttemptedState()

        repeat(10) { index ->
            val result = reduce(
                state,
                DegradedConnectivityEvent.TimeAdvanced(1_100L + index * 100L),
            )
            state = result.state
            assertTrue(result.actions.isEmpty())
            assertEquals(FallbackDisposition.ALLOCATED, state.fallbackDisposition)
        }
        assertEquals(2L, state.nextFallbackEnvelopeSequence)
    }

    @Test
    fun ordinaryTelemetryDoesNotCreateLaterFallbackEvenAfterMinimumInterval() {
        var state = firstAttemptedState()
        state = reduce(state, telemetry(sequence = 2, atMillis = 1_050)).state

        assertTrue(reduce(state, DegradedConnectivityEvent.TimeAdvanced(1_099)).actions.isEmpty())
        val result = reduce(state, DegradedConnectivityEvent.TimeAdvanced(2_000))
        assertTrue(result.actions.isEmpty())
        assertEquals(FallbackDisposition.ALLOCATED, result.state.fallbackDisposition)
        assertEquals(2L, result.state.nextFallbackEnvelopeSequence)
    }

    @Test
    fun explicitSparseTriggerRequiresNewTelemetryIntervalAndRateCapacity() {
        var state = firstAttemptedState()
        state = reduce(state, telemetry(sequence = 2, atMillis = 1_050)).state

        assertTrue(
            reduce(state, DegradedConnectivityEvent.SparseFallbackTriggered(1_099)).actions.isEmpty(),
        )
        var result = reduce(state, DegradedConnectivityEvent.SparseFallbackTriggered(1_100))
        assertEquals(2L, allocation(result).envelopeSequence)
        state = reduce(
            result.state,
            DegradedConnectivityEvent.FallbackAttemptAllocated(2, 2, 1_100),
        ).state
        state = reduce(state, telemetry(sequence = 3, atMillis = 1_200)).state

        result = reduce(state, DegradedConnectivityEvent.SparseFallbackTriggered(1_500))
        assertTrue(result.actions.isEmpty())
        assertEquals(FallbackDisposition.ALLOCATED, result.state.fallbackDisposition)

        result = reduce(result.state, DegradedConnectivityEvent.SparseFallbackTriggered(2_000))
        assertEquals(3L, allocation(result).envelopeSequence)
    }

    @Test
    fun validationFlappingRetainsEpisodeAndRateHistory() {
        var state = firstAttemptedState()
        val episode = state.degradationEpisodeId
        val attempts = state.fallbackAttemptsInRateWindow

        state = reduce(
            state,
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010),
        ).state
        assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, state.fallbackDisposition)

        state = reduce(state, DegradedConnectivityEvent.ValidatedInternetLost(1_020)).state
        assertEquals(ConnectivityPhase.DEGRADED, state.connectivityPhase)
        assertEquals(episode, state.degradationEpisodeId)
        assertEquals(attempts, state.fallbackAttemptsInRateWindow)
        assertEquals(FallbackDisposition.ALLOCATED, state.fallbackDisposition)
    }

    @Test
    fun validatedInternetStartsRecoveryButDoesNotEstablishHealth() {
        val result = reduce(
            firstAttemptedState(),
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010),
        )

        assertEquals(ConnectivityPhase.RECOVERING, result.state.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, result.state.fallbackDisposition)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun freshAuthenticatedCloudSuccessCompletesRecovery() {
        var state = reduce(
            firstAttemptedState(),
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010, 7),
        ).state
        state = reduce(state, DegradedConnectivityEvent.RecoveryCheckpointObserved(7, 1_011)).state
        val success = reduce(
            state,
            DegradedConnectivityEvent.AuthenticatedCloudSuccess(
                atMillis = 1_020,
                establishesFreshContact = true,
                heartbeatStartedAtMillis = 1_012,
            ),
        )
        state = success.state

        assertEquals(ConnectivityPhase.HEALTHY, state.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, state.fallbackDisposition)
        assertEquals(null, state.degradationEpisodeId)
        assertEquals(1_020L, state.lastAuthenticatedCloudSuccessAtMillis)
        assertTrue(success.actions.none { it is DegradedConnectivityAction.AllocateFallbackAttempt })
    }

    @Test
    fun authenticatedSuccessWithoutFreshContactDoesNotCompleteRecovery() {
        var state = reduce(
            firstAttemptedState(),
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010),
        ).state
        state = reduce(
            state,
            DegradedConnectivityEvent.AuthenticatedCloudSuccess(
                atMillis = 1_020,
                establishesFreshContact = false,
            ),
        ).state

        assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, state.fallbackDisposition)
    }

    @Test
    fun failedRecoveryResumesSameEpisodeAndRateHistory() {
        var state = firstAttemptedState()
        val episode = state.degradationEpisodeId
        val attempts = state.fallbackAttemptsInRateWindow
        state = reduce(
            state,
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010),
        ).state
        state = reduce(state, DegradedConnectivityEvent.RetryableCloudFailure(1_020)).state

        assertEquals(ConnectivityPhase.DEGRADED, state.connectivityPhase)
        assertEquals(episode, state.degradationEpisodeId)
        assertEquals(attempts, state.fallbackAttemptsInRateWindow)
    }

    @Test
    fun recoveryTicksNeverResumeDegradedWhileValidatedInternetRemainsAvailable() {
        var state = firstAttemptedState()
        val episode = state.degradationEpisodeId
        state = reduce(
            state,
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010),
        ).state

        state = reduce(state, DegradedConnectivityEvent.TimeAdvanced(1_509)).state
        assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)

        val result = reduce(state, DegradedConnectivityEvent.TimeAdvanced(10_000))
        state = result.state
        assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, state.fallbackDisposition)
        assertEquals(episode, state.degradationEpisodeId)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun backlogTelemetryDuringRecoveryCannotAllocateFallback() {
        var state = reduce(
            firstAttemptedState(),
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010),
        ).state
        state = reduce(state, telemetry(sequence = 2, atMillis = 1_100)).state

        val telemetryResult = reduce(state, telemetry(sequence = 3, atMillis = 2_000))
        val tickResult = reduce(telemetryResult.state, DegradedConnectivityEvent.TimeAdvanced(3_000))
        val sparseResult = reduce(
            tickResult.state,
            DegradedConnectivityEvent.SparseFallbackTriggered(3_000),
        )

        assertTrue(telemetryResult.actions.isEmpty())
        assertTrue(tickResult.actions.isEmpty())
        assertTrue(sparseResult.actions.isEmpty())
        assertEquals(ConnectivityPhase.RECOVERING, sparseResult.state.connectivityPhase)
        assertEquals(FallbackDisposition.INACTIVE, sparseResult.state.fallbackDisposition)
    }

    @Test
    fun failedRecoveryReturnsToSameEpisodeWithoutReplayingFirstFallback() {
        var state = firstAttemptedState()
        val episode = state.degradationEpisodeId
        state = reduce(
            state,
            DegradedConnectivityEvent.ValidatedInternetAvailable(1_010),
        ).state

        val failure = reduce(state, DegradedConnectivityEvent.RetryableCloudFailure(1_020))
        val tick = reduce(failure.state, DegradedConnectivityEvent.TimeAdvanced(10_000))

        assertEquals(ConnectivityPhase.DEGRADED, tick.state.connectivityPhase)
        assertEquals(episode, tick.state.degradationEpisodeId)
        assertEquals(FallbackDisposition.ALLOCATED, tick.state.fallbackDisposition)
        assertTrue(failure.actions.isEmpty())
        assertTrue(tick.actions.isEmpty())
    }

    @Test
    fun journeyCompletionStopsOrdinaryFallback() {
        val result = reduce(
            firstAttemptedState(),
            DegradedConnectivityEvent.JourneyCompleted(1_010),
        )

        assertFalse(result.state.journeyActive)
        assertEquals(FallbackDisposition.INACTIVE, result.state.fallbackDisposition)
        assertEquals(
            DegradedConnectivityAction.StopOrdinaryFallback("journey", 1_010),
            result.actions.single(),
        )
    }

    @Test
    fun restoredStateCannotAllocateAnotherFirstFallback() {
        val restored = firstAttemptedState().copy()
        val result = reduce(restored, DegradedConnectivityEvent.TimeAdvanced(1_500))

        assertEquals(FallbackDisposition.ALLOCATED, result.state.fallbackDisposition)
        assertEquals(2L, result.state.nextFallbackEnvelopeSequence)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun lowBatteryObservationDoesNotClassifyConnectivityAsDegraded() {
        var state = healthyState()
        state = reduce(
            state,
            telemetry(sequence = 2, batteryPercent = 1, atMillis = 100),
        ).state

        assertEquals(ConnectivityPhase.HEALTHY, state.connectivityPhase)
        assertEquals(1, state.latestBatteryPercent)
        assertEquals(FallbackDisposition.INACTIVE, state.fallbackDisposition)
    }

    @Test
    fun domainContainsNoDangerOrEmergencySemantics() {
        val names = buildList {
            addAll(ConnectivityPhase.entries.map { it.name })
            addAll(FallbackDisposition.entries.map { it.name })
            addAll(
                listOf(
                    DegradedConnectivityAction.AllocateFallbackAttempt::class.simpleName.orEmpty(),
                    DegradedConnectivityAction.StopOrdinaryFallback::class.simpleName.orEmpty(),
                ),
            )
        }.joinToString(" ").lowercase()

        assertFalse("danger" in names)
        assertFalse("emergency" in names)
    }

    private fun healthyState(): DegradedConnectivityState {
        var state = reduce(
            DegradedConnectivityState(),
            DegradedConnectivityEvent.JourneyActivated(
                journeyId = "journey",
                validatedInternetAvailable = true,
                fallbackBindingProvisioned = true,
                atMillis = 0,
                recoveryTargetTelemetrySequence = 0,
            ),
        ).state
        state = reduce(state, DegradedConnectivityEvent.RecoveryCheckpointObserved(0, 0)).state
        state = reduce(
            state,
            DegradedConnectivityEvent.AuthenticatedCloudSuccess(1, true, 1),
        ).state
        return state
    }

    private fun interruptedWithTelemetry(): DegradedConnectivityState {
        var state = reduce(
            DegradedConnectivityState(),
            DegradedConnectivityEvent.JourneyActivated(
                journeyId = "journey",
                validatedInternetAvailable = false,
                fallbackBindingProvisioned = true,
                atMillis = 0,
            ),
        ).state
        state = reduce(state, telemetry(sequence = 1, atMillis = 0)).state
        return state
    }

    private fun firstAttemptedState(): DegradedConnectivityState {
        var state = interruptedWithTelemetry()
        state = reduce(
            state,
            DegradedConnectivityEvent.TransportAvailabilityChanged(true, 0),
        ).state
        state = reduce(state, DegradedConnectivityEvent.TimeAdvanced(1_000)).state
        assertNotNull(allocation(DegradedConnectivityReduction(state, listOf(
            DegradedConnectivityAction.AllocateFallbackAttempt("journey", 1, 1, 1),
        ))))
        return reduce(
            state,
            DegradedConnectivityEvent.FallbackAttemptAllocated(1, 1, 1_000),
        ).state
    }

    private fun telemetry(
        sequence: Long,
        batteryPercent: Int? = 70,
        atMillis: Long,
    ) = DegradedConnectivityEvent.TelemetryObserved(
        observation = TelemetryObservation(
            id = sequence,
            journeyId = "journey",
            sequence = sequence,
            eventTime = atMillis,
            latitude = 9.0,
            longitude = 7.0,
            accuracyMeters = 10f,
            batteryPercent = batteryPercent,
            isCharging = false,
            connectivity = ConnectivityState.NONE,
        ),
        atMillis = atMillis,
    )

    private fun reduce(
        state: DegradedConnectivityState,
        event: DegradedConnectivityEvent,
    ) = policy.reduce(state, event)

    private fun allocation(result: DegradedConnectivityReduction) =
        result.actions.single() as DegradedConnectivityAction.AllocateFallbackAttempt
}
