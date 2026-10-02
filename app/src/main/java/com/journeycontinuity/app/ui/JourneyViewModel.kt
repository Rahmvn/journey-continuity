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
import com.journeycontinuity.app.degraded.DegradedConnectivityCoordinator
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.service.JourneyServiceController
import com.journeycontinuity.app.service.JourneyForegroundService
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.SyncFailureKind
import com.journeycontinuity.app.trusted.TrustedContactGateway
import com.journeycontinuity.app.trusted.TrustedContactSummary
import com.journeycontinuity.app.trusted.TrustedContactStatus
import com.journeycontinuity.app.trusted.TrustedContactSubjectKind
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TrustedContactsAvailability {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

enum class JourneyProductRoute { HOME, START, CHECKPOINT, ACTIVE, CONTACTS, RESILIENCE }
enum class TrustedContactsPage { LIST, ADD, READY }

internal fun trustedContactsReturnRoute(route: JourneyProductRoute): JourneyProductRoute =
    if (route == JourneyProductRoute.CHECKPOINT) JourneyProductRoute.START
    else JourneyProductRoute.HOME

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
    val degradation: DegradedConnectivityState? = null,
    val monitoringJourneyId: String? = null,
    val currentConnectivity: CurrentJourneyConnectivity? = null,
    val productRoute: JourneyProductRoute = JourneyProductRoute.HOME,
    val utilityReturnRoute: JourneyProductRoute = JourneyProductRoute.HOME,
    val resiliencePage: ResiliencePage = ResiliencePage.STATUS,
    val draftDestination: String = "",
    val draftExpectedArrivalAt: Long? = null,
    val isActionInProgress: Boolean = false,
    val trustedContacts: List<TrustedContactSummary> = emptyList(),
    val trustedContactsAvailability: TrustedContactsAvailability = TrustedContactsAvailability.LOADING,
    val trustedContactsUnavailableMessage: String? = null,
    val trustedContactActionInProgress: Boolean = false,
    val trustedContactsPage: TrustedContactsPage = TrustedContactsPage.LIST,
    val invitationReadyId: String? = null,
    val invitationReadyName: String? = null,
    val invitationReadyEmail: String? = null,
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

internal fun JourneyUiState.withTrustedContactsSnapshot(
    contacts: List<TrustedContactSummary>,
): JourneyUiState {
    val readyInvitationIsPending = invitationReadyId == null || contacts.any {
        it.id == invitationReadyId && it.kind == TrustedContactSubjectKind.INVITATION &&
            it.status == TrustedContactStatus.PENDING
    }
    return copy(
        trustedContacts = contacts,
        trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE,
        trustedContactsUnavailableMessage = null,
        trustedContactsPage = if (readyInvitationIsPending) trustedContactsPage
            else TrustedContactsPage.LIST,
        invitationReadyId = if (readyInvitationIsPending) invitationReadyId else null,
        invitationReadyName = if (readyInvitationIsPending) invitationReadyName else null,
        invitationReadyEmail = if (readyInvitationIsPending) invitationReadyEmail else null,
        invitationShareUrl = if (readyInvitationIsPending) invitationShareUrl else null,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyViewModel(
    repository: JourneyRepository,
    private val lifecycle: JourneyLifecycle,
    private val serviceController: JourneyServiceController,
    private val trustedContactGateway: TrustedContactGateway,
    private val degradedConnectivityCoordinator: DegradedConnectivityCoordinator,
    currentConnectivity: StateFlow<CurrentJourneyConnectivity?> =
        JourneyForegroundService.currentConnectivity,
) : ViewModel() {
    private val _uiState = MutableStateFlow(JourneyUiState())
    val uiState: StateFlow<JourneyUiState> = _uiState.asStateFlow()
    private val trustedContactRequests = Mutex()
    private var trustedRefreshInFlight = false

    init {
        refreshTrustedContacts()
        viewModelScope.launch {
            JourneyForegroundService.monitoringJourneyId.collect { journeyId ->
                _uiState.update { it.copy(monitoringJourneyId = journeyId) }
            }
        }
        viewModelScope.launch {
            currentConnectivity.collect { observation ->
                _uiState.update { it.copy(currentConnectivity = observation) }
            }
        }
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
                            degradedConnectivityCoordinator.observe(active.id),
                        ) { summary, syncState, monitoringState, degradation ->
                            ActiveJourneyUiData(
                                journey = active,
                                telemetryCount = summary.count,
                                latestTelemetry = summary.latest,
                                syncState = syncState,
                                monitoringState = monitoringState,
                                degradation = degradation,
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
                            degradation = activeData.degradation,
                            productRoute = routeAfterJourneyChange(it.productRoute, activeData.journey),
                        )
                    }
                }
        }
    }

    fun openStart() = _uiState.update {
        if (it.activeJourney == null) it.copy(productRoute = JourneyProductRoute.START) else it
    }

    fun openActive() = _uiState.update {
        if (it.activeJourney != null) it.copy(productRoute = JourneyProductRoute.ACTIVE) else it
    }

    fun openContacts() {
        _uiState.update {
            val pendingResult = it.trustedContactsPage == TrustedContactsPage.READY &&
                it.invitationShareUrl != null
            it.copy(productRoute = JourneyProductRoute.CONTACTS,
                utilityReturnRoute = if (it.productRoute == JourneyProductRoute.RESILIENCE)
                    it.utilityReturnRoute else trustedContactsReturnRoute(it.productRoute),
                trustedContactsPage = if (pendingResult) TrustedContactsPage.READY
                else TrustedContactsPage.LIST,
                invitationReadyId = if (pendingResult) it.invitationReadyId else null,
                invitationReadyName = if (pendingResult) it.invitationReadyName else null,
                invitationReadyEmail = if (pendingResult) it.invitationReadyEmail else null,
                invitationShareUrl = if (pendingResult) it.invitationShareUrl else null)
        }
        refreshTrustedContacts()
    }

    fun openAddTrustedContact() = _uiState.update {
        if (it.productRoute == JourneyProductRoute.CONTACTS)
            it.copy(trustedContactsPage = TrustedContactsPage.ADD) else it
    }

    fun doneWithInvitation() {
        _uiState.update {
            it.copy(trustedContactsPage = TrustedContactsPage.LIST,
                invitationReadyId = null,
                invitationReadyName = null, invitationReadyEmail = null,
                invitationShareUrl = null)
        }
        refreshTrustedContacts()
    }

    fun openResilience() {
        _uiState.update {
            it.copy(productRoute = JourneyProductRoute.RESILIENCE,
                resiliencePage = ResiliencePage.STATUS,
                utilityReturnRoute = if (it.productRoute == JourneyProductRoute.CHECKPOINT)
                    JourneyProductRoute.START else JourneyProductRoute.HOME)
        }
        refreshTrustedContacts()
    }

    fun openChooseSim() = _uiState.update {
        if (it.productRoute == JourneyProductRoute.RESILIENCE)
            it.copy(resiliencePage = ResiliencePage.CHOOSE_SIM) else it
    }

    fun returnToResilienceStatus() = _uiState.update {
        it.copy(resiliencePage = ResiliencePage.STATUS)
    }

    fun closeUtility() = _uiState.update {
        it.copy(productRoute = it.utilityReturnRoute,
            resiliencePage = ResiliencePage.STATUS,
            trustedContactsPage = TrustedContactsPage.LIST,
            invitationReadyId = null,
            invitationReadyName = null, invitationReadyEmail = null,
            invitationShareUrl = null)
    }

    fun backToHome() = _uiState.update { it.copy(productRoute = JourneyProductRoute.HOME) }

    fun backToStart() = _uiState.update { it.copy(productRoute = JourneyProductRoute.START) }

    fun updateDestination(value: String) = _uiState.update { it.copy(draftDestination = value) }

    fun updateExpectedArrival(value: Long) = _uiState.update {
        it.copy(draftExpectedArrivalAt = value)
    }

    /** The checkpoint is advisory; it never changes Journey eligibility. */
    fun requestStart(smsFallbackReady: Boolean, begin: (String, Long) -> Unit) {
        val state = _uiState.value
        if (state.isActionInProgress || state.activeJourney != null) return
        val arrival = state.draftExpectedArrivalAt ?: return
        if (!validateStart(state.draftDestination, arrival)) return
        val accepted = state.trustedContacts.any { it.status == TrustedContactStatus.ACCEPTED }
        if (needsResilienceCheckpoint(accepted, smsFallbackReady)) {
            _uiState.update { it.copy(productRoute = JourneyProductRoute.CHECKPOINT) }
        } else begin(state.draftDestination, arrival)
    }

    fun startAnyway(begin: (String, Long) -> Unit) {
        val state = _uiState.value
        val arrival = state.draftExpectedArrivalAt ?: return
        if (state.productRoute == JourneyProductRoute.CHECKPOINT &&
            !state.isActionInProgress && validateStart(state.draftDestination, arrival)) {
            begin(state.draftDestination, arrival)
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
        if (_uiState.value.trustedContactActionInProgress || trustedRefreshInFlight) return
        trustedRefreshInFlight = true
        _uiState.update {
            it.copy(
                trustedContactsAvailability = if (it.trustedContactsAvailability ==
                    TrustedContactsAvailability.AVAILABLE) TrustedContactsAvailability.AVAILABLE
                else TrustedContactsAvailability.LOADING,
                trustedContactsUnavailableMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                runCatching { trustedContactRequests.withLock { trustedContactGateway.list() } }.fold(
                    onSuccess = { contacts ->
                        _uiState.update { it.withTrustedContactsSnapshot(contacts) }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        val presentation = trustedContactsFailurePresentation(error)
                        _uiState.update { it.withTrustedContactsFailure(error) }
                        setMessage(presentation.message)
                    },
                )
            } finally {
                trustedRefreshInFlight = false
            }
        }
    }

    fun createTrustedContact(displayName: String, email: String) {
        if (_uiState.value.trustedContactActionInProgress) return
        val normalizedName = displayName.trim()
        val normalizedEmail = email.trim().lowercase()
        if (normalizedName.length !in 1..100) {
            setMessage("Enter the trusted contact's name.")
            return
        }
        if ('@' !in normalizedEmail || normalizedEmail.length !in 3..320) {
            setMessage("Enter a valid trusted contact email.")
            return
        }
        if (!trustedContactGateway.invitationSharingAvailable) {
            setMessage("Trusted contact invitation sharing is unavailable.")
            return
        }
        _uiState.update { it.copy(trustedContactActionInProgress = true, invitationShareUrl = null) }
        viewModelScope.launch {
            runCatching { trustedContactRequests.withLock {
                trustedContactGateway.create(normalizedName, normalizedEmail)
            } }.fold(
                onSuccess = { invitation ->
                    val contactsResult = runCatching { trustedContactGateway.list() }
                    _uiState.update { state ->
                        val refreshed = contactsResult.fold(
                            onSuccess = state::withTrustedContactsSnapshot,
                            onFailure = state::withTrustedContactsFailure,
                        )
                        val stillPending = contactsResult.getOrNull()?.any {
                            it.id == invitation.id && it.kind == TrustedContactSubjectKind.INVITATION &&
                                it.status == TrustedContactStatus.PENDING
                        } ?: true
                        refreshed.copy(
                            trustedContactActionInProgress = false,
                            trustedContactsPage = if (stillPending) TrustedContactsPage.READY
                                else TrustedContactsPage.LIST,
                            invitationReadyId = if (stillPending) invitation.id else null,
                            invitationReadyName = if (stillPending) invitation.displayName else null,
                            invitationReadyEmail = if (stillPending) invitation.email else null,
                            invitationShareUrl = if (stillPending) invitation.shareUrl else null,
                        )
                    }
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
            runCatching { trustedContactRequests.withLock { trustedContactGateway.revoke(subjectId) } }.fold(
                onSuccess = { revoked ->
                    val contactsResult = runCatching { trustedContactGateway.list() }
                    _uiState.update { state ->
                        val refreshed = contactsResult.fold(
                            onSuccess = state::withTrustedContactsSnapshot,
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
    val degradation: DegradedConnectivityState? = null,
)

class JourneyViewModelFactory(
    private val repository: JourneyRepository,
    private val lifecycle: JourneyLifecycle,
    private val serviceController: JourneyServiceController,
    private val trustedContactGateway: TrustedContactGateway,
    private val degradedConnectivityCoordinator: DegradedConnectivityCoordinator,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        JourneyViewModel(repository, lifecycle, serviceController, trustedContactGateway,
            degradedConnectivityCoordinator) as T
}
