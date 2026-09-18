package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.domain.CompleteJourneyResult
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.JourneyInputValidation
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.StartJourneyResult
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.service.JourneyServiceController
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.SyncFailureKind
import com.journeycontinuity.app.trusted.TrustedContactGateway
import com.journeycontinuity.app.trusted.TrustedContactSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TrustedContactsAvailability {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

internal data class TrustedContactsFailurePresentation(
    val availability: TrustedContactsAvailability,
    val message: String,
)

internal fun trustedContactsFailurePresentation(error: Throwable) = when {
    error is CloudSyncException && error.kind == SyncFailureKind.TRANSIENT ->
        TrustedContactsFailurePresentation(
            TrustedContactsAvailability.UNAVAILABLE,
            "Trusted contacts unavailable while reconnecting.",
        )
    error is CloudSyncException && error.kind == SyncFailureKind.AUTHENTICATION ->
        TrustedContactsFailurePresentation(
            TrustedContactsAvailability.UNAVAILABLE,
            "Trusted contacts unavailable because traveller identity needs attention.",
        )
    else -> TrustedContactsFailurePresentation(
        TrustedContactsAvailability.UNAVAILABLE,
        "Trusted contacts are currently unavailable.",
    )
}

data class JourneyUiState(
    val isRestoring: Boolean = true,
    val activeJourney: Journey? = null,
    val telemetryCount: Long = 0,
    val latestTelemetry: TelemetryObservation? = null,
    val syncState: JourneySyncState? = null,
    val monitoringState: CloudMonitoringState? = null,
    val isActionInProgress: Boolean = false,
    val trustedContacts: List<TrustedContactSummary> = emptyList(),
    val trustedContactsAvailability: TrustedContactsAvailability = TrustedContactsAvailability.LOADING,
    val trustedContactsUnavailableMessage: String? = null,
    val trustedContactActionInProgress: Boolean = false,
    val invitationShareUrl: String? = null,
    val message: String? = null,
)

internal fun JourneyUiState.withTrustedContactsFailure(error: Throwable): JourneyUiState {
    val presentation = trustedContactsFailurePresentation(error)
    return copy(
        trustedContactsAvailability = presentation.availability,
        trustedContactsUnavailableMessage = presentation.message,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyViewModel(
    repository: JourneyRepository,
    private val lifecycle: JourneyLifecycle,
    private val serviceController: JourneyServiceController,
    private val trustedContactGateway: TrustedContactGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(JourneyUiState())
    val uiState: StateFlow<JourneyUiState> = _uiState.asStateFlow()

    init {
        refreshTrustedContacts()
        viewModelScope.launch {
            repository.activeJourney
                .flatMapLatest { active ->
                    if (active == null) {
                        flowOf(ActiveJourneyUiData())
                    } else {
                        combine(
                            repository.observeTelemetry(active.id),
                            repository.observeSyncState(active.id),
                            repository.observeMonitoringState(active.id),
                        ) { summary, syncState, monitoringState ->
                            ActiveJourneyUiData(
                                journey = active,
                                telemetryCount = summary.count,
                                latestTelemetry = summary.latest,
                                syncState = syncState,
                                monitoringState = monitoringState,
                            )
                        }
                    }
                }
                .catch { error ->
                    _uiState.update {
                        it.copy(isRestoring = false, message = "Could not read saved journey: ${error.message}")
                    }
                }
                .collect { activeData ->
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            activeJourney = activeData.journey,
                            telemetryCount = activeData.telemetryCount,
                            latestTelemetry = activeData.latestTelemetry,
                            syncState = activeData.syncState,
                            monitoringState = activeData.monitoringState,
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

    fun refreshTrustedContacts() {
        if (_uiState.value.trustedContactActionInProgress) return
        _uiState.update {
            it.copy(
                trustedContactsAvailability = TrustedContactsAvailability.LOADING,
                trustedContactsUnavailableMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { trustedContactGateway.list() }.fold(
                onSuccess = { contacts ->
                    _uiState.update {
                        it.copy(
                            trustedContacts = contacts,
                            trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE,
                            trustedContactsUnavailableMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    val presentation = trustedContactsFailurePresentation(error)
                    _uiState.update { it.withTrustedContactsFailure(error) }
                    setMessage(presentation.message)
                },
            )
        }
    }

    fun createTrustedContact(displayName: String, email: String) {
        if (_uiState.value.trustedContactActionInProgress) return
        val normalizedName = displayName.trim()
        val normalizedEmail = email.trim().lowercase()
        if (normalizedName.isEmpty()) {
            setMessage("Enter the trusted contact's name.")
            return
        }
        if ('@' !in normalizedEmail || normalizedEmail.length !in 3..320) {
            setMessage("Enter a valid trusted contact email.")
            return
        }
        _uiState.update { it.copy(trustedContactActionInProgress = true, invitationShareUrl = null) }
        viewModelScope.launch {
            runCatching { trustedContactGateway.create(normalizedName, normalizedEmail) }.fold(
                onSuccess = { invitation ->
                    val contactsResult = runCatching { trustedContactGateway.list() }
                    _uiState.update { state ->
                        val refreshed = contactsResult.fold(
                            onSuccess = { contacts -> state.copy(
                                trustedContacts = contacts,
                                trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE,
                                trustedContactsUnavailableMessage = null,
                            ) },
                            onFailure = state::withTrustedContactsFailure,
                        )
                        refreshed.copy(
                            trustedContactActionInProgress = false,
                            invitationShareUrl = invitation.shareUrl,
                        )
                    }
                    setMessage("Invitation created. Share this one-time link with ${invitation.displayName}.")
                },
                onFailure = { error ->
                    _uiState.update { it.copy(trustedContactActionInProgress = false) }
                    setMessage("Could not create invitation: ${error.message}")
                },
            )
        }
    }

    fun revokeTrustedContact(subjectId: String) {
        if (_uiState.value.trustedContactActionInProgress) return
        _uiState.update { it.copy(trustedContactActionInProgress = true, invitationShareUrl = null) }
        viewModelScope.launch {
            runCatching { trustedContactGateway.revoke(subjectId) }.fold(
                onSuccess = { revoked ->
                    val contactsResult = runCatching { trustedContactGateway.list() }
                    _uiState.update { state ->
                        val refreshed = contactsResult.fold(
                            onSuccess = { contacts -> state.copy(
                                trustedContacts = contacts,
                                trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE,
                                trustedContactsUnavailableMessage = null,
                            ) },
                            onFailure = state::withTrustedContactsFailure,
                        )
                        refreshed.copy(
                            trustedContactActionInProgress = false,
                        )
                    }
                    setMessage(if (revoked) "Trusted contact access revoked." else "This contact was already inactive.")
                },
                onFailure = { error ->
                    _uiState.update { it.copy(trustedContactActionInProgress = false) }
                    setMessage("Could not revoke trusted contact: ${error.message}")
                },
            )
        }
    }

    fun clearInvitationShareUrl() = _uiState.update { it.copy(invitationShareUrl = null) }

    fun showMessage(message: String) = setMessage(message)

    private fun setMessage(message: String) = _uiState.update { it.copy(message = message) }
}

private data class ActiveJourneyUiData(
    val journey: Journey? = null,
    val telemetryCount: Long = 0,
    val latestTelemetry: TelemetryObservation? = null,
    val syncState: JourneySyncState? = null,
    val monitoringState: CloudMonitoringState? = null,
)

class JourneyViewModelFactory(
    private val repository: JourneyRepository,
    private val lifecycle: JourneyLifecycle,
    private val serviceController: JourneyServiceController,
    private val trustedContactGateway: TrustedContactGateway,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        JourneyViewModel(repository, lifecycle, serviceController, trustedContactGateway) as T
}
