package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.domain.CompleteJourneyResult
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.JourneyInputValidation
import com.journeycontinuity.app.domain.StartJourneyResult
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.service.JourneyServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JourneyUiState(
    val isRestoring: Boolean = true,
    val activeJourney: Journey? = null,
    val telemetryCount: Long = 0,
    val latestTelemetry: TelemetryObservation? = null,
    val isActionInProgress: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyViewModel(
    repository: JourneyRepository,
    private val lifecycle: JourneyLifecycle,
    private val serviceController: JourneyServiceController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(JourneyUiState())
    val uiState: StateFlow<JourneyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.activeJourney
                .flatMapLatest { active ->
                    if (active == null) {
                        flowOf(Triple<Journey?, Long, TelemetryObservation?>(null, 0, null))
                    } else {
                        repository.observeTelemetry(active.id).map { summary ->
                            Triple(active, summary.count, summary.latest)
                        }
                    }
                }
                .catch { error ->
                    _uiState.update {
                        it.copy(isRestoring = false, message = "Could not read saved journey: ${error.message}")
                    }
                }
                .collect { (active, count, latest) ->
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            activeJourney = active,
                            telemetryCount = count,
                            latestTelemetry = latest,
                        )
                    }
                }
        }
    }

    fun validateStart(destination: String, expectedArrivalAt: Long): Boolean =
        when (lifecycle.validate(destination, expectedArrivalAt)) {
            JourneyInputValidation.Valid -> true
            JourneyInputValidation.BlankDestination -> {
                setMessage("Enter a destination.")
                false
            }
            JourneyInputValidation.ExpectedArrivalNotFuture -> {
                setMessage("Expected arrival must be in the future.")
                false
            }
        }

    fun startJourney(destination: String, expectedArrivalAt: Long) {
        if (_uiState.value.isActionInProgress || _uiState.value.activeJourney != null) return
        _uiState.update { it.copy(isActionInProgress = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { lifecycle.start(destination, expectedArrivalAt) }
            result.fold(
                onSuccess = { startResult ->
                    when (startResult) {
                        is StartJourneyResult.Started -> {
                            // The visible Activity starts the location FGS after Room emits this
                            // Journey, ensuring while-in-use prerequisites remain satisfied.
                        }
                        StartJourneyResult.BlankDestination -> setMessage("Enter a destination.")
                        StartJourneyResult.ExpectedArrivalNotFuture -> setMessage("Expected arrival must be in the future.")
                        StartJourneyResult.ActiveJourneyAlreadyExists -> setMessage("A journey is already active.")
                    }
                },
                onFailure = { setMessage("Could not start journey: ${it.message}") },
            )
            _uiState.update { it.copy(isActionInProgress = false) }
        }
    }

    fun endJourney() {
        if (_uiState.value.isActionInProgress || _uiState.value.activeJourney == null) return
        _uiState.update { it.copy(isActionInProgress = true, message = null) }
        viewModelScope.launch {
            runCatching { lifecycle.complete() }.fold(
                onSuccess = { result ->
                    when (result) {
                        is CompleteJourneyResult.Completed -> {
                            serviceController.stop()
                            setMessage("Journey completed.")
                        }
                        CompleteJourneyResult.NoActiveJourney -> {
                            serviceController.stop()
                            setMessage("There is no active journey.")
                        }
                    }
                },
                onFailure = { setMessage("Could not end journey: ${it.message}") },
            )
            _uiState.update { it.copy(isActionInProgress = false) }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun showMessage(message: String) = setMessage(message)

    private fun setMessage(message: String) = _uiState.update { it.copy(message = message) }
}

class JourneyViewModelFactory(
    private val repository: JourneyRepository,
    private val lifecycle: JourneyLifecycle,
    private val serviceController: JourneyServiceController,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        JourneyViewModel(repository, lifecycle, serviceController) as T
}
