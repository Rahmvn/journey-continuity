package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.domain.CompleteJourneyResult
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.StartJourneyResult
import com.journeycontinuity.app.service.JourneyServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JourneyUiState(
    val isRestoring: Boolean = true,
    val activeJourney: Journey? = null,
    val isActionInProgress: Boolean = false,
    val message: String? = null,
)

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
                .catch { error ->
                    _uiState.update {
                        it.copy(isRestoring = false, message = "Could not read saved journey: ${error.message}")
                    }
                }
                .collect { active ->
                    _uiState.update { it.copy(isRestoring = false, activeJourney = active) }
                    if (active != null) runCatching { serviceController.start() }
                }
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
                            val serviceError = runCatching { serviceController.start() }.exceptionOrNull()
                            _uiState.update {
                                it.copy(
                                    message = serviceError?.let { error ->
                                        "Journey saved, but monitoring service could not start: ${error.message}"
                                    },
                                )
                            }
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
