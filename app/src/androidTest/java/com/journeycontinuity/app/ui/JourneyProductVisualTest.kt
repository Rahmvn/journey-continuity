package com.journeycontinuity.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Isolated visual states. These never create or end a real Journey on the test device. */
class JourneyProductVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    private val journey = Journey("visual-journey", "Ilorin", System.currentTimeMillis() + 7_200_000,
        System.currentTimeMillis() - 3_600_000, JourneyStatus.ACTIVE, null)

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

    @Test fun homeUsesCorrectActionForPersistedJourneyAndStartNeedsExplicitArrival() {
        setContent {
            Box(Modifier.size(390.dp, 844.dp).testTag("journeyFrame")) {
                JourneyHome(JourneyUiState(isRestoring = false), null, "Unavailable",
                    onPrimary = {}, onContacts = {}, onResilience = {}, onAccount = {})
            }
        }
        compose.onNodeWithText("Start Journey").assertIsDisplayed()
        compose.onNodeWithText("Open Journey").assertDoesNotExist()
        capture("home-no-active")
        compose.runOnUiThread {
            host!!.setContent {
                Box(Modifier.size(390.dp, 844.dp).testTag("journeyFrame")) {
                    JourneyStartScreen(JourneyUiState(isRestoring = false), {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Choose date and time").assertIsDisplayed()
        compose.onNodeWithTag("journey_primary_action").assertIsDisplayed()
        capture("start-setup")
        compose.runOnUiThread {
            host!!.setContent {
                Box(Modifier.size(390.dp, 844.dp).testTag("journeyFrame")) {
                    JourneyStartScreen(JourneyUiState(isRestoring = false,
                        draftDestination = "Ilorin",
                        draftExpectedArrivalAt = System.currentTimeMillis() + 7_200_000),
                        {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Ilorin").assertIsDisplayed()
        capture("start-ready")
    }

    @Test fun activeHomeAndActiveScreenPreserveProductWording() {
        var returnedHome = 0
        setContent {
            Box(Modifier.size(390.dp, 844.dp).testTag("journeyFrame")) {
                JourneyHome(JourneyUiState(isRestoring = false, activeJourney = journey),
                    "traveller@example.com", "Unavailable", {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Open Journey").assertIsDisplayed()
        compose.onNodeWithText("Continue Journey").assertDoesNotExist()
        capture("home-active")
        compose.runOnUiThread {
            host!!.setContent {
                Box(Modifier.size(390.dp, 844.dp).testTag("journeyFrame")) {
                    JourneyActiveScreen(journey,
                        JourneyUiState(isRestoring = false, activeJourney = journey,
                            monitoringJourneyId = journey.id),
                        LocationUiState(ForegroundLocationAccess.PRECISE), false,
                        { returnedHome++ }, {}, {})
                }
            }
        }
        compose.onNodeWithText("Monitoring in background").assertIsDisplayed()
        compose.onNodeWithText("Latitude").assertDoesNotExist()
        compose.onNodeWithText("Evidence fresh").assertDoesNotExist()
        capture("active-normal")
        compose.onNodeWithContentDescription("Return to Home").performClick()
        compose.runOnIdle { assertEquals(1, returnedHome) }
    }

    @Test fun optionalCheckpointKeepsStartAnywayAvailable() {
        var starts = 0
        setContent {
            Box(Modifier.size(390.dp, 382.dp).testTag("journeyFrame")) {
                JourneyCheckpoint(false, { starts++ }, {})
            }
        }
        compose.onNodeWithText("Resilience setup needed").assertIsDisplayed()
        compose.onNodeWithText("Start anyway").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        capture("resilience-checkpoint")
    }

    @Test fun stoppedMonitoringAndLimitedConnectivityNeverClaimDelivery() {
        setContent {
            Box(Modifier.size(320.dp, 520.dp).testTag("journeyFrame")) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                    JourneyActiveScreen(journey,
                        JourneyUiState(isRestoring = false, activeJourney = journey,
                            degradation = DegradedConnectivityState(
                                journeyId = journey.id, journeyActive = true,
                                connectivityPhase = ConnectivityPhase.DEGRADED)),
                        LocationUiState(), false, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Monitoring needs attention").assertIsDisplayed()
        compose.onNodeWithText("Limited connectivity").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Monitoring in background").assertDoesNotExist()
        compose.onNodeWithText("Delivered").assertDoesNotExist()
        capture("active-limited-narrow-large-text")
        compose.onNodeWithText(
            "Fresh information may not reach trusted contacts until communication is restored.",
        ).performScrollTo().assertIsDisplayed()
        capture("active-limited-detail-narrow-large-text")
    }

    @Test fun recoveringUsesConnectionRestoredCopy() {
        setContent {
            Box(Modifier.size(390.dp, 844.dp).testTag("journeyFrame")) {
                JourneyActiveScreen(journey,
                    JourneyUiState(isRestoring = false, activeJourney = journey,
                        monitoringJourneyId = journey.id,
                        currentConnectivity = CurrentJourneyConnectivity(journey.id, true),
                        degradation = DegradedConnectivityState(
                            journeyId = journey.id, journeyActive = true,
                            connectivityPhase = ConnectivityPhase.RECOVERING)),
                    LocationUiState(ForegroundLocationAccess.PRECISE), false, {}, {}, {})
            }
        }
        compose.onNodeWithText("Connection restored").assertIsDisplayed()
        compose.onNodeWithText("Alabarin is syncing recent information while communication stabilizes.")
            .assertIsDisplayed()
        capture("active-recovering")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "journey-core-visual")
        check(output.mkdirs() || output.isDirectory)
        val bitmap = compose.onNodeWithTag("journeyFrame").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
