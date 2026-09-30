package com.journeycontinuity.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test

class CreateAccountScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null

    /** Shell launch keeps the blank Compose host independent of HyperOS background-launch policy. */
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

    @After
    fun closeHost() {
        compose.runOnUiThread { host?.finish() }
    }

    @Test
    fun detailsEditsAndActionsOnlyCallTheirOwnCallbacks() {
        var fullName by mutableStateOf("")
        var handle by mutableStateOf("")
        var email by mutableStateOf("")
        val events = mutableListOf<String>()
        setContent {
            CreateAccountDetailsScreen(
                fullName, handle, email,
                CreateAccountHandleFeedback(handle, CreateAccountHandleStatus.AVAILABLE), true,
                onFullNameChange = { fullName = it; events += "name" },
                onHandleChange = { handle = it; events += "handle" },
                onEmailChange = { email = it; events += "email" },
                onContinue = { events += "continue" },
                onBack = { events += "back" },
                onLogIn = { events += "login" },
            )
        }
        compose.onNodeWithText("Create your account")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }

        compose.onNodeWithContentDescription("Full name").performTextInput("Amina Yusuf")
        compose.runOnIdle { assertEquals("Amina Yusuf", fullName); assertEquals(listOf("name"), events) }
        events.clear()
        compose.onNodeWithContentDescription("Alabarin handle").performTextInput("@amina")
        compose.runOnIdle { assertEquals("@amina", handle); assertEquals(listOf("handle"), events) }
        events.clear()
        compose.onNodeWithContentDescription("Email address").performTextInput("a@example.com")
        compose.runOnIdle { assertEquals("a@example.com", email); assertEquals(listOf("email"), events) }
        events.clear()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertEquals(listOf("continue"), events) }
        events.clear()
        compose.onNodeWithText("Already have an account? Log in").performClick()
        compose.runOnIdle { assertEquals(listOf("login"), events) }
        events.clear()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("back"), events) }
    }

    @Test
    fun handleFeedbackIsBoundToCurrentInputAndActionsFitNarrowPhone() {
        var handle by mutableStateOf("@rahmvn")
        var feedback by mutableStateOf(CreateAccountHandleFeedback(handle, CreateAccountHandleStatus.CHECKING))
        var continueCount = 0
        setContent {
            Box(Modifier.size(320.dp, 640.dp)) {
                CreateAccountDetailsScreen("", handle, "", feedback, true,
                    {}, { handle = it }, {}, { continueCount++ }, {}, {})
            }
        }
        compose.onNodeWithText("Checking availability…").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Already have an account? Log in").assertIsDisplayed()

        compose.runOnIdle { feedback = CreateAccountHandleFeedback(handle, CreateAccountHandleStatus.AVAILABLE) }
        compose.onNodeWithText("Available").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsEnabled()

        compose.runOnIdle { handle = "@newhandle" }
        compose.onNodeWithText("Available").assertDoesNotExist()
        compose.onNodeWithText("Continue").assertIsNotEnabled()

        compose.runOnIdle { feedback = CreateAccountHandleFeedback(handle, CreateAccountHandleStatus.TAKEN) }
        compose.onNodeWithText("That handle is taken. Try another.").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, continueCount) }
    }

    @Test
    fun otpEntryAndEachActionOnlyCallTheirOwnCallbacksAtNarrowWidth() {
        var code by mutableStateOf("")
        val events = mutableListOf<String>()
        setContent {
            Box(Modifier.size(320.dp, 640.dp)) {
                CreateAccountCheckEmailScreen(code, true,
                    onCodeChange = { code = it; events += "code" },
                    onVerify = { events += "verify" },
                    onSendNewCode = { events += "resend" },
                    onUseDifferentEmail = { events += "email" },
                    onLogIn = { events += "login" },
                    onBack = { events += "back" })
            }
        }
        compose.onNodeWithText("Check your email")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithText("Send a new code").assertIsDisplayed()
        compose.onNodeWithText("Use a different email").assertIsDisplayed()
        compose.onNodeWithText("Already have an account? Log in").assertIsDisplayed()
        compose.onNodeWithText("Verify and continue").assertIsDisplayed()
        compose.onAllNodesWithTag("otpCell", useUnmergedTree = true).assertCountEquals(6)

        compose.onNodeWithContentDescription("Six-digit code").performTextInput("12a34567")
        compose.runOnIdle { assertEquals("123456", code); assertEquals(listOf("code"), events) }
        events.clear()
        compose.onNodeWithText("Verify and continue").performClick()
        compose.runOnIdle { assertEquals(listOf("verify"), events) }
        events.clear()
        compose.onNodeWithText("Send a new code").performClick()
        compose.runOnIdle { assertEquals(listOf("resend"), events) }
        events.clear()
        compose.onNodeWithText("Use a different email").performClick()
        compose.runOnIdle { assertEquals(listOf("email"), events) }
        events.clear()
        compose.onNodeWithText("Already have an account? Log in").performClick()
        compose.runOnIdle { assertEquals(listOf("login"), events) }
        events.clear()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("back"), events) }
    }

    @Test
    fun capturesFourFrozenReferenceStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "slice3-create-account")
        check(output.mkdirs() || output.isDirectory)
        var screen by mutableStateOf(0)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Box(Modifier.size(390.dp, 844.dp).testTag("createAccountFrame")) {
                    if (screen < 3) {
                        val handle = if (screen == 2) "@abdulrahman" else "@rahmvn"
                        val status = when (screen) {
                            0 -> CreateAccountHandleStatus.AVAILABLE
                            1 -> CreateAccountHandleStatus.CHECKING
                            else -> CreateAccountHandleStatus.TAKEN
                        }
                        CreateAccountDetailsScreen("", handle, "",
                            CreateAccountHandleFeedback(handle, status), true,
                            {}, {}, {}, {}, {}, {})
                    } else {
                        CreateAccountCheckEmailScreen("12", true, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        fun capture(name: String) {
            val bitmap = compose.onNodeWithTag("createAccountFrame").captureToImage().asAndroidBitmap()
            File(output, name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
        capture("details-available.png")
        compose.runOnIdle { screen = 1 }
        capture("handle-checking.png")
        compose.runOnIdle { screen = 2 }
        capture("handle-taken.png")
        compose.runOnIdle { screen = 3 }
        capture("check-email-otp.png")
    }
}
