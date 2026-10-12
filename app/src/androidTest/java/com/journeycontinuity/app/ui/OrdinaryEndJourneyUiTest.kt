package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import org.junit.After
import org.junit.Assert.assertEquals
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
