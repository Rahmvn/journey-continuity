package com.journeycontinuity.app.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.journeycontinuity.app.data.local.ProbableEndCompletedResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProbableEndAutomaticResultUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resultUsesUncertainCopyAndDoneOnlyAcknowledges() {
        var acknowledged = 0
        compose.setContent {
            ProbableEndAutomaticResultScreen(
                ProbableEndCompletedResult("episode", "journey", "Test destination", 1_700_000_000_000L),
            ) { acknowledged++ }
        }
        compose.onNodeWithTag("probable_end_automatic_title").assertExists()
        compose.onNodeWithText("Monitoring ended automatically").assertExists()
        compose.onNodeWithText("This does not confirm arrival or safety.").assertExists()
        compose.onNodeWithTag("probable_end_automatic_done").performClick()
        compose.runOnIdle { assertEquals(1, acknowledged) }
    }
}
