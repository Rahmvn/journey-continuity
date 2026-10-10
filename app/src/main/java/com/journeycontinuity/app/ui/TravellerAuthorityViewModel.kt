package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.data.local.TravellerAuthorityRecord
import com.journeycontinuity.app.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TravellerAuthorityUiState(
    val records: List<TravellerAuthorityRecord>? = null,
    val busyJourneyId: String? = null,
    val checking: Boolean = false,
    val outcomeJourneyId: String? = null,
    val outcome: String? = null,
    val error: String? = null,
    val offline: Boolean = false,
    val returnedFromNetworkSettings: Boolean = false,
) {
    val terminalResult get() = records?.let(::selectTravellerTerminalResult)
    val result get() = terminalResult?.unresolved
    val automaticResult get() = terminalResult?.automatic
    val pending get() = records?.firstOrNull { it.isPendingAuthority() }
}

class TravellerAuthorityViewModel(private val actions: TravellerAuthorityActions,
    private val isOffline: () -> Boolean) : ViewModel() {
    private var networkSettingsOpened = false
    private val mutable = MutableStateFlow(TravellerAuthorityUiState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {
        actions.records.catch { mutable.update { it.copy(error = "Could not read saved Journey status.") } }
            .collect { rows -> mutable.update { old ->
                val previous = old.records?.find { it.journey.id == old.outcomeJourneyId }
                val current = rows.find { it.journey.id == old.outcomeJourneyId }
                old.copy(records = rows, outcome = old.outcome.takeIf { previous == current },
                    offline = isOffline())
            } }
    } }
    fun markNetworkSettingsOpened() { networkSettingsOpened = true }
    fun refreshConnectivity() {
        val returned = networkSettingsOpened
        networkSettingsOpened = false
        mutable.update { it.copy(offline = isOffline(), returnedFromNetworkSettings = returned) }
    }
    fun check(id: String) = perform(id, checking = true) {
        val offline = isOffline()
        mutable.update { it.copy(offline = offline, returnedFromNetworkSettings = false) }
        if (!offline) {
            val outcome = actions.retryFailed(id)
            mutable.update { it.copy(outcomeJourneyId = id, outcome = outcome) }
        }
    }
    fun complete(id: String) = perform(id) {
        if (!actions.complete(id)) error("Action no longer available. Check the saved Journey status.")
    }
    fun stop(id: String) = perform(id) {
        if (!actions.stop(id)) error("Stop could not be recorded. Check your account and the saved Journey status.")
    }
    fun acknowledge(result: UnresolvedJourneyResult) = perform(result.journeyId) {
        if (!actions.acknowledge(result)) error("Could not acknowledge this exact result. Please try again.")
    }
    private fun perform(id: String, checking: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busyJourneyId != null) return
        mutable.update { it.copy(busyJourneyId = id, checking = checking, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(error = if (e is IllegalStateException) e.message
                    else "Could not save this operation. Please try again.") }
            } finally { mutable.update { it.copy(busyJourneyId = null, checking = false) } }
        }
    }
}

class TravellerAuthorityViewModelFactory(private val actions: TravellerAuthorityActions,
    private val isOffline: () -> Boolean) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TravellerAuthorityViewModel(actions, isOffline) as T
}
