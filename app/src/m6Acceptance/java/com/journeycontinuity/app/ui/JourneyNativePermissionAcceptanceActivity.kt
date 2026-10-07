package com.journeycontinuity.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.JourneyContinuityApplication
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Acceptance build only: real native permission handoff, Room and service, no hosted auth/data. */
class JourneyNativePermissionAcceptanceActivity : ComponentActivity() {
    private val app get() = application as JourneyContinuityApplication
    val journeyViewModel by viewModels<JourneyViewModel> {
        JourneyViewModelFactory(app.journeyRepository, app.journeyLifecycle, app.journeyServiceController,
            app.trustedContactGateway, app.degradedConnectivityCoordinator)
    }
    lateinit var startPermissions: JourneyStartPermissions
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        check(packageName == "com.journeycontinuity.app.structuredadmissionaudit" &&
            BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty())
        startPermissions = JourneyStartPermissions(this, { journeyViewModel }, { true }, {}, ::monitor)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                journeyViewModel.uiState.map { it.activeJourney?.id }.distinctUntilChanged().collect {
                    if (it != null) monitor()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startPermissions.resume()
    }

    private fun monitor() {
        if (journeyViewModel.uiState.value.activeJourney != null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) app.journeyServiceController.start()
    }
}
