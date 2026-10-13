package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.degraded.*
import com.journeycontinuity.app.service.JourneyMonitoringController
import com.journeycontinuity.app.trusted.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI geometry and actions only; the production route and authority operation are tested separately. */
class OrdinaryEndJourneyUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    private val journey = Journey("ui-end", "Ilorin", System.currentTimeMillis() + 3_600_000,
        System.currentTimeMillis(), JourneyStatus.ACTIVE, null)

    private fun show(width: Int, height: Int = 844, fontScale: Float = 1f,
        content: @androidx.compose.runtime.Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val density = instrumentation.targetContext.resources.displayMetrics.density
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n $packageName/androidx.activity.ComponentActivity").use { }
        compose.waitUntil("Compose host did not resume", 10_000) {
            compose.runOnUiThread {
                host = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull() as? ComponentActivity
            }
            host != null
        }
        compose.runOnUiThread { host!!.setContent {
            CompositionLocalProvider(LocalDensity provides Density(
                density, fontScale)) {
                Box(Modifier.size(width.dp, height.dp)) { content() }
            }
        } }
        compose.waitForIdle()
    }

    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun endPageDrawsBeforeJourneyWindowIsRemovedAndBackReopensJourney() {
        val repository = object : JourneyRepository {
            override val activeJourney = MutableStateFlow<Journey?>(journey)
            override suspend fun createIfNoActive(journey: Journey) = false
            override suspend fun completeActive(completedAt: Long): Journey? = error("Navigation only")
            override suspend fun stopMonitoringActive(journeyId: String, endedAt: Long): Journey? =
                error("Navigation only")
            override fun observeTelemetry(journeyId: String) = flowOf(TelemetrySummary())
            override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? = null
            override fun observeSyncState(journeyId: String) = flowOf<JourneySyncState?>(null)
            override fun observeMonitoringState(journeyId: String) = flowOf<CloudMonitoringState?>(null)
        }
        val coordinator = DegradedConnectivityCoordinator(object : DegradedConnectivityStateStore {
            override fun observe(journeyId: String) = flowOf<DegradedConnectivityState?>(null)
            override suspend fun updateAtomically(journeyId: String,
                transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?) = null
        }, LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
            DegradedConnectivityLabConfiguration.policyConfig()))
        lateinit var viewModel: JourneyViewModel
        val store = ViewModelStore()
        compose.runOnUiThread {
            viewModel = JourneyViewModel(repository, JourneyLifecycle(repository),
                object : JourneyMonitoringController {
                    override fun start() = Unit
                    override fun stop() = Unit
                }, object : TrustedContactGateway {
                    override val invitationSharingAvailable = false
                    override suspend fun list() = emptyList<TrustedContactSummary>()
                    override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation =
                        error("Navigation only")
                    override suspend fun revoke(subjectId: String) = false
                }, coordinator)
            store.put("navigation", viewModel)
        }
        var firstEndDrawKeptSheet: Boolean? = null
        var listener: ViewTreeObserver.OnDrawListener? = null
        try {
            show(390) {
                JourneyProductRoot(viewModel, LocationUiState(),
                    SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Unavailable"),
                    "test@example.com", onRetryMonitoring = {}, onOpenLocationSettings = {},
                    onRequestSmsPermissions = {}, onSelectSmsSubscription = {})
            }
            val homeWindows = WindowInspector.getGlobalWindowViews().toSet()
            compose.onNodeWithText("Open Journey").performClick()
            compose.onNodeWithTag("journey_sheet").assertIsDisplayed()
            val sheetWindow = WindowInspector.getGlobalWindowViews().single { it !in homeWindows }
            compose.runOnUiThread {
                listener = ViewTreeObserver.OnDrawListener {
                    if (viewModel.uiState.value.productRoute == JourneyProductRoute.END &&
                        firstEndDrawKeptSheet == null) {
                        firstEndDrawKeptSheet = sheetWindow.isAttachedToWindow
                    }
                }
                host!!.window.decorView.viewTreeObserver.addOnDrawListener(listener)
            }
            compose.onNodeWithText("End Journey").performClick()
            compose.onNodeWithTag("ordinary_end_complete_choice").assertIsDisplayed()
            compose.onNodeWithTag("journey_sheet").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("The Journey window must cover Home until End Journey draws",
                    true, firstEndDrawKeptSheet)
                assertTrue(repository.activeJourney.value?.status == JourneyStatus.ACTIVE)
            }
            compose.onNodeWithTag("ordinary_end_back").performClick()
            compose.onNodeWithTag("journey_sheet").assertIsDisplayed()
            compose.onNodeWithText("End Journey").assertIsDisplayed()
        } finally {
            compose.runOnUiThread {
                listener?.let { host?.window?.decorView?.viewTreeObserver?.removeOnDrawListener(it) }
                store.clear()
            }
        }
    }

    @Test fun choiceConfirmCancelAndBothBranchesHaveCorrectActions() {
        var complete = 0
        var stop = 0
        var back = 0
        show(390) {
            OrdinaryEndChoiceScreen(JourneyUiState(isRestoring = false, activeJourney = journey,
                productRoute = JourneyProductRoute.END, endFlowJourneyId = journey.id),
                onBack = { back++ }, onComplete = { complete++ }, onStop = { stop++ })
        }
        compose.onNodeWithTag("ordinary_end_complete_choice").performClick()
        compose.onNodeWithText("Complete Journey?").assertIsDisplayed()
        compose.onNodeWithTag("ordinary_end_confirm_cancel").performClick()
        assertEquals(0, complete)
        compose.onNodeWithTag("ordinary_end_stop_choice").performClick()
        compose.onNodeWithText("Stop monitoring?").assertIsDisplayed()
        compose.onNodeWithTag("ordinary_end_confirm_action").performClick()
        assertEquals(1, stop)
        assertEquals(0, complete)
        compose.onNodeWithTag("ordinary_end_confirm_cancel").performClick()
        compose.onNodeWithTag("ordinary_end_complete_choice").performClick()
        compose.onNodeWithTag("ordinary_end_confirm_action").performClick()
        assertEquals(1, complete)
        compose.onNodeWithTag("ordinary_end_confirm_cancel").performClick()
        compose.onNodeWithTag("ordinary_end_back").performClick()
        assertEquals(1, back)
    }

    @Test fun resultTitlesAndDoneFitCompactAndLargeText() {
        var done = 0
        show(320, 640, 1.3f) {
            OrdinaryEndResultScreen(journey.copy(status = JourneyStatus.COMPLETED,
                completedAt = System.currentTimeMillis()), onDone = { done++ })
        }
        compose.onNodeWithText("Journey completed").assertIsDisplayed()
        compose.onNodeWithTag("ordinary_end_done").assertIsDisplayed().performClick()
        assertEquals(1, done)
        compose.runOnUiThread { host!!.setContent {
            Box(Modifier.size(360.dp, 700.dp)) {
                OrdinaryEndResultScreen(journey.copy(status = JourneyStatus.CANCELLED,
                    endedAt = System.currentTimeMillis()), onDone = { done++ })
            }
        } }
        compose.onNodeWithText("Monitoring stopped").assertIsDisplayed()
        compose.onNodeWithTag("ordinary_end_done").assertIsDisplayed()
    }

    @Test fun compactChoiceAndConfirmationKeepActionsVisibleAtLargeText() {
        show(320, 640, 1.3f) {
            OrdinaryEndChoiceScreen(JourneyUiState(isRestoring = false, activeJourney = journey,
                productRoute = JourneyProductRoute.END, endFlowJourneyId = journey.id),
                onBack = {}, onComplete = {}, onStop = {})
        }
        compose.onNodeWithTag("ordinary_end_stop_choice").performScrollTo().performClick()
        compose.onNodeWithText("Stop monitoring?").assertIsDisplayed()
        compose.onNodeWithTag("ordinary_end_confirm_action").assertIsDisplayed()
        compose.onNodeWithTag("ordinary_end_confirm_cancel").assertIsDisplayed()
    }
}
