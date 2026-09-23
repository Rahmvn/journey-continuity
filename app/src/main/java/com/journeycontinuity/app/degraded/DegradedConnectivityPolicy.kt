package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.TelemetryObservation

enum class ConnectivityPhase {
    HEALTHY,
    INTERRUPTED,
    DEGRADED,
    RECOVERING,
}

enum class FallbackDisposition {
    INACTIVE,
    ELIGIBLE,
    UNAVAILABLE,
    ALLOCATED,
    /** Legacy v5 value. New durable allocations use [ALLOCATED]. */
    ATTEMPTED,
}

data class DegradedConnectivityPolicyConfig(
    val degradationAfterMillis: Long,
    val minimumRetryableCloudFailures: Int,
    val recoveryGraceMillis: Long,
    val minimumFallbackIntervalMillis: Long,
    val fallbackRateWindowMillis: Long,
    val maximumFallbackAttemptsPerWindow: Int,
) {
    init {
        require(degradationAfterMillis > 0)
        require(minimumRetryableCloudFailures > 0)
        require(recoveryGraceMillis > 0)
        require(minimumFallbackIntervalMillis > 0)
        require(fallbackRateWindowMillis > 0)
        require(maximumFallbackAttemptsPerWindow > 0)
    }
}

data class DegradedConnectivityState(
    val journeyId: String? = null,
    val journeyActive: Boolean = false,
    val connectivityPhase: ConnectivityPhase = ConnectivityPhase.INTERRUPTED,
    val fallbackDisposition: FallbackDisposition = FallbackDisposition.INACTIVE,
    val validatedInternetAvailable: Boolean = false,
    val fallbackBindingProvisioned: Boolean = false,
    val transportAvailable: Boolean = false,
    val degradationEpisodeId: Long? = null,
    val lastDegradationEpisodeId: Long = 0,
    val interruptionStartedAtMillis: Long? = null,
    val lastAuthenticatedCloudSuccessAtMillis: Long? = null,
    val consecutiveRetryableCloudFailures: Int = 0,
    val recoveryStartedAtMillis: Long? = null,
    val nextFallbackEnvelopeSequence: Long = 1,
    val lastFallbackAttemptAtMillis: Long? = null,
    val lastFallbackAttemptEpisodeId: Long? = null,
    val lastCoveredTelemetrySequence: Long? = null,
    val rateWindowStartedAtMillis: Long? = null,
    val fallbackAttemptsInRateWindow: Int = 0,
    val latestTelemetrySequence: Long? = null,
    val latestBatteryPercent: Int? = null,
)

sealed interface DegradedConnectivityEvent {
    val atMillis: Long

    data class JourneyActivated(
        val journeyId: String,
        val validatedInternetAvailable: Boolean,
        val fallbackBindingProvisioned: Boolean,
        override val atMillis: Long,
    ) : DegradedConnectivityEvent

    data class ValidatedInternetLost(override val atMillis: Long) : DegradedConnectivityEvent

    data class ValidatedInternetAvailable(override val atMillis: Long) : DegradedConnectivityEvent

    data class AuthenticatedCloudSuccess(
        override val atMillis: Long,
        val establishesFreshContact: Boolean,
    ) : DegradedConnectivityEvent

    data class RetryableCloudFailure(override val atMillis: Long) : DegradedConnectivityEvent

    data class TelemetryObserved(
        val observation: TelemetryObservation,
        override val atMillis: Long,
    ) : DegradedConnectivityEvent

    data class TransportAvailabilityChanged(
        val available: Boolean,
        override val atMillis: Long,
    ) : DegradedConnectivityEvent

    data class FallbackCapabilityChanged(
        val available: Boolean,
        override val atMillis: Long,
    ) : DegradedConnectivityEvent

    data class FallbackAttemptAllocated(
        val envelopeSequence: Long,
        val coveredTelemetrySequence: Long,
        override val atMillis: Long,
    ) : DegradedConnectivityEvent

    data class TimeAdvanced(override val atMillis: Long) : DegradedConnectivityEvent

    data class JourneyCompleted(override val atMillis: Long) : DegradedConnectivityEvent
}

sealed interface DegradedConnectivityAction {
    data class AllocateFallbackAttempt(
        val journeyId: String,
        val degradationEpisodeId: Long,
        val envelopeSequence: Long,
        val telemetrySequence: Long,
    ) : DegradedConnectivityAction

