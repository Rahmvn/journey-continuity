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
    private var serviceGeneration = 0L
    private var latestStartId = 0
    private var currentJourneyId: String? = null
    private val _monitoringJourneyId = MutableStateFlow<String?>(null)
    private val _activeRuntimeJourneyId = MutableStateFlow<String?>(null)
    private val _serviceRunning = MutableStateFlow(false)
    private val _currentConnectivity = MutableStateFlow<CurrentJourneyConnectivity?>(null)

    val monitoringJourneyId: StateFlow<String?> = _monitoringJourneyId.asStateFlow()
    val activeRuntimeJourneyId: StateFlow<String?> = _activeRuntimeJourneyId.asStateFlow()
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()
    val currentConnectivity: StateFlow<CurrentJourneyConnectivity?> =
        _currentConnectivity.asStateFlow()

    @Synchronized
    fun begin(journeyId: String): Long {
        generation++
        currentJourneyId = journeyId
        _activeRuntimeJourneyId.value = journeyId
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
        _activeRuntimeJourneyId.value = null
    }

    @Synchronized
    fun serviceStarted(): Long { serviceGeneration++; _serviceRunning.value = true; return serviceGeneration }

    @Synchronized fun startCommand(startId: Int) { latestStartId = startId }

    @Synchronized fun stopTicket(journeyId: String): JourneyRuntimeStopTicket? =
        if (currentJourneyId == journeyId) JourneyRuntimeStopTicket(journeyId, generation, serviceGeneration, latestStartId)
        else null

    /** Execution-time identity and Android start IDs also protect newer queued service starts. */
    @Synchronized fun executeStop(ticket: JourneyRuntimeStopTicket, stopSelfResult: (Int) -> Boolean,
                                  stopCollection: () -> Unit): Boolean {
        if (ticket.serviceGeneration != serviceGeneration || !isCurrent(ticket.generation, ticket.journeyId)) return true
        if (ticket.startId != latestStartId || !stopSelfResult(ticket.startId)) return false
        stopCollection()
        return true
    }

    @Synchronized fun stopRegistration(forGeneration: Long?) {
        if (forGeneration != null && forGeneration == generation) stop()
    }

    @Synchronized
    fun serviceStopped() { _serviceRunning.value = false }

    @Synchronized fun serviceStopped(forServiceGeneration: Long) {
        if (forServiceGeneration == serviceGeneration) _serviceRunning.value = false
    }

    private fun isCurrent(forGeneration: Long, journeyId: String): Boolean =
        generation == forGeneration && currentJourneyId == journeyId
}

internal data class JourneyRuntimeStopTicket(val journeyId: String, val generation: Long,
    val serviceGeneration: Long, val startId: Int)
