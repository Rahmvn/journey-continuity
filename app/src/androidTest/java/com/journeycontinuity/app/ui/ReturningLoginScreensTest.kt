package com.journeycontinuity.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReturningLoginScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null

    /** Launches the isolated Compose host through the shell on HyperOS. */
    private fun setContent(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        check(packageName.matches(Regex("[a-zA-Z0-9_.]+")))
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n $packageName/androidx.activity.ComponentActivity",
        ).use { }
        compose.waitUntil("Blank Compose host did not resume", 10_000) {
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

    @Test fun emailScreenOnlyEmitsItsSelectedActionAtNarrowWidth() {
        var email by mutableStateOf("")
        val events = mutableListOf<String>()
        setContent {
            Box(Modifier.size(320.dp, 640.dp)) {
                ReturningLoginEmailScreen(email, true,
                    onEmailChange = { email = it; events += "email" },
                    onContinue = { events += "continue" },
                    onCreateAccount = { events += "create" },
                    onBack = { events += "back" })
            }
        }
        compose.onNodeWithText("Log in")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Need an Alabarin account? Create one").assertIsDisplayed()
        compose.onNodeWithContentDescription("Email address").performTextInput("a@example.com")
        compose.runOnIdle { assertEquals("a@example.com", email); assertEquals(listOf("email"), events) }
        events.clear()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertEquals(listOf("continue"), events) }
        events.clear()
        compose.onNodeWithText("Need an Alabarin account? Create one").performClick()
        compose.runOnIdle { assertEquals(listOf("create"), events) }
        events.clear()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("back"), events) }
    }

    @Test fun otpScreenFiltersPasteAndOnlyEmitsSelectedActionsAtNarrowWidth() {
        var code by mutableStateOf("")
        val events = mutableListOf<String>()
        setContent {
            Box(Modifier.size(320.dp, 640.dp)) {
                ReturningLoginCheckEmailScreen(code, true, ReturningLoginOtpFeedback.NEUTRAL,
                    onCodeChange = { code = it; events += "code" },
                    onVerify = { events += "verify" },
                    onSendNewCode = { events += "resend" },
                    onUseDifferentEmail = { events += "different" },
                    onCreateAccount = { events += "create" },
                    onBack = { events += "back" })
            }
        }
        compose.onNodeWithText("Check your email")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithText("Verify and continue").assertIsDisplayed()
        compose.onNodeWithText("Send a new code").assertIsDisplayed()
        compose.onNodeWithText("No account yet? Create one").assertIsDisplayed()
        compose.onNodeWithText("Use a different email").assertIsDisplayed()
        compose.onAllNodesWithTag("loginOtpCell", useUnmergedTree = true).assertCountEquals(6)
        val input = compose.onNodeWithContentDescription("Six-digit code")
        input.performTextInput("12a345678٩")
        compose.runOnIdle { assertEquals("123456", code); assertEquals(listOf("code"), events) }
        events.clear()
        input.performTextReplacement("12")
        compose.runOnIdle { assertEquals("12", code); assertEquals(listOf("code"), events) }
        events.clear()
        compose.onNodeWithText("Verify and continue").performClick()
        compose.runOnIdle { assertEquals(listOf("verify"), events) }
        events.clear()
        compose.onNodeWithText("Send a new code").performClick()
        compose.runOnIdle { assertEquals(listOf("resend"), events) }
        events.clear()
        compose.onNodeWithText("Use a different email").performClick()
        compose.runOnIdle { assertEquals(listOf("different"), events) }
        events.clear()
        compose.onNodeWithText("No account yet? Create one").performClick()
        compose.runOnIdle { assertEquals(listOf("create"), events) }
        events.clear()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("back"), events) }
    }

    @Test fun invalidOrExpiredFeedbackIsExplicitAndKeepsActionsAvailable() {
        var feedback by mutableStateOf(ReturningLoginOtpFeedback.NEUTRAL)
        var verifyCount = 0
        setContent {
            Box(Modifier.size(320.dp, 640.dp)) {
                ReturningLoginCheckEmailScreen("123456", true, feedback,
                    {}, { verifyCount++ }, {}, {}, {}, {})
            }
        }
        val message = "That code is invalid or has expired. Request a new code and try again."
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.runOnIdle { feedback = ReturningLoginOtpFeedback.INVALID_OR_EXPIRED }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Send a new code").assertIsDisplayed()
        compose.onNodeWithText("Use a different email").assertIsDisplayed()
        compose.onNodeWithText("Verify and continue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, verifyCount) }
    }

    @Test fun rateLimitSurfaceOnlyEmitsBack() {
        var backCount = 0
        setContent {
            Box(Modifier.size(320.dp, 640.dp)) {
                AuthRateLimitScreen(onBack = { backCount++ })
            }
        }
        compose.onNodeWithText("Try again later")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithText("Too many code requests or verification attempts were made. Wait a little before trying again.")
            .assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, backCount) }
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, backCount) }
    }

    @Test fun deviceConflictSurfaceOnlyEmitsBackToLogIn() {
        var backCount = 0
        setContent {
            Box(Modifier.size(320.dp, 640.dp)) {
                AuthVerifiedDeviceConflictScreen(onBackToLogIn = { backCount++ })
            }
        }
        compose.onNodeWithText("Signed in on another device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithText("Your email is verified, but Alabarin allows one Traveller device at a time. This phone cannot sign in while the other device remains active.")
            .assertIsDisplayed()
        compose.onNodeWithText("If a Journey is active on the other device").assertIsDisplayed()
        compose.onNodeWithText("End that Journey and sign out there first. Active Journey monitoring never transfers to this phone.")
            .assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, backCount) }
        compose.onNodeWithText("Back to log in").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, backCount) }
    }

    @Test fun capturesFiveFrozenReferenceStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "slice4-returning-login")
        check(output.mkdirs() || output.isDirectory)
        var screen by mutableStateOf(0)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Box(Modifier.size(390.dp, 844.dp).testTag("loginFrame")) {
                    when (screen) {
                        0 -> ReturningLoginEmailScreen("", true, {}, {}, {}, {})
                        1 -> ReturningLoginCheckEmailScreen("12", true,
                            ReturningLoginOtpFeedback.NEUTRAL, {}, {}, {}, {}, {}, {})
                        2 -> ReturningLoginCheckEmailScreen("123456", true,
                            ReturningLoginOtpFeedback.INVALID_OR_EXPIRED, {}, {}, {}, {}, {}, {})
                        3 -> AuthRateLimitScreen({})
                        else -> AuthVerifiedDeviceConflictScreen({})
                    }
                }
            }
        }
        fun capture(name: String) {
            val bitmap = compose.onNodeWithTag("loginFrame").captureToImage().asAndroidBitmap()
            File(output, name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
        capture("login-email.png")
        compose.runOnIdle { screen = 1 }
        capture("login-check-email.png")
        compose.runOnIdle { screen = 2 }
        capture("login-invalid-expired.png")
        compose.runOnIdle { screen = 3 }
        capture("auth-rate-limit.png")
        compose.runOnIdle { screen = 4 }
        capture("auth-device-conflict.png")
    }
}
