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
import com.journeycontinuity.app.ui.LocationUiState
import com.journeycontinuity.app.ui.theme.JourneyContinuityTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
                    }
                }
            }
        }
        setContent {
            JourneyContinuityTheme {
                TravellerRootHost(rootViewModel) {
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
        if (!journeyViewModel.validateStart(destination, eta)) return
        pendingStart = destination to eta
        continuePendingStart()
    }

    private fun continuePendingStart() {
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
}
