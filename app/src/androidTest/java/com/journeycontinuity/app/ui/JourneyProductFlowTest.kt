package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityCoordinator
import com.journeycontinuity.app.degraded.DegradedConnectivityLabConfiguration
import com.journeycontinuity.app.degraded.DegradedConnectivityPolicy
import com.journeycontinuity.app.degraded.DegradedConnectivityReduction
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.degraded.DegradedConnectivityStateStore
import com.journeycontinuity.app.degraded.LatestTelemetryReader
import com.journeycontinuity.app.degraded.RecoveryBacklogSnapshot
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.domain.TelemetrySummary
import com.journeycontinuity.app.service.JourneyServiceController
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import com.journeycontinuity.app.trusted.CreatedTrustedContactInvitation
import com.journeycontinuity.app.trusted.TrustedContactGateway
import com.journeycontinuity.app.trusted.TrustedContactSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/** Exercises the real product root and JourneyLifecycle against isolated in-memory state. */
class JourneyProductFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null

    @Test fun homeFirstStartCheckpointOpenAndReturn() {
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val degradationStore = FakeDegradationStore()
        val currentConnectivity = MutableStateFlow<CurrentJourneyConnectivity?>(null)
        val coordinator = DegradedConnectivityCoordinator(
            store = degradationStore,
            latestTelemetryReader = LatestTelemetryReader { null },
            policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig()),
        )
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember {
                JourneyViewModel(repository, JourneyLifecycle(repository),
                    JourneyServiceController(context), EmptyContacts, coordinator,
                    currentConnectivity)
            }
            JourneyProductRoot(viewModel, LocationUiState(), unavailableSms, "test@example.com",
                onStartRequested = viewModel::startJourney,
                onRetryMonitoring = {}, onOpenLocationSettings = {},
                onRequestSmsPermissions = {}, onSelectSmsSubscription = {})
        }
        compose.waitUntil("Home did not restore", 10_000) { !viewModel.uiState.value.isRestoring }
        compose.onNodeWithText("Alabarin").assertIsDisplayed()
        compose.onNodeWithText("Start Journey").performClick()
        compose.runOnIdle {
            viewModel.updateDestination("Ilorin")
            viewModel.updateExpectedArrival(System.currentTimeMillis() + 7_200_000)
        }
        compose.onNodeWithTag("journey_primary_action").performClick()
        compose.waitUntil("Checkpoint did not open", 10_000) {
            viewModel.uiState.value.productRoute == JourneyProductRoute.CHECKPOINT
        }
        compose.onNodeWithText("Start anyway").performClick()
        compose.waitUntil("Journey did not become active", 10_000) {
            viewModel.uiState.value.activeJourney != null &&
                viewModel.uiState.value.productRoute == JourneyProductRoute.HOME
        }
        compose.runOnIdle { assertNotNull(repository.active.value) }
        compose.onNodeWithText("Open Journey").performClick()
        compose.onNodeWithText("Monitoring needs attention").assertIsDisplayed()
        degradationStore.state.value = DegradedConnectivityState(
            journeyId = repository.active.value!!.id, journeyActive = true,
            connectivityPhase = ConnectivityPhase.RECOVERING,
            fallbackBindingProvisioned = true, transportAvailable = true,
        )
        compose.onNodeWithText("Checking connection").assertIsDisplayed()
        compose.onNodeWithText("Connection restored").assertDoesNotExist()

        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, true)
        compose.onNodeWithText("Connection restored").assertIsDisplayed()
        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, false)
        compose.onNodeWithText("Limited connectivity").assertIsDisplayed()
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.DEGRADED)
        compose.onNodeWithText("Limited connectivity").assertIsDisplayed()
        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, true)
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.RECOVERING)
        compose.onNodeWithText("Connection restored").assertIsDisplayed()
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.HEALTHY)
        compose.onNodeWithText("Connection restored").assertDoesNotExist()
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.RECOVERING)
        compose.onNodeWithContentDescription("Return to Home").performClick()
        compose.onNodeWithText("Open Journey").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(JourneyStatus.ACTIVE, repository.active.value?.status)
            assertEquals(0, repository.completions)
        }

        // A new ViewModel models a new process: persisted active state still opens Home first.
        currentConnectivity.value = null
        compose.runOnUiThread {
            host!!.setContent {
                val restarted = remember {
                    JourneyViewModel(repository, JourneyLifecycle(repository),
                        JourneyServiceController(context), EmptyContacts, coordinator,
                        currentConnectivity)
                }
                JourneyProductRoot(restarted, LocationUiState(), unavailableSms,
                    "test@example.com", restarted::startJourney, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Open Journey").assertIsDisplayed()
        compose.onNodeWithText("Open Journey").performClick()
        compose.onNodeWithText("Checking connection").assertIsDisplayed()
        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, false)
        compose.onNodeWithText("Limited connectivity").assertIsDisplayed()
    }

    private fun setContent(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n $packageName/androidx.activity.ComponentActivity",
        ).use { }
        compose.waitUntil("Compose host did not resume", 10_000) {
            compose.runOnUiThread {
                host = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull() as? ComponentActivity
            }
            host != null
        }
        compose.runOnUiThread { host!!.setContent { content() } }
        compose.waitForIdle()
    }

    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }

    private class FakeRepository : JourneyRepository {
        val active = MutableStateFlow<Journey?>(null)
        var completions = 0
        override val activeJourney: Flow<Journey?> = active
        override suspend fun createIfNoActive(journey: Journey): Boolean {
            if (active.value != null) return false
            active.value = journey
            return true
        }
        override suspend fun completeActive(completedAt: Long): Journey? {
            val journey = active.value ?: return null
            completions++
            active.value = null
            return journey.copy(status = JourneyStatus.COMPLETED, completedAt = completedAt)
        }
        override fun observeTelemetry(journeyId: String) = flowOf(TelemetrySummary())
        override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? = null
        override fun observeSyncState(journeyId: String) = flowOf<JourneySyncState?>(null)
        override fun observeMonitoringState(journeyId: String) = flowOf<CloudMonitoringState?>(null)
    }

    private class FakeDegradationStore : DegradedConnectivityStateStore {
        val state = MutableStateFlow<DegradedConnectivityState?>(null)
        override suspend fun updateAtomically(journeyId: String,
            transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) ->
                DegradedConnectivityReduction?): DegradedConnectivityReduction? = null
        override fun observe(journeyId: String): Flow<DegradedConnectivityState?> = state
    }

    private object EmptyContacts : TrustedContactGateway {
        override suspend fun list(): List<TrustedContactSummary> = emptyList()
        override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation =
            error("Not used")
        override suspend fun revoke(subjectId: String): Boolean = error("Not used")
    }

    private val unavailableSms = SmsFallbackStatus(false, false, false, emptyList(), null,
        false, false, "SMS fallback unavailable")
}
