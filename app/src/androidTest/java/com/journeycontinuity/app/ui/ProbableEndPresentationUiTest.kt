package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.journeycontinuity.app.data.local.ProbableEndPresentationEntity
import com.journeycontinuity.app.data.local.ProbableEndPresentationStatus
import com.journeycontinuity.app.data.repository.ProbableEndTravellerResponse
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProbableEndPresentationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    private val journey = Journey("visual", "Ilorin", System.currentTimeMillis() + 3_600_000,
        System.currentTimeMillis() - 3_600_000, JourneyStatus.ACTIVE, null)
    private val episode = ProbableEndPresentationEntity("presentation", "visual", "candidate", 1,
        "alabarin-probable-end-v1", 1_700_000_000_000L, 0,
        status = ProbableEndPresentationStatus.PRESENTED.name,
        createdAtMillis = 1_700_000_100_000L, presentedAtMillis = 1_700_000_100_100L,
        revision = 1)
    private val sms = SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Unavailable")
    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }

    @Test fun exactQuestionAndEnabledResponseControlsForwardOnlyTheExplicitTap() {
        var opportunityLayouts = 0
        var completed = 0
        var dismissed = 0
        val responses = mutableListOf<ProbableEndTravellerResponse>()
        val sheetHeight = mutableStateOf(0.dp)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n $packageName/androidx.activity.ComponentActivity").use { }
        compose.waitUntil("Compose host did not resume", 10_000) {
            compose.runOnUiThread {
                host = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull() as? ComponentActivity
            }
            host != null
        }
        compose.runOnUiThread {
            host!!.setContent {
                JourneyActiveScreen(journey, JourneyUiState(isRestoring = false, activeJourney = journey),
                    LocationUiState(), sms, onBack = { dismissed++ }, onRetry = {}, onEndCompleted = { completed++ },
                    probableEnd = episode, onProbableEndRendered = { _, id, view ->
                        assertEquals(true, view.hasWindowFocus())
                        if (id == episode.id) opportunityLayouts++
                    }, onProbableEndResponse = { request, response ->
                        assertEquals(episode.id, request.presentationEpisodeId)
                        assertEquals(episode.revision, request.presentationRevision)
                        responses += response
                    }, modifier = Modifier.fillMaxWidth().height(sheetHeight.value))
            }
        }
        compose.runOnIdle {
            assertEquals(0, opportunityLayouts)
            sheetHeight.value = 632.dp
        }
        compose.onNodeWithTag("probable_end_prompt").assertExists()
        compose.onNodeWithTag("probable_end_title").assertExists()
        compose.onNodeWithText("Has this Journey ended?").assertExists()
        compose.onNodeWithText("Alabarin detected signs that this Journey may have ended. If you don’t respond, monitoring may stop automatically after 30 minutes.")
            .assertExists()
        compose.onNodeWithTag("probable_end_completed_action").assertIsEnabled()
        compose.onNodeWithTag("probable_end_keep_monitoring_action").assertIsEnabled()
        compose.onNodeWithTag("probable_end_keep_monitoring_action").assertIsDisplayed()
        compose.onNodeWithText("Journey completed").assertExists()
        compose.onNodeWithText("Keep monitoring").assertExists()
        compose.waitUntil("Visible prompt did not report a foreground opportunity", 10_000) {
            opportunityLayouts > 0
        }
        compose.runOnIdle {
            assertEquals(true, opportunityLayouts >= 1)
            assertEquals(0, completed)
        }
        compose.onNodeWithTag("probable_end_keep_monitoring_action").performClick()
        compose.runOnIdle { assertEquals(listOf(ProbableEndTravellerResponse.KEEP_MONITORING), responses) }
        compose.onNodeWithContentDescription("Return to Home").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, completed)
        }
    }
}
