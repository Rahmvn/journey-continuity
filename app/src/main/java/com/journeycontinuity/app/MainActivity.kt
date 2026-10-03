package com.journeycontinuity.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.content.ContextCompat
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
import com.journeycontinuity.app.ui.LocationUiState
import com.journeycontinuity.app.ui.theme.JourneyContinuityTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
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

    private var notificationsVisible by mutableStateOf(true)
    private var locationUiState by mutableStateOf(LocationUiState())
    private var pendingStart: Pair<String, Long>? = null
    private var smsFallbackStatus by mutableStateOf(
        SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Checking SMS fallback capability."),
    )

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshSmsFallbackState()
    }
    private val accountViewModel by viewModels<TravellerAccountViewModel> {
        TravellerAccountViewModelFactory(app.travellerAccountGateway,
            app.travellerNoActiveSignOutGateway, app.travellerActiveJourneySignOutGateway)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        refreshPrerequisiteState()
        if (!isAdmitted()) return@registerForActivityResult
        val hasFine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val hasCoarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            hasFine -> continueAfterLocationPermission()
            hasCoarse -> {
                journeyViewModel.showMessage(
                    "Approximate location granted. Journey evidence will have reduced precision.",
                )
                continueAfterLocationPermission()
            }
            else -> {
                pendingStart = null
                journeyViewModel.showMessage(
                    "Journey monitoring requires location access. Tap Start Journey to retry.",
                )
            }
        }
    }

    private fun continueAfterLocationPermission() {
        if (pendingStart != null) continuePendingStart() else startServiceForActiveJourneyIfReady()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPrerequisiteState()
        if (!isAdmitted()) return@registerForActivityResult
        beginPendingJourney()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPrerequisiteState()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                rootViewModel.state.map { it.route }.distinctUntilChanged().collectLatest { route ->
                    if (route != TravellerRootRoute.ADMITTED_EXISTING &&
                        route != TravellerRootRoute.ADMITTED_NEW) return@collectLatest
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
        setContent {
            JourneyContinuityTheme {
                val accountState by accountViewModel.state.collectAsStateWithLifecycle()
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
                } else TravellerRootHost(rootViewModel) {
                    JourneyProductRoot(
                        viewModel = journeyViewModel,
                        locationUiState = locationUiState,
                        accountEmail = app.currentAccountEmail,
                        onStartRequested = ::startAfterPrerequisites,
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
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPrerequisiteState()
        refreshSmsFallbackState()
        if (!isAdmitted()) {
            resumeEstablishedLocalMonitoring()
            return
        }
        if (pendingStart != null && locationUiState.locationServicesEnabled) {
            continuePendingStart()
        } else {
            startServiceForActiveJourneyIfReady()
        }
    }

    private fun startAfterPrerequisites(destination: String, eta: Long) {
        if (signOutBlocksJourneyStart()) return
        if (!journeyViewModel.validateStart(destination, eta)) return
        pendingStart = destination to eta
        continuePendingStart()
    }

    private fun continuePendingStart() {
        if (signOutBlocksJourneyStart()) return
        if (pendingStart == null) return
        refreshPrerequisiteState()
        if (locationUiState.access == ForegroundLocationAccess.NONE) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }
        if (!locationUiState.locationServicesEnabled) {
            journeyViewModel.showMessage(
                "Turn on device Location Services before starting monitoring.",
            )
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        beginPendingJourney()
    }

    private fun beginPendingJourney() {
        if (signOutBlocksJourneyStart()) return
        val (destination, eta) = pendingStart ?: return
        pendingStart = null
        journeyViewModel.startJourney(destination, eta)
    }

    private fun retryMonitoringPrerequisites() {
        refreshPrerequisiteState()
        if (locationUiState.access == ForegroundLocationAccess.NONE) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
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
            runCatching { app.journeyServiceController.start() }
                .onFailure {
                    journeyViewModel.showMessage("Could not start location monitoring: ${it.message}")
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
            val hasActiveJourney = runCatching { app.journeyRepository.activeJourney.first() != null }
                .getOrDefault(false)
            if (hasActiveJourney && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
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
