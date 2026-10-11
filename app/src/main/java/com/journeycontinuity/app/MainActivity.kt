package com.journeycontinuity.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import com.journeycontinuity.app.telemetry.LocationPrerequisites
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.ui.JourneyProductRoot
import com.journeycontinuity.app.ui.JourneyViewModel
import com.journeycontinuity.app.ui.JourneyViewModelFactory
import com.journeycontinuity.app.ui.TravellerRootHost
import com.journeycontinuity.app.ui.TravellerRootRoute
import com.journeycontinuity.app.ui.TravellerRootViewModel
import com.journeycontinuity.app.ui.TravellerRootViewModelFactory
import com.journeycontinuity.app.ui.TravellerAccountViewModel
import com.journeycontinuity.app.ui.TravellerAccountViewModelFactory
import com.journeycontinuity.app.ui.AccountSignOutStatus
import com.journeycontinuity.app.ui.TravellerMonitoringStoppedResult
import com.journeycontinuity.app.ui.ProbableEndCompletedResultScreen
import com.journeycontinuity.app.ui.ProbableEndAutomaticResultScreen
import com.journeycontinuity.app.data.repository.ProbableEndResponseRequest
import com.journeycontinuity.app.data.repository.ProbableEndTravellerResponse
import com.journeycontinuity.app.data.repository.ProbableEndResponseOutcome
import com.journeycontinuity.app.ui.TravellerAuthorityViewModel
import com.journeycontinuity.app.ui.TravellerAuthorityViewModelFactory
import com.journeycontinuity.app.ui.UnresolvedJourneyResultScreen
import com.journeycontinuity.app.ui.LocationUiState
import com.journeycontinuity.app.ui.destination.DestinationPickerViewModel
import com.journeycontinuity.app.ui.destination.DestinationPickerViewModelFactory
import com.journeycontinuity.app.telemetry.DeviceContextReader
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.ui.theme.JourneyContinuityTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val app by lazy { application as JourneyContinuityApplication }
    private val rootViewModel by viewModels<TravellerRootViewModel> {
        TravellerRootViewModelFactory(app.travellerRootAuth, app.introCompletionStore,
            app.createAccountProgressStore, app.createAccountEntryStore, app.loginEntryStore)
    }
    private val journeyViewModel by viewModels<JourneyViewModel> {
        JourneyViewModelFactory(
            repository = app.journeyRepository,
            lifecycle = app.journeyLifecycle,
            serviceController = app.journeyServiceController,
            trustedContactGateway = app.trustedContactGateway,
            degradedConnectivityCoordinator = app.degradedConnectivityCoordinator,
        )
    }

    private val authorityViewModel by viewModels<TravellerAuthorityViewModel> {
        TravellerAuthorityViewModelFactory(app.travellerAuthorityActions) {
            !DeviceContextReader(app.applicationContext).usableInternet()
        }
    }

    private var notificationsVisible by mutableStateOf(true)
    private var probableEndForegroundEpisodeId by mutableStateOf<String?>(null)
    private var probableEndResponseInFlight by mutableStateOf(false)
    private var probableEndResponseError by mutableStateOf<String?>(null)
    private var probableEndResultAcknowledging by mutableStateOf(false)
    private var renderedProbableEndJourneyId: String? = null
    private var renderedProbableEndEpisodeId: String? = null
    private var renderedProbableEndWindowView: View? = null
    private var locationUiState by mutableStateOf(LocationUiState())
    private val pendingStart get() = journeyViewModel.pendingStartRequest
    private lateinit var startPermissions: com.journeycontinuity.app.ui.JourneyStartPermissions
    private var smsFallbackStatus by mutableStateOf(
        SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Checking SMS fallback capability."),
    )

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshSmsFallbackState()
    }
    private val destinationPickerViewModel by viewModels<DestinationPickerViewModel> {
        // The retained ViewModel captures only this application-context reader, not the Activity.
        val connectivityReader = DeviceContextReader(app.applicationContext)
        DestinationPickerViewModelFactory(app.adminCatalogue.snapshot(),
            app.destinationSearchGateway,
            { connectivityReader.connectivity() == ConnectivityState.NONE })
    }
    private val destinationMapViewModel by viewModels<com.journeycontinuity.app.ui.destination.DestinationMapViewModel> {
        com.journeycontinuity.app.ui.destination.DestinationMapViewModelFactory(app.adminCatalogue.snapshot(),
            app.destinationMapGateway, DeviceContextReader(app.applicationContext).usableInternet())
    }
    private val accountViewModel by viewModels<TravellerAccountViewModel> {
        TravellerAccountViewModelFactory(app.travellerAccountGateway,
            app.travellerNoActiveSignOutGateway, app.travellerActiveJourneySignOutGateway)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startPermissions = com.journeycontinuity.app.ui.JourneyStartPermissions(
            this, { journeyViewModel }, { !signOutBlocksJourneyStart() },
            ::refreshPrerequisiteState, ::startServiceForActiveJourneyIfReady,
        )
        enableEdgeToEdge()
        refreshPrerequisiteState()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                rootViewModel.state.map { it.route }.distinctUntilChanged().collectLatest { route ->
                    if (route == TravellerRootRoute.ACTIVE_REAUTHENTICATION ||
                        route == TravellerRootRoute.ACTIVE_AUTH_PENDING) {
                        accountViewModel.setReauthenticationNeeded(
                            route == TravellerRootRoute.ACTIVE_REAUTHENTICATION)
                        resumeEstablishedLocalMonitoring()
                        return@collectLatest
                    }
                    if (route != TravellerRootRoute.ADMITTED_EXISTING &&
                        route != TravellerRootRoute.ADMITTED_NEW) return@collectLatest
                    accountViewModel.setReauthenticationNeeded(false)
                    // A same-owner session may return after the startup worker could not
                    // authenticate; recheck remote terminal truth before relying on sync backlog.
                    runCatching {
                        app.syncScheduler.schedule(com.journeycontinuity.app.sync.SyncRequestUrgency.URGENT)
                    }
                    journeyViewModel.uiState.map { it.activeJourney?.id }
                    .distinctUntilChanged()
                    .collect { activeJourneyId ->
                        if (activeJourneyId != null) startServiceForActiveJourneyIfReady()
                        else accountViewModel.refreshMonitoringStoppedResult()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    combine(rootViewModel.state.map { it.route },
                        journeyViewModel.uiState.map { it.activeJourney?.id },
                        app.probableEndProvenCandidateId) { route, journeyId, candidateId ->
                        Triple(route, journeyId, candidateId)
                    }.distinctUntilChanged().collectLatest { (route, journeyId, candidateId) ->
                        probableEndForegroundEpisodeId = null
                        renderedProbableEndJourneyId = null
                        renderedProbableEndEpisodeId = null
                        renderedProbableEndWindowView = null
                        if (route in setOf(TravellerRootRoute.ADMITTED_EXISTING,
                                TravellerRootRoute.ADMITTED_NEW) && journeyId != null && candidateId != null &&
                            accountViewModel.state.value.monitoringStoppedJourney == null) {
                            probableEndForegroundEpisodeId = app.recheckProbableEndForForeground(journeyId, candidateId)
                        }
                    }
                } finally {
                    probableEndForegroundEpisodeId = null
                    renderedProbableEndJourneyId = null
                    renderedProbableEndEpisodeId = null
                    renderedProbableEndWindowView = null
                }
            }
        }
        setContent {
            JourneyContinuityTheme {
                val rootState by rootViewModel.state.collectAsStateWithLifecycle()
                val accountState by accountViewModel.state.collectAsStateWithLifecycle()
                val authorityState by authorityViewModel.state.collectAsStateWithLifecycle()
                val probableEndEpisode by app.probableEndPresentationEpisode.collectAsStateWithLifecycle(initialValue = null)
                val probableEndCompletedResult by app.probableEndCompletedResult.collectAsStateWithLifecycle(initialValue = null)
                val probableEndAutomaticResult = authorityState.automaticResult
                val stopped = accountState.monitoringStoppedJourney
                if (accountState.restoringMonitoringStoppedResult) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFFBFBF8)),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF1B3250))
                    }
                } else if (stopped != null) {
                    TravellerMonitoringStoppedResult(
                        journey = stopped,
                        signedOut = accountState.monitoringStoppedSignedOut,
                        error = accountState.monitoringStoppedError,
                        inProgress = accountState.signOutStatus == AccountSignOutStatus.STOPPING_ACTIVE,
                        onRetry = accountViewModel::retryTerminalSignOut,
                        onDone = { accountViewModel.doneWithMonitoringStopped(
                            rootViewModel::assessStartup) },
                    )
                } else if (authorityState.result != null && rootState.route in setOf(
                        TravellerRootRoute.ADMITTED_EXISTING, TravellerRootRoute.ADMITTED_NEW)) {
                    val result = requireNotNull(authorityState.result)
                    UnresolvedJourneyResultScreen(result, authorityState.busyJourneyId != null,
                        authorityState.error) {
                        authorityViewModel.acknowledge(result)
                        journeyViewModel.backToHome()
                    }
                } else if (probableEndAutomaticResult != null && rootState.route in setOf(
                        TravellerRootRoute.ADMITTED_EXISTING, TravellerRootRoute.ADMITTED_NEW)) {
                    val result = requireNotNull(probableEndAutomaticResult)
                    ProbableEndAutomaticResultScreen(result) {
                        if (!probableEndResultAcknowledging) {
                            probableEndResultAcknowledging = true
                            lifecycleScope.launch {
                                try { app.acknowledgeProbableEndAutomaticResult(result.episodeId) }
                                finally { probableEndResultAcknowledging = false }
                            }
                        }
                    }
                } else if (probableEndCompletedResult != null && rootState.route in setOf(
                        TravellerRootRoute.ADMITTED_EXISTING, TravellerRootRoute.ADMITTED_NEW)) {
                    val result = requireNotNull(probableEndCompletedResult)
                    ProbableEndCompletedResultScreen(result) {
                        if (!probableEndResultAcknowledging) {
                            probableEndResultAcknowledging = true
                            lifecycleScope.launch {
                                try { app.acknowledgeProbableEndCompletion(result.episodeId) }
                                finally { probableEndResultAcknowledging = false }
                            }
                        }
                    }
                } else TravellerRootHost(rootViewModel) {
                    JourneyProductRoot(
                        viewModel = journeyViewModel,
                        authorityState = authorityState,
                        onAuthorityCheck = authorityViewModel::check,
                        onAuthorityComplete = authorityViewModel::complete,
                        onAuthorityStop = authorityViewModel::stop,
                        onAuthorityResume = authorityViewModel::refreshConnectivity,
                        onOpenNetworkSettings = {
                            authorityViewModel.markNetworkSettingsOpened()
                            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        },
                        locationUiState = locationUiState,
                        accountEmail = when (rootState.route) {
                            TravellerRootRoute.ACTIVE_REAUTHENTICATION -> "Account connection needs attention"
                            TravellerRootRoute.ACTIVE_AUTH_PENDING -> "Checking account connection"
                            else -> app.currentAccountEmail
                        },
                        onStructuredStartRequested = startPermissions::begin,
                        onRetryMonitoring = ::retryMonitoringPrerequisites,
                        onOpenLocationSettings = ::openLocationSettings,
                        smsFallbackStatus = smsFallbackStatus,
                        onRequestSmsPermissions = ::requestSmsFallbackPermissions,
                        onRequestPhoneStatePermission = ::requestPhoneStatePermission,
                        onSelectSmsSubscription = ::selectSmsFallbackSubscription,
                        onRefreshSmsFallback = ::refreshSmsFallbackState,
                        accountViewModel = accountViewModel,
                        onSignOutRequested = {
                            accountViewModel.requestSignOut(accountSignOutReady(),
                                journeyViewModel.uiState.value.activeJourney?.id)
                        },
                        onConfirmSignOutRequested = {
                            accountViewModel.confirmNoActiveSignOut(accountSignOutReady(),
                                rootViewModel::assessStartup)
                        },
                        onCancelSignOutRequested = accountViewModel::cancelNoActiveSignOut,
                        onConfirmActiveSignOutRequested = {
                            accountViewModel.confirmActiveSignOut(accountSignOutReady())
                        },
                        onCancelActiveSignOutRequested = accountViewModel::cancelActiveSignOut,
                        onReauthenticateRequested = rootViewModel::reauthenticateAuthorityStatus,
                        destinationPickerViewModel = destinationPickerViewModel,
                        onMapRefinementRequested = destinationMapViewModel::open,
                        destinationMapViewModel = destinationMapViewModel,
                        probableEndPresentation = probableEndEpisode,
                        probableEndForegroundEpisodeId = probableEndForegroundEpisodeId,
                        onProbableEndDismissed = {
                            probableEndForegroundEpisodeId = null
                            renderedProbableEndJourneyId = null
                            renderedProbableEndEpisodeId = null
                            renderedProbableEndWindowView = null
                        },
                        onProbableEndReopen = { journeyId, candidateId ->
                            lifecycleScope.launch {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && isAdmitted())
                                    probableEndForegroundEpisodeId =
                                        app.recheckProbableEndForForeground(journeyId, candidateId)
                            }
                        },
                        onProbableEndRendered = { journeyId, episodeId, sheetView ->
                            if (probableEndForegroundEpisodeId == episodeId) {
                                renderedProbableEndJourneyId = journeyId
                                renderedProbableEndEpisodeId = episodeId
                                renderedProbableEndWindowView = sheetView
                                recordVisibleProbableEndOpportunity()
                            }
                        },
                        onProbableEndResponse = ::respondToProbableEnd,
                        probableEndResponseInFlight = probableEndResponseInFlight,
                        probableEndResponseError = probableEndResponseError,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.wakeForAuthorityForeground()
        refreshPrerequisiteState()
        refreshSmsFallbackState()
        if (!isAdmitted()) {
            resumeEstablishedLocalMonitoring()
            return
        }
        if (pendingStart != null) {
            startPermissions.resume()
        } else {
            startServiceForActiveJourneyIfReady()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) recordVisibleProbableEndOpportunity()
    }

    private fun recordVisibleProbableEndOpportunity() {
        val journeyId = renderedProbableEndJourneyId ?: return
        val episodeId = renderedProbableEndEpisodeId ?: return
        val sheetView = renderedProbableEndWindowView ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !sheetView.isAttachedToWindow || !sheetView.isShown || !sheetView.hasWindowFocus() ||
            probableEndForegroundEpisodeId != episodeId ||
            journeyViewModel.uiState.value.activeJourney?.id != journeyId) return
        lifecycleScope.launch {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                sheetView.isAttachedToWindow && sheetView.isShown && sheetView.hasWindowFocus() &&
                probableEndForegroundEpisodeId == episodeId && renderedProbableEndEpisodeId == episodeId)
                app.recordProbableEndForegroundOpportunity(journeyId, episodeId)
        }
    }

    private fun respondToProbableEnd(request: ProbableEndResponseRequest,
        response: ProbableEndTravellerResponse) {
        if (probableEndResponseInFlight || !isAdmitted() ||
            probableEndForegroundEpisodeId != request.presentationEpisodeId ||
            journeyViewModel.uiState.value.activeJourney?.id != request.journeyId) return
        probableEndResponseInFlight = true
        probableEndResponseError = null
        lifecycleScope.launch {
            try {
                when (app.respondToProbableEnd(request, response)) {
                    ProbableEndResponseOutcome.ACCEPTED, ProbableEndResponseOutcome.ALREADY_ACCEPTED -> {
                        probableEndForegroundEpisodeId = null
                        renderedProbableEndWindowView = null
                        if (response == ProbableEndTravellerResponse.KEEP_MONITORING)
                            journeyViewModel.openActive()
                    }
                    ProbableEndResponseOutcome.UNAVAILABLE ->
                        probableEndResponseError = "Could not save this response. Please try again."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                probableEndResponseError = "Could not save this response. Please try again."
            } finally { probableEndResponseInFlight = false }
        }
    }

    private fun retryMonitoringPrerequisites() {
        refreshPrerequisiteState()
        if (locationUiState.access == ForegroundLocationAccess.NONE) {
            startPermissions.retryMonitoringLocation()
        } else if (!locationUiState.locationServicesEnabled) {
            openLocationSettings()
        } else {
            startServiceForActiveJourneyIfReady()
        }
    }

    private fun startServiceForActiveJourneyIfReady() {
        if (!isAdmitted()) return
        if (accountViewModel.state.value.signOutStatus == AccountSignOutStatus.STOPPING_ACTIVE ||
            accountViewModel.state.value.monitoringStoppedJourney != null) return
        refreshPrerequisiteState()
        if (
            journeyViewModel.uiState.value.activeJourney != null &&
            locationUiState.access != ForegroundLocationAccess.NONE &&
            locationUiState.locationServicesEnabled &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            val journeyId = requireNotNull(journeyViewModel.uiState.value.activeJourney?.id)
            lifecycleScope.launch {
                if (!app.mayStartContinuousMonitoring(journeyId) ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
                    journeyViewModel.uiState.value.activeJourney?.id != journeyId ||
                    com.journeycontinuity.app.service.JourneyForegroundService.activeRuntimeJourneyId.value == journeyId)
                    return@launch
                runCatching { app.journeyServiceController.start() }
                    .onFailure { journeyViewModel.showMessage("Could not start location monitoring. Retry monitoring.") }
            }
        }
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun refreshPrerequisiteState() {
        notificationsVisible = NotificationManagerCompat.from(this).areNotificationsEnabled()
        locationUiState = LocationUiState(
            access = LocationPrerequisites.access(this),
            locationServicesEnabled = LocationPrerequisites.locationServicesEnabled(this),
        )
    }

    /** Local monitoring can resume during a cloud-auth outage without constructing JourneyViewModel. */
    private fun resumeEstablishedLocalMonitoring() {
        if (locationUiState.access == ForegroundLocationAccess.NONE ||
            !locationUiState.locationServicesEnabled) return
        lifecycleScope.launch {
            val journeyId = runCatching { app.journeyRepository.activeJourney.first()?.id }.getOrNull()
            if (journeyId != null && app.mayStartContinuousMonitoring(journeyId) &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                runCatching { app.journeyServiceController.start() }
            }
        }
    }

    private fun refreshSmsFallbackState() {
        smsFallbackStatus = app.smsFallbackConfiguration.status()
    }

    private fun requestSmsFallbackPermissions() {
        smsPermissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
    }

    private fun requestPhoneStatePermission() {
        smsPermissionLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
    }

    private fun selectSmsFallbackSubscription(subscriptionId: Int) {
        if (!isAdmitted()) return
        if (!app.smsFallbackConfiguration.selectSubscription(subscriptionId)) {
            journeyViewModel.showMessage("That SIM is no longer active. Refresh and select an active SIM.")
        }
        refreshSmsFallbackState()
    }

    private fun isAdmitted(): Boolean = rootViewModel.state.value.route ==
        TravellerRootRoute.ADMITTED_EXISTING || rootViewModel.state.value.route ==
        TravellerRootRoute.ADMITTED_NEW

    private fun accountSignOutReady(): Boolean = isAdmitted() && pendingStart == null &&
        !journeyViewModel.uiState.value.isRestoring &&
        !journeyViewModel.uiState.value.isActionInProgress

    private fun signOutBlocksJourneyStart(): Boolean = !isAdmitted() ||
        accountViewModel.state.value.signOutStatus in setOf(
            AccountSignOutStatus.SIGNING_OUT, AccountSignOutStatus.CONFIRMING_ACTIVE,
            AccountSignOutStatus.STOPPING_ACTIVE, AccountSignOutStatus.MONITORING_STOPPED_RESULT,
        ) || accountViewModel.state.value.monitoringStoppedJourney != null
}
