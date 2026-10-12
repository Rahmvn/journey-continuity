package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.degraded.evaluateSmsFallbackStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SheetCloseSemanticsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null

    private fun setContent(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        check(packageName.matches(Regex("[a-zA-Z0-9_.]+")))
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

    @After fun closeHost() {
        compose.runOnUiThread { host?.finish() }
    }

    @Test fun trustedContactsCloseIsOneNamedButton() {
        var dismissals = 0
        setContent {
            TrustedContactsSheet(JourneyUiState(), onDismiss = { dismissals++ },
                onAdd = {}, onCreate = { _, _ -> }, onDone = {}, onRemove = {}, onShare = {})
        }
        assertClose("trusted_contacts_close", "Close Trusted Contacts")
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun resilienceCloseIsOneNamedButton() {
        var dismissals = 0
        val status = evaluateSmsFallbackStatus(false, false, false, emptyList(), null, false)
        setContent {
            ResilienceSheet(resiliencePresentation(status, TrustedContactsAvailability.LOADING, 0),
                status, ResiliencePage.STATUS, onDismiss = { dismissals++ },
                onAddContact = {}, onAllowSms = {}, onAllowPhoneAccess = {},
                onChooseSim = {}, onSelectSim = {})
        }
        assertClose("resilience_close", "Close Resilience")
        compose.onNodeWithTag("resilience_close").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    private fun assertClose(tag: String, label: String) {
        compose.onAllNodesWithContentDescription(label).assertCountEquals(1)
        compose.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
        compose.onAllNodesWithText("×").assertCountEquals(0)
    }
}
