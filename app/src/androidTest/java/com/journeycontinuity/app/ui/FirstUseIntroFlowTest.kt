package com.journeycontinuity.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FirstUseIntroFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun forwardStepsDoNotCompleteAndFinalActionCompletesOnce() {
        val completions = mutableListOf<FirstUseCompletion>()
        compose.setContent { FirstUseIntroFlow(onCompleted = completions::add) }

        compose.onNodeWithText("Stay connected through the Journey").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Only while a Journey is running").assertExists()
        compose.runOnIdle { assertEquals(0, completions.size) }

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("You stay in control").assertExists()
        compose.runOnIdle { assertEquals(0, completions.size) }

        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertEquals(listOf(FirstUseCompletion.INTRODUCTION_FINISHED), completions)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, completions.size) }
    }

    @Test
    fun currentStepSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FirstUseIntroFlow(onCompleted = {}) }

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Only while a Journey is running").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Only while a Journey is running").assertExists()
    }

    @Test
    fun captureAllThreeReferenceFramesForVisualReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "first-use-intro")
        check(output.mkdirs() || output.isDirectory)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 390.dp, height = 844.dp).testTag("introFrame")) {
                    FirstUseIntroFlow(onCompleted = {})
                }
            }
        }

        fun capture(name: String) {
            val bitmap = compose.onNodeWithTag("introFrame").captureToImage().asAndroidBitmap()
            File(output, name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }

        capture("purpose.png")
        compose.onNodeWithText("Next").performClick()
        capture("journey-scoped-monitoring.png")
        compose.onNodeWithText("Next").performClick()
        capture("privacy-control.png")
    }
}
