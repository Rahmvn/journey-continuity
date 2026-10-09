package com.journeycontinuity.app.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.journeycontinuity.app.data.local.ProbableEndPresentationEntity
import com.journeycontinuity.app.data.repository.ProbableEndResponseRequest
import com.journeycontinuity.app.data.repository.ProbableEndTravellerResponse
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.pow

class ProbableEndPrimaryActionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    @After fun finishHost() { compose.runOnUiThread { host?.finish() } }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun settledReadyToPresentedAndInFlightTransitionsRetainPrimaryPaintAndExactResponse() {
        val p = mutableStateOf(ProbableEndPresentationEntity("presentation", "journey", "candidate", 4,
            "alabarin-probable-end-v1", 1_700_000_000_000, 7, createdAtMillis = 1_700_000_100_000))
        val inFlight = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val replies = mutableListOf<Pair<ProbableEndResponseRequest, ProbableEndTravellerResponse>>()
        val j = Journey("journey", "Ilorin", 1_700_003_600_000, 1_700_000_000_000, JourneyStatus.ACTIVE, null)
        host = launchProbableEndUiTestHost(compose)
        compose.runOnUiThread { host!!.setContent {
            if (visible.value) ModalBottomSheet(onDismissRequest = { visible.value = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFFFBFBF8)) {
                JourneyActiveScreen(j, JourneyUiState(isRestoring = false, activeJourney = j),
                    LocationUiState(), SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Unavailable"),
                    onBack = { visible.value = false }, onRetry = {}, onEndCompleted = {},
                    probableEnd = p.value, probableEndResponseInFlight = inFlight.value,
                    onProbableEndResponse = { request, response -> replies += request to response },
                    modifier = Modifier.fillMaxWidth().height(632.dp))
            }
        } }
        compose.onNodeWithTag("probable_end_completed_action").assertIsNotEnabled()
        compose.runOnIdle { p.value = p.value.copy(status = "PRESENTED", presentedAtMillis = 1_700_000_100_100, revision = 1) }
        assertSettledPrimary()
        compose.runOnIdle { p.value = p.value.copy(revision = 2) }
        assertSettledPrimary()
        compose.runOnIdle { inFlight.value = true }
        compose.onNodeWithTag("probable_end_completed_action").assertIsNotEnabled()
        compose.onNodeWithTag("probable_end_keep_monitoring_action").assertIsNotEnabled()
        val busyPaint = compose.onNodeWithTag("probable_end_completed_action").captureToImage().toPixelMap()
        assertTrue("In-flight primary treatment must remain visible",
            busyPaint[(busyPaint.width * 0.1f).toInt(), busyPaint.height / 2].red < 0.8f)
        compose.runOnIdle { inFlight.value = false }
        assertSettledPrimary()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        assertSettledPrimary()
        compose.onNodeWithTag("probable_end_completed_action").performClick()
        compose.runOnIdle {
            assertEquals(1, replies.size)
            assertEquals(ProbableEndTravellerResponse.JOURNEY_COMPLETED, replies.single().second)
            assertEquals(ProbableEndResponseRequest("journey", "presentation", "candidate", 4, 2), replies.single().first)
        }
    }

    private fun assertSettledPrimary() {
        compose.waitForIdle()
        SystemClock.sleep(2_000)
        val primary = compose.onNodeWithTag("probable_end_completed_action").assertIsEnabled()
        val pixels = primary.captureToImage().toPixelMap()
        val fill = pixels[(pixels.width * 0.1f).toInt(), pixels.height / 2]
        val expected = Color(0xFF1B3250)
        assertEquals(expected.red, fill.red, 0.01f)
        assertEquals(expected.green, fill.green, 0.01f)
        assertEquals(expected.blue, fill.blue, 0.01f)
        assertTrue("Primary label must actually paint white text", (0 until pixels.height).any { y ->
            (pixels.width / 4 until pixels.width * 3 / 4).any { x ->
                val p = pixels[x, y]; p.red > 0.99f && p.green > 0.99f && p.blue > 0.99f
            }
        })
        fun linear(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        val luminance = 0.2126 * linear(fill.red) + 0.7152 * linear(fill.green) + 0.0722 * linear(fill.blue)
        assertTrue("White primary text must exceed 4.5:1 against the rendered fill", 1.05 / (luminance + 0.05) >= 4.5)
        compose.onNodeWithTag("probable_end_keep_monitoring_action").assertIsEnabled()
    }
}
