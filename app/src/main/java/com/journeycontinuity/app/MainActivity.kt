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
import com.journeycontinuity.app.ui.JourneyScreen
import com.journeycontinuity.app.ui.JourneyViewModel
import com.journeycontinuity.app.ui.JourneyViewModelFactory
import com.journeycontinuity.app.ui.LocationUiState
import com.journeycontinuity.app.ui.theme.JourneyContinuityTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app by lazy { application as JourneyContinuityApplication }
    private val journeyViewModel by viewModels<JourneyViewModel> {
        JourneyViewModelFactory(
            repository = app.journeyRepository,
            lifecycle = app.journeyLifecycle,
            serviceController = app.journeyServiceController,
            trustedContactGateway = app.trustedContactGateway,
        )
    }

    private var notificationsVisible by mutableStateOf(true)
    private var locationUiState by mutableStateOf(LocationUiState())
    private var pendingStart: Pair<String, Long>? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        refreshPrerequisiteState()
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
        beginPendingJourney()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPrerequisiteState()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                journeyViewModel.uiState
                    .map { it.activeJourney?.id }
                    .distinctUntilChanged()
                    .collect { activeJourneyId ->
                        if (activeJourneyId != null) startServiceForActiveJourneyIfReady()
                    }
            }
        }
        setContent {
            JourneyContinuityTheme {
                JourneyScreen(
                    viewModel = journeyViewModel,
                    notificationsVisible = notificationsVisible,
                    locationUiState = locationUiState,
                    onStartRequested = ::startAfterPrerequisites,
                    onRetryMonitoring = ::retryMonitoringPrerequisites,
                    onOpenLocationSettings = ::openLocationSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPrerequisiteState()
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
}
