package com.journeycontinuity.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.journeycontinuity.app.ui.JourneyScreen
import com.journeycontinuity.app.ui.JourneyViewModel
import com.journeycontinuity.app.ui.JourneyViewModelFactory
import com.journeycontinuity.app.ui.theme.JourneyContinuityTheme

class MainActivity : ComponentActivity() {
    private val app by lazy { application as JourneyContinuityApplication }
    private val journeyViewModel by viewModels<JourneyViewModel> {
        JourneyViewModelFactory(
            repository = app.journeyRepository,
            lifecycle = app.journeyLifecycle,
            serviceController = app.journeyServiceController,
        )
    }

    private var notificationsVisible by mutableStateOf(true)
    private var pendingStart: Pair<String, Long>? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsVisible = NotificationManagerCompat.from(this).areNotificationsEnabled()
        pendingStart?.let { (destination, eta) ->
            pendingStart = null
            journeyViewModel.startJourney(destination, eta)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshNotificationVisibility()
        setContent {
            JourneyContinuityTheme {
                JourneyScreen(
                    viewModel = journeyViewModel,
                    notificationsVisible = notificationsVisible,
                    onStartRequested = ::startAfterNotificationPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationVisibility()
    }

    private fun startAfterNotificationPermission(destination: String, eta: Long) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = destination to eta
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            journeyViewModel.startJourney(destination, eta)
        }
    }

    private fun refreshNotificationVisibility() {
        notificationsVisible = NotificationManagerCompat.from(this).areNotificationsEnabled()
    }
}
