package com.journeycontinuity.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.auth.CreateAccountState
import com.journeycontinuity.app.auth.HandleAvailability
import com.journeycontinuity.app.auth.ReturningLoginState
import com.journeycontinuity.app.auth.TravellerAuthOutcome
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TravellerRootHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null

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

    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }

    @Test fun firstUseAndIdentityChoiceNeverRenderAdmittedRuntime() {
        val auth = FakeAuth()
        val intro = FakeIntro()
        var admittedCompositions = 0
        lateinit var root: TravellerRootViewModel
        setContent {
            root = androidx.compose.runtime.remember {
                TravellerRootViewModel(auth, intro, FakeProgress(), FakeCreateEntry(), FakeLoginEntry())
            }
            TravellerRootHost(root) { admittedCompositions++; Text("Journey runtime") }
        }
        compose.waitUntil("Intro was not shown", 10_000) {
            root.state.value.route == TravellerRootRoute.INTRO
        }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Your Journey, your account").assertIsDisplayed()
        compose.onNodeWithText("Create account").performClick()
        compose.onNodeWithText("Create your account").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, auth.establishCalls)
            assertEquals(0, admittedCompositions)
            assertEquals(TravellerRootRoute.CREATE_DETAILS, root.state.value.route)
        }
    }

    @Test fun createAccountInvalidCodeUsesFrozenFeedbackAtReferenceSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "slice5b-root")
        check(output.mkdirs() || output.isDirectory)
        setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(390.dp, 844.dp).testTag("createOtpFrame")) {
                    CreateAccountCheckEmailScreen(
                        code = "123456", submissionEnabled = true,
                        onCodeChange = {}, onVerify = {}, onSendNewCode = {},
                        onUseDifferentEmail = {}, onLogIn = {}, onBack = {},
                        invalidOrExpired = true,
                    )
                }
            }
        }
        compose.onNodeWithText("That code is invalid or has expired. Request a new code and try again.")
            .assertIsDisplayed()
        compose.onNodeWithText("Verify and continue").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("createOtpFrame").captureToImage().asAndroidBitmap()
        File(output, "create-account-invalid-expired.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    @Test fun narrowShortLargeTextLoginActionsStayReachable() {
        val auth = FakeAuth()
        val intro = FakeIntro(completed = true)
        lateinit var root: TravellerRootViewModel
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                Box(Modifier.size(320.dp, 520.dp).testTag("narrowRootFrame")) {
                    root = remember {
                        TravellerRootViewModel(auth, intro, FakeProgress(),
                            FakeCreateEntry(), FakeLoginEntry())
                    }
                    TravellerRootHost(root) { Text("Journey runtime") }
                }
            }
        }
        compose.waitUntil("Identity Choice was not shown", 10_000) {
            root.state.value.route == TravellerRootRoute.IDENTITY_CHOICE
        }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null), "slice5b-root")
        check(output.mkdirs() || output.isDirectory)
        val bitmap = compose.onNodeWithTag("narrowRootFrame").captureToImage().asAndroidBitmap()
        File(output, "identity-choice-narrow-large-text.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        compose.onNodeWithText("Create an Alabarin account or log in with your verified email. Your session normally stays signed in.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Log in").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(TravellerRootRoute.LOGIN_EMAIL, root.state.value.route) }
        compose.waitUntil("Login email was not shown", 10_000) {
            root.state.value.route == TravellerRootRoute.LOGIN_EMAIL
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Email address").performTextInput("owner@example.com")
        compose.onNodeWithText("Continue").assertIsDisplayed().performClick()
        compose.waitUntil("OTP screen was not shown", 10_000) {
            root.state.value.route == TravellerRootRoute.LOGIN_OTP
        }
        compose.onNodeWithText("Verify and continue").assertIsDisplayed()
        compose.onNodeWithText("Send a new code").assertIsDisplayed()
        compose.onNodeWithText("Use a different email").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Six-digit code").performTextInput("123456")
        compose.onNodeWithText("Verify and continue").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, auth.establishCalls) }
    }

    private class FakeIntro(var completed: Boolean = false) : IntroCompletionPreference {
        override fun isCompleted() = completed
        override fun complete(): Boolean { completed = true; return true }
    }

    private class FakeProgress : CreateAccountProgressPreference {
        override fun pendingOwnerId(): String? = null
        override fun markStarted(ownerId: String) = true
        override fun clear(ownerId: String) = true
    }

    private class FakeLoginEntry : LoginEntryPreference {
        override fun shouldReturnToEmailEntry() = false
        override fun markEmailEntry() = true
        override fun clear() = true
    }

    private class FakeCreateEntry : CreateAccountEntryPreference {
        override fun isPending() = false
        override fun markPending() = true
        override fun clear() = true
    }

    private class FakeAuth : TravellerRootAuth {
        var establishCalls = 0
        override suspend fun resolve() = TravellerAuthOutcome.TravellerIdentityNotEstablished
        override suspend fun establishAnonymousOwner(): TravellerAuthOutcome {
            establishCalls++
            return TravellerAuthOutcome.TravellerIdentityNotEstablished
        }
        override val createAccount: CreateAccountActions = object : CreateAccountActions {
            override suspend fun checkHandleAvailability(handle: String) = HandleAvailability.UNKNOWN
            override suspend fun begin(fullName: String, handle: String, email: String) = CreateAccountState.Ready
            override suspend fun submitCode(code: String) = CreateAccountState.Ready
            override suspend fun completeProfile(fullName: String, handle: String) = CreateAccountState.Ready
            override suspend fun cancel() = Unit
        }
        override val returningLogin: ReturningLoginActions = object : ReturningLoginActions {
            override suspend fun requestCode(email: String) = ReturningLoginState.CodeRequested
            override suspend fun submitCode(code: String) = ReturningLoginState.Idle
            override suspend fun cancel() = Unit
        }
    }
}
