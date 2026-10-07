package com.journeycontinuity.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.journeycontinuity.app.domain.JourneyStartRequest
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import com.journeycontinuity.app.telemetry.LocationPrerequisites

/** The native permission handoff shared by production and isolated device acceptance. */
class JourneyStartPermissions(
    private val activity: ComponentActivity,
    private val retainedViewModel: () -> JourneyViewModel,
    private val allowed: () -> Boolean,
    private val refresh: () -> Unit,
    private val resumeMonitoring: () -> Unit,
) {
    private val viewModel get() = retainedViewModel()
    private val state get() = viewModel.startPermissionState
    private val location = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ticket = state.consume(JourneyStartPermissionState.Permission.LOCATION)
            ?: return@registerForActivityResult
        refresh()
        if (ticket.request !== viewModel.pendingStartRequest) return@registerForActivityResult
        if (!allowed()) {
            viewModel.cancelPendingStart()
            return@registerForActivityResult
        }
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            if (!fine) viewModel.showMessage("Approximate location granted. Journey evidence will have reduced precision.")
            if (ticket.request != null) resume() else resumeMonitoring()
        } else {
            viewModel.cancelPendingStart()
            viewModel.showMessage("Journey monitoring requires location access. Tap Start Journey to retry.")
        }
    }
    private val notifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val ticket = state.consume(JourneyStartPermissionState.Permission.NOTIFICATIONS)
            ?: return@registerForActivityResult
        refresh()
        val request = ticket.request ?: return@registerForActivityResult
        if (request !== viewModel.pendingStartRequest) return@registerForActivityResult
        if (!allowed()) {
            viewModel.cancelPendingStart()
            return@registerForActivityResult
        }
        // Notifications remain optional under the existing monitoring policy.
        state.notificationDecided(request)
        resume()
    }

    fun begin(request: JourneyStartRequest) {
        if (request !== viewModel.pendingStartRequest) return
        if (!allowed() || !viewModel.validateStart(request) || !state.begin(request)) {
            viewModel.cancelPendingStart()
            return
        }
        resume()
    }

    fun resume() {
        val request = viewModel.pendingStartRequest ?: return
        if (!allowed()) { viewModel.cancelPendingStart(); return }
        if (state.inFlight) return
        refresh()
        if (LocationPrerequisites.access(activity) == ForegroundLocationAccess.NONE) {
            requestLocation(request)
            return
        }
        if (!LocationPrerequisites.locationServicesEnabled(activity)) {
            viewModel.cancelPendingStart()
            viewModel.showMessage("Turn on device Location Services before starting monitoring.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            state.needsNotificationDecision(request) &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            state.launch(JourneyStartPermissionState.Permission.NOTIFICATIONS, request) ?: return
            launchSafely(JourneyStartPermissionState.Permission.NOTIFICATIONS) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.startJourney(request)
    }

    fun retryMonitoringLocation() = requestLocation(null)

    private fun requestLocation(request: JourneyStartRequest?) {
        state.launch(JourneyStartPermissionState.Permission.LOCATION, request) ?: return
        launchSafely(JourneyStartPermissionState.Permission.LOCATION) {
            location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun launchSafely(permission: JourneyStartPermissionState.Permission, launch: () -> Unit) {
        try { launch() } catch (_: Exception) {
            val ticket = state.consume(permission)
            if (ticket?.request === viewModel.pendingStartRequest) viewModel.cancelPendingStart()
            viewModel.showMessage("Could not request monitoring permission. Tap Start Journey to retry.")
        }
    }
}
