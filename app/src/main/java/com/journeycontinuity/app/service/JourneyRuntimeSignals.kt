package com.journeycontinuity.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current-process evidence only; persisted Journey and connectivity rows remain authoritative. */
data class CurrentJourneyConnectivity(
    val journeyId: String,
    val validatedInternetAvailable: Boolean,
)

internal class JourneyRuntimeSignals {
    private var generation = 0L
    private var currentJourneyId: String? = null
    private val _monitoringJourneyId = MutableStateFlow<String?>(null)
    private val _currentConnectivity = MutableStateFlow<CurrentJourneyConnectivity?>(null)

    val monitoringJourneyId: StateFlow<String?> = _monitoringJourneyId.asStateFlow()
    val currentConnectivity: StateFlow<CurrentJourneyConnectivity?> =
        _currentConnectivity.asStateFlow()

    @Synchronized
    fun begin(journeyId: String): Long {
        generation++
        currentJourneyId = journeyId
        _monitoringJourneyId.value = null
        _currentConnectivity.value = null
        return generation
    }

    @Synchronized
    fun listenerRegistered(forGeneration: Long, journeyId: String) {
        if (isCurrent(forGeneration, journeyId)) _monitoringJourneyId.value = journeyId
    }

    @Synchronized
    fun connectivityReconciled(
        forGeneration: Long,
        journeyId: String,
        validatedInternetAvailable: Boolean,
    ) {
        if (isCurrent(forGeneration, journeyId)) {
            _currentConnectivity.value = CurrentJourneyConnectivity(
                journeyId, validatedInternetAvailable,
            )
        }
    }

    /** The startup snapshot must not overwrite a newer network callback. */
    @Synchronized
    fun initialConnectivityReconciled(
        forGeneration: Long,
        journeyId: String,
        validatedInternetAvailable: Boolean,
    ) {
        if (isCurrent(forGeneration, journeyId) && _currentConnectivity.value == null) {
            _currentConnectivity.value = CurrentJourneyConnectivity(
                journeyId, validatedInternetAvailable,
            )
        }
    }

    @Synchronized
    fun stop() {
        generation++
        currentJourneyId = null
        _monitoringJourneyId.value = null
        _currentConnectivity.value = null
    }

    private fun isCurrent(forGeneration: Long, journeyId: String): Boolean =
        generation == forGeneration && currentJourneyId == journeyId
}
