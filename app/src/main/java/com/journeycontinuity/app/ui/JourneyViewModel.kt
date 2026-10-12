package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.domain.CompleteJourneyResult
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.JourneyInputValidation
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.StartJourneyResult
import com.journeycontinuity.app.domain.JourneyStartRequest
import com.journeycontinuity.app.ui.destination.DestinationDraft
import com.journeycontinuity.app.ui.destination.toJourneyStartRequest
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.degraded.DegradedConnectivityCoordinator
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.service.JourneyMonitoringController
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
import kotlinx.coroutines.flow.first
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

enum class JourneyProductRoute { HOME, START, CHECKPOINT, ACTIVE, END, END_RESULT, CONTACTS, RESILIENCE }
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
            "Trusted contacts are unavailable right now. Try again here.",
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
    val endFlowJourneyId: String? = null,
    val endResult: Journey? = null,
    val endStopPending: Boolean = false,
    val endError: String? = null,
    val utilityReturnRoute: JourneyProductRoute = JourneyProductRoute.HOME,
    val resiliencePage: ResiliencePage = ResiliencePage.STATUS,
    val draftDestination: String = "",
    val draftExpectedArrivalAt: Long? = null,
    val isActionInProgress: Boolean = false,
    val trustedContacts: List<TrustedContactSummary> = emptyList(),
    val trustedContactsAvailability: TrustedContactsAvailability = TrustedContactsAvailability.LOADING,
    val trustedContactsUnavailableMessage: String? = null,
    val trustedContactActionError: String? = null,
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
    private val repository: JourneyRepository,
    private val lifecycle: JourneyLifecycle,
    private val serviceController: JourneyMonitoringController,
    private val trustedContactGateway: TrustedContactGateway,
    private val degradedConnectivityCoordinator: DegradedConnectivityCoordinator,
    currentConnectivity: StateFlow<CurrentJourneyConnectivity?> =
        JourneyForegroundService.currentConnectivity,
    private val requestOrdinaryStop: suspend (String) -> Boolean = { false },
) : ViewModel() {
    private val _uiState = MutableStateFlow(JourneyUiState())
    val uiState: StateFlow<JourneyUiState> = _uiState.asStateFlow()
    private val trustedContactRequests = Mutex()
    private var trustedRefreshInFlight = false
    private var checkpointRequest: JourneyStartRequest? = null
    var pendingStartRequest: JourneyStartRequest? = null
        private set
    private var admissionInProgress = false
    val startPermissionState = JourneyStartPermissionState()

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
                        it.copy(isRestoring = false, message = "Could not read saved journey.")
                    }
                }
                .collect { activeData ->
                    // A concurrently admitted Journey consumes eligibility for a pending Start.
                    if (activeData.journey != null && !admissionInProgress &&
                        (pendingStartRequest != null || checkpointRequest != null)) cancelPendingStart()
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

    fun openEndJourney(expectedJourneyId: String) = _uiState.update {
        if (it.activeJourney?.id == expectedJourneyId && !it.isActionInProgress)
            it.copy(productRoute = JourneyProductRoute.END, endFlowJourneyId = expectedJourneyId,
                endResult = null, endStopPending = false, endError = null) else it
    }

    fun returnToActiveFromEnd() = _uiState.update {
        if (it.productRoute == JourneyProductRoute.END && !it.isActionInProgress && !it.endStopPending &&
            it.activeJourney?.id == it.endFlowJourneyId)
            it.copy(productRoute = JourneyProductRoute.ACTIVE, endFlowJourneyId = null, endError = null) else it
    }

    fun doneWithEndResult() = _uiState.update {
        if (it.productRoute == JourneyProductRoute.END_RESULT && it.endResult?.id == it.endFlowJourneyId)
            it.copy(productRoute = JourneyProductRoute.HOME, endFlowJourneyId = null,
                endResult = null, endStopPending = false, endError = null) else it
    }

    fun openContacts() {
        cancelPendingStart()
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
                 invitationShareUrl = if (pendingResult) it.invitationShareUrl else null,
                 trustedContactActionError = null)
        }
        refreshTrustedContacts()
    }

    fun openAddTrustedContact() = _uiState.update {
        if (it.productRoute == JourneyProductRoute.CONTACTS)
            it.copy(trustedContactsPage = TrustedContactsPage.ADD,
                trustedContactActionError = null) else it
    }

    fun doneWithInvitation() {
        _uiState.update {
            it.copy(trustedContactsPage = TrustedContactsPage.LIST,
                invitationReadyId = null,
                invitationReadyName = null, invitationReadyEmail = null,
                 invitationShareUrl = null, trustedContactActionError = null)
        }
        refreshTrustedContacts()
    }

    fun openResilience() {
        cancelPendingStart()
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
             invitationShareUrl = null, trustedContactActionError = null)
    }

    fun backToHome() {
        cancelPendingStart()
        _uiState.update { it.copy(productRoute = JourneyProductRoute.HOME) }
    }

    fun backToStart() {
        cancelPendingStart()
        _uiState.update { it.copy(productRoute = JourneyProductRoute.START) }
    }

    fun cancelPendingStart() {
        if (admissionInProgress) return
        val waitingForPrerequisites = pendingStartRequest != null
        checkpointRequest = null
        pendingStartRequest = null
        if (waitingForPrerequisites) _uiState.update { it.copy(isActionInProgress = false) }
    }

    /** Freeze once, before checkpoint and permissions. UI readiness is never admission authority. */
    fun requestStructuredStart(draft: DestinationDraft?, smsFallbackReady: Boolean,
        begin: (JourneyStartRequest) -> Unit) {
        val state = _uiState.value
        if (state.isRestoring || state.isActionInProgress || state.activeJourney != null ||
            checkpointRequest != null) return
        val arrival = state.draftExpectedArrivalAt ?: return
        val request = runCatching { requireNotNull(draft).toJourneyStartRequest(arrival) }.getOrNull()
        if (request == null) {
            setMessage("Choose a valid destination.")
            return
        }
        if (!validateStart(request)) return
        val accepted = state.trustedContacts.any { it.status == TrustedContactStatus.ACCEPTED }
        if (needsResilienceCheckpoint(accepted, smsFallbackReady)) {
            checkpointRequest = request
            _uiState.update { it.copy(productRoute = JourneyProductRoute.CHECKPOINT) }
        } else continueStructuredStart(request, begin)
    }

    fun startStructuredAnyway(begin: (JourneyStartRequest) -> Unit) {
        if (_uiState.value.productRoute != JourneyProductRoute.CHECKPOINT ||
            _uiState.value.isActionInProgress || _uiState.value.activeJourney != null) return
        val request = checkpointRequest ?: return
        checkpointRequest = null
        continueStructuredStart(request, begin)
    }

    private fun continueStructuredStart(request: JourneyStartRequest, begin: (JourneyStartRequest) -> Unit) {
        if (!validateStart(request)) {
            backToStart()
            return
        }
        pendingStartRequest = request
        _uiState.update { it.copy(productRoute = JourneyProductRoute.START,
            isActionInProgress = true, message = null) }
        begin(request)
    }

    fun validateStart(request: JourneyStartRequest): Boolean = presentValidation(lifecycle.validate(request))

    /** Consumes exactly the request sent to prerequisites; late/replayed callbacks are inert. */
    fun startJourney(request: JourneyStartRequest) {
        if (pendingStartRequest !== request || admissionInProgress) return
        if (_uiState.value.activeJourney != null) {
            cancelPendingStart()
            return
        }
        pendingStartRequest = null
        admissionInProgress = true
        viewModelScope.launch {
            try {
                when (val result = lifecycle.start(request)) {
                    is StartJourneyResult.Started -> _uiState.update { it.copy(
                        activeJourney = result.journey,
                        productRoute = JourneyProductRoute.HOME,
                        draftDestination = "", draftExpectedArrivalAt = null,
                    ) }
                    StartJourneyResult.BlankDestination, StartJourneyResult.InvalidStructuredDestination ->
                        setMessage("Choose a valid destination.")
                    StartJourneyResult.ExpectedArrivalNotFuture -> setMessage("Expected arrival must be in the future.")
                    StartJourneyResult.ExpectedArrivalOutOfRange -> setMessage("Expected arrival must be within 72 hours of starting this Journey.")
                    StartJourneyResult.ActiveJourneyAlreadyExists -> setMessage("A journey is already active.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                setMessage("Could not start journey. Try again.")
            } finally {
                admissionInProgress = false
                _uiState.update { it.copy(isActionInProgress = false,
                    productRoute = if (it.activeJourney == null && it.productRoute == JourneyProductRoute.CHECKPOINT)
                        JourneyProductRoute.START else it.productRoute) }
            }
        }
    }

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
        presentValidation(lifecycle.validate(destination, expectedArrivalAt))

    private fun presentValidation(validation: JourneyInputValidation): Boolean =
        when (validation) {
            JourneyInputValidation.Valid -> true
            JourneyInputValidation.BlankDestination -> {
                setMessage("Enter a destination.")
                false
            }
            JourneyInputValidation.ExpectedArrivalNotFuture -> {
                setMessage("Expected arrival must be in the future.")
                false
            }
            JourneyInputValidation.InvalidStructuredDestination -> {
                setMessage("Choose a valid destination.")
                false
            }
            JourneyInputValidation.ExpectedArrivalOutOfRange -> {
                setMessage("Expected arrival must be within 72 hours of starting this Journey.")
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
                        StartJourneyResult.ExpectedArrivalOutOfRange -> setMessage("Expected arrival must be within 72 hours of starting this Journey.")
                        StartJourneyResult.ActiveJourneyAlreadyExists -> setMessage("A journey is already active.")
                        StartJourneyResult.InvalidStructuredDestination -> setMessage("Choose a valid destination.")
                    }
                },
                onFailure = { setMessage("Could not start journey. Try again.") },
            )
            _uiState.update { it.copy(isActionInProgress = false) }
        }
    }

    fun endJourney(expectedJourneyId: String) {
        if (_uiState.value.isActionInProgress ||
            _uiState.value.activeJourney?.id != expectedJourneyId) return
        _uiState.update { it.copy(isActionInProgress = true, message = null, endError = null) }
        viewModelScope.launch {
            runCatching { lifecycle.completeExact(expectedJourneyId) }.fold(
                onSuccess = { result ->
                    when (result) {
                        is CompleteJourneyResult.Completed -> {
                            _uiState.update { current ->
                                if (current.activeJourney?.id == expectedJourneyId ||
                                    current.endFlowJourneyId == expectedJourneyId)
                                    current.copy(productRoute = JourneyProductRoute.END_RESULT,
                                        endFlowJourneyId = expectedJourneyId, endResult = result.journey)
                                else current
                            }
                        }
                        CompleteJourneyResult.NoActiveJourney -> {
                            _uiState.update { it.copy(endError =
                                "Journey status changed. Check the saved Journey status.") }
                        }
                    }
                },
                onFailure = { _uiState.update { state -> state.copy(endError =
                    "Could not complete this Journey. Please try again.") } },
            )
            _uiState.update { it.copy(isActionInProgress = false) }
        }
    }

    fun stopJourney(expectedJourneyId: String) {
        val current = _uiState.value
        if (current.productRoute != JourneyProductRoute.END || current.isActionInProgress ||
            current.endStopPending || current.activeJourney?.id != expectedJourneyId ||
            current.endFlowJourneyId != expectedJourneyId) return
        _uiState.update { it.copy(isActionInProgress = true, endError = null) }
        viewModelScope.launch {
            val accepted = try { requestOrdinaryStop(expectedJourneyId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (!accepted) {
                _uiState.update { it.copy(isActionInProgress = false,
                    endError = "Stop could not be saved. Check your account and try again.") }
                return@launch
            }
            _uiState.update { it.copy(isActionInProgress = false, endStopPending = true) }
            // Only an exact persisted authoritative CANCELLED state may display the Stop result.
            val terminal = combine(repository.observeJourney(expectedJourneyId),
                repository.observeAcknowledgedStop(expectedJourneyId)) { journey, acknowledged ->
                if (journey?.status == JourneyStatus.CANCELLED) acknowledged
                else journey?.takeIf { it.status != JourneyStatus.ACTIVE }
            }.first { it != null } ?: return@launch
            _uiState.update { state ->
                if (state.endFlowJourneyId != expectedJourneyId || !state.endStopPending ||
                    state.activeJourney?.id?.let { it != expectedJourneyId } == true) state
                else if (terminal.status == JourneyStatus.CANCELLED && terminal.endedAt != null)
                    state.copy(productRoute = JourneyProductRoute.END_RESULT,
                        endResult = terminal, endStopPending = false, endError = null)
                else state.copy(productRoute = JourneyProductRoute.HOME, endFlowJourneyId = null,
                    endStopPending = false, endError = null,
                    message = "Journey status changed. Check the saved Journey status.")
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun refreshTrustedContacts() {
        if (_uiState.value.trustedContactActionInProgress || trustedRefreshInFlight) return
        trustedRefreshInFlight = true
        viewModelScope.launch {
            try {
                runCatching { trustedContactRequests.withLock { trustedContactGateway.list() } }.fold(
                    onSuccess = { contacts ->
                        _uiState.update { it.withTrustedContactsSnapshot(contacts) }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        _uiState.update { it.withTrustedContactsFailure(error) }
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
            _uiState.update { it.copy(trustedContactActionError =
                "Invitation sharing is unavailable right now.") }
            return
        }
        _uiState.update { it.copy(trustedContactActionInProgress = true, invitationShareUrl = null,
            trustedContactActionError = null) }
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
                            trustedContactActionError = null,
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
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(trustedContactActionInProgress = false,
                        trustedContactActionError = "Could not create the invitation. Try again.") }
                },
            )
        }
    }

    fun revokeTrustedContact(subjectId: String) {
        if (_uiState.value.trustedContactActionInProgress) return
        _uiState.update { it.copy(trustedContactActionInProgress = true, invitationShareUrl = null,
            trustedContactActionError = null) }
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
                            trustedContactActionError = null,
                        )
                    }
                    setMessage(if (revoked) "Trusted contact access revoked." else "This contact was already inactive.")
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(trustedContactActionInProgress = false,
                        trustedContactActionError = "Could not remove the trusted contact. Try again.") }
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
    private val serviceController: JourneyMonitoringController,
    private val trustedContactGateway: TrustedContactGateway,
    private val degradedConnectivityCoordinator: DegradedConnectivityCoordinator,
    private val requestOrdinaryStop: suspend (String) -> Boolean = { false },
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        JourneyViewModel(repository, lifecycle, serviceController, trustedContactGateway,
            degradedConnectivityCoordinator, requestOrdinaryStop = requestOrdinaryStop) as T
}