    data class StopOrdinaryFallback(
        val journeyId: String,
        val stoppedAtMillis: Long,
    ) : DegradedConnectivityAction

    data class SupersedeUnsentFallback(
        val journeyId: String,
        val recoveredAtMillis: Long,
    ) : DegradedConnectivityAction
}

data class DegradedConnectivityReduction(
    val state: DegradedConnectivityState,
    val actions: List<DegradedConnectivityAction> = emptyList(),
)

class DegradedConnectivityPolicy(
    private val config: DegradedConnectivityPolicyConfig,
) {
    fun reduce(
        previous: DegradedConnectivityState,
        event: DegradedConnectivityEvent,
    ): DegradedConnectivityReduction {
        require(event.atMillis >= 0)
        val updated = when (event) {
            is DegradedConnectivityEvent.JourneyActivated -> activate(event)
            is DegradedConnectivityEvent.ValidatedInternetLost -> onInternetLost(previous, event)
            is DegradedConnectivityEvent.ValidatedInternetAvailable -> onInternetAvailable(previous, event)
            is DegradedConnectivityEvent.AuthenticatedCloudSuccess ->
                onAuthenticatedCloudSuccess(previous, event)
            is DegradedConnectivityEvent.RetryableCloudFailure -> onCloudFailure(previous, event)
            is DegradedConnectivityEvent.TelemetryObserved -> onTelemetry(previous, event)
            is DegradedConnectivityEvent.TransportAvailabilityChanged ->
                previous.copy(transportAvailable = event.available)
            is DegradedConnectivityEvent.FallbackCapabilityChanged ->
                previous.copy(fallbackBindingProvisioned = event.available)
            is DegradedConnectivityEvent.FallbackAttemptAllocated ->
                onFallbackAttempt(previous, event)
            is DegradedConnectivityEvent.TimeAdvanced -> previous
            is DegradedConnectivityEvent.JourneyCompleted ->
                previous.copy(
                    journeyActive = false,
                    fallbackDisposition = FallbackDisposition.INACTIVE,
                    recoveryStartedAtMillis = null,
                )
        }

        val evaluated = evaluate(normalizeRateWindow(updated, event.atMillis), event.atMillis)
        val actions = buildList {
            if (event is DegradedConnectivityEvent.AuthenticatedCloudSuccess &&
                event.establishesFreshContact && previous.journeyActive
            ) {
                previous.journeyId?.let {
                    add(DegradedConnectivityAction.SupersedeUnsentFallback(it, event.atMillis))
                }
            }
            if (event is DegradedConnectivityEvent.JourneyCompleted) {
                previous.journeyId?.let {
                    add(DegradedConnectivityAction.StopOrdinaryFallback(it, event.atMillis))
                }
            }
            if (evaluated.fallbackDisposition == FallbackDisposition.ELIGIBLE) {
                add(
                    DegradedConnectivityAction.AllocateFallbackAttempt(
                        journeyId = requireNotNull(evaluated.journeyId),
                        degradationEpisodeId = requireNotNull(evaluated.degradationEpisodeId),
                        envelopeSequence = evaluated.nextFallbackEnvelopeSequence,
                        telemetrySequence = requireNotNull(evaluated.latestTelemetrySequence),
                    ),
                )
            }
        }
        return DegradedConnectivityReduction(evaluated, actions)
    }

    private fun activate(event: DegradedConnectivityEvent.JourneyActivated) =
        DegradedConnectivityState(
            journeyId = event.journeyId,
            journeyActive = true,
            connectivityPhase = if (event.validatedInternetAvailable) {
                ConnectivityPhase.RECOVERING
            } else {
                ConnectivityPhase.INTERRUPTED
            },
            validatedInternetAvailable = event.validatedInternetAvailable,
            fallbackBindingProvisioned = event.fallbackBindingProvisioned,
            interruptionStartedAtMillis = if (event.validatedInternetAvailable) null else event.atMillis,
            recoveryStartedAtMillis = if (event.validatedInternetAvailable) event.atMillis else null,
        )

    private fun onInternetLost(
        state: DegradedConnectivityState,
        event: DegradedConnectivityEvent.ValidatedInternetLost,
    ): DegradedConnectivityState {
        if (!state.journeyActive) return state.copy(validatedInternetAvailable = false)
        val resumeEpisode = state.connectivityPhase == ConnectivityPhase.RECOVERING &&
            state.degradationEpisodeId != null
        return state.copy(
            connectivityPhase = if (resumeEpisode) ConnectivityPhase.DEGRADED else {
                if (state.connectivityPhase == ConnectivityPhase.DEGRADED) {
                    ConnectivityPhase.DEGRADED
                } else {
                    ConnectivityPhase.INTERRUPTED
                }
            },
            validatedInternetAvailable = false,
            interruptionStartedAtMillis = state.interruptionStartedAtMillis ?: event.atMillis,
            recoveryStartedAtMillis = null,
        )
    }

    private fun onInternetAvailable(
        state: DegradedConnectivityState,
        event: DegradedConnectivityEvent.ValidatedInternetAvailable,
    ): DegradedConnectivityState {
        if (!state.journeyActive) return state.copy(validatedInternetAvailable = true)
        if (state.connectivityPhase == ConnectivityPhase.HEALTHY) {
            return state.copy(validatedInternetAvailable = true)
        }
        return state.copy(
            connectivityPhase = ConnectivityPhase.RECOVERING,
            fallbackDisposition = FallbackDisposition.INACTIVE,
            validatedInternetAvailable = true,
            recoveryStartedAtMillis = state.recoveryStartedAtMillis ?: event.atMillis,
        )
    }

    private fun onAuthenticatedCloudSuccess(
        state: DegradedConnectivityState,
        event: DegradedConnectivityEvent.AuthenticatedCloudSuccess,
    ): DegradedConnectivityState {
        val common = state.copy(
            validatedInternetAvailable = true,
            lastAuthenticatedCloudSuccessAtMillis = event.atMillis,
            consecutiveRetryableCloudFailures = 0,
        )
        if (!event.establishesFreshContact || !state.journeyActive) return common
        return common.copy(
            connectivityPhase = ConnectivityPhase.HEALTHY,
            fallbackDisposition = FallbackDisposition.INACTIVE,
            degradationEpisodeId = null,
            interruptionStartedAtMillis = null,
            recoveryStartedAtMillis = null,
        )
    }

    private fun onCloudFailure(
        state: DegradedConnectivityState,
        event: DegradedConnectivityEvent.RetryableCloudFailure,
    ): DegradedConnectivityState {
        if (!state.journeyActive) return state
        val recoveringExistingEpisode = state.connectivityPhase == ConnectivityPhase.RECOVERING &&
            state.degradationEpisodeId != null
        return state.copy(
            connectivityPhase = if (recoveringExistingEpisode) {
                ConnectivityPhase.DEGRADED
            } else if (state.connectivityPhase == ConnectivityPhase.HEALTHY ||
                state.connectivityPhase == ConnectivityPhase.RECOVERING
            ) {
                ConnectivityPhase.INTERRUPTED
            } else {
                state.connectivityPhase
            },
            validatedInternetAvailable = false,
            interruptionStartedAtMillis = state.interruptionStartedAtMillis ?: event.atMillis,
            consecutiveRetryableCloudFailures =
                if (state.consecutiveRetryableCloudFailures == Int.MAX_VALUE) {
                    Int.MAX_VALUE
                } else {
                    state.consecutiveRetryableCloudFailures + 1
                },
            recoveryStartedAtMillis = null,
        )
    }

    private fun onTelemetry(
        state: DegradedConnectivityState,
        event: DegradedConnectivityEvent.TelemetryObserved,
    ): DegradedConnectivityState {
        require(event.observation.sequence >= 0)
        if (!state.journeyActive || event.observation.journeyId != state.journeyId) return state
        if (state.latestTelemetrySequence != null &&
            event.observation.sequence <= state.latestTelemetrySequence
        ) return state
        return state.copy(
            latestTelemetrySequence = event.observation.sequence,
            latestBatteryPercent = event.observation.batteryPercent,
        )
    }

    private fun onFallbackAttempt(
        state: DegradedConnectivityState,
        event: DegradedConnectivityEvent.FallbackAttemptAllocated,
    ): DegradedConnectivityState {
        require(event.envelopeSequence >= 1)
        require(event.coveredTelemetrySequence >= 0)
        if (event.envelopeSequence < state.nextFallbackEnvelopeSequence) return state
        require(event.envelopeSequence == state.nextFallbackEnvelopeSequence) {
            "Fallback attempts must be persisted in envelope sequence order."
        }
        val episodeId = requireNotNull(state.degradationEpisodeId) {
            "A fallback attempt requires an active degradation episode."
        }
        return state.copy(
            fallbackDisposition = FallbackDisposition.ALLOCATED,
            nextFallbackEnvelopeSequence = event.envelopeSequence + 1,
            lastFallbackAttemptAtMillis = event.atMillis,
            lastFallbackAttemptEpisodeId = episodeId,
            lastCoveredTelemetrySequence = maxOf(
                state.lastCoveredTelemetrySequence ?: 0,
                event.coveredTelemetrySequence,
            ),
            rateWindowStartedAtMillis = state.rateWindowStartedAtMillis ?: event.atMillis,
            fallbackAttemptsInRateWindow = state.fallbackAttemptsInRateWindow + 1,
        )
    }

    private fun normalizeRateWindow(
        state: DegradedConnectivityState,
        nowMillis: Long,
    ): DegradedConnectivityState {
        val startedAt = state.rateWindowStartedAtMillis ?: return state
        if (nowMillis - startedAt < config.fallbackRateWindowMillis) return state
        return state.copy(
            rateWindowStartedAtMillis = null,
            fallbackAttemptsInRateWindow = 0,
        )
    }

    private fun evaluate(
        state: DegradedConnectivityState,
        nowMillis: Long,
    ): DegradedConnectivityState {
        if (!state.journeyActive) {
            return state.copy(fallbackDisposition = FallbackDisposition.INACTIVE)
        }

        val recoveryTimedOut = state.connectivityPhase == ConnectivityPhase.RECOVERING &&
            state.degradationEpisodeId != null &&
            state.recoveryStartedAtMillis?.let { nowMillis - it >= config.recoveryGraceMillis } == true
        val degraded = if (recoveryTimedOut) {
            state.copy(
                connectivityPhase = ConnectivityPhase.DEGRADED,
                recoveryStartedAtMillis = null,
            )
        } else if (state.connectivityPhase == ConnectivityPhase.INTERRUPTED &&
            shouldEnterDegraded(state, nowMillis)
        ) {
            val episodeId = state.lastDegradationEpisodeId + 1
            state.copy(
                connectivityPhase = ConnectivityPhase.DEGRADED,
                degradationEpisodeId = episodeId,
                lastDegradationEpisodeId = episodeId,
            )
        } else {
            state
        }

        if (degraded.connectivityPhase != ConnectivityPhase.DEGRADED) {
            return degraded.copy(fallbackDisposition = FallbackDisposition.INACTIVE)
        }
        if (!degraded.fallbackBindingProvisioned || degraded.latestTelemetrySequence == null) {
            return degraded.copy(fallbackDisposition = FallbackDisposition.UNAVAILABLE)
        }

        val episodeId = requireNotNull(degraded.degradationEpisodeId)
        val firstAttemptDue = degraded.lastFallbackAttemptEpisodeId != episodeId
        val resendDue = !firstAttemptDue &&
            degraded.latestTelemetrySequence > (degraded.lastCoveredTelemetrySequence ?: -1) &&
            degraded.lastFallbackAttemptAtMillis?.let {
                nowMillis - it >= config.minimumFallbackIntervalMillis
            } == true
        val rateAvailable = degraded.fallbackAttemptsInRateWindow <
            config.maximumFallbackAttemptsPerWindow

        return degraded.copy(
            fallbackDisposition = if ((firstAttemptDue || resendDue) && rateAvailable) {
                FallbackDisposition.ELIGIBLE
            } else {
                if (degraded.lastFallbackAttemptEpisodeId != null) {
                    FallbackDisposition.ALLOCATED
                } else {
                    FallbackDisposition.UNAVAILABLE
                }
            },
        )
    }

    private fun shouldEnterDegraded(state: DegradedConnectivityState, nowMillis: Long): Boolean {
        val elapsedThresholdReached = state.interruptionStartedAtMillis?.let {
            nowMillis - it >= config.degradationAfterMillis
        } == true
        return elapsedThresholdReached ||
            state.consecutiveRetryableCloudFailures >= config.minimumRetryableCloudFailures
    }
}
