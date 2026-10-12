package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.auth.AuthenticatedTraveller
import com.journeycontinuity.app.auth.TravellerAuthOutcome
import com.journeycontinuity.app.auth.TravellerSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Production root and ViewModel; auth responses are simulated, never sent to a backend. */
class StartupRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    private val store = ViewModelStore()
    private lateinit var root: TravellerRootViewModel
    private class Auth : TravellerRootAuth {
        override var startupConfigured = true
        var outcome: TravellerAuthOutcome = TravellerAuthOutcome.TemporaryAuthUnavailable
        var checks = 0
        val sessions = MutableStateFlow<TravellerSessionState>(TravellerSessionState.Initializing)
        override val sessionStates = sessions
        override suspend fun resolve(): TravellerAuthOutcome { checks++; return outcome }
        override suspend fun establishAnonymousOwner(): TravellerAuthOutcome = error("Must not create an owner")
        override val createAccount: CreateAccountActions? = null
        override val returningLogin: ReturningLoginActions? = null
    }
    private fun open(auth: Auth) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand("am start -n $pkg/androidx.activity.ComponentActivity").use { }
        compose.waitUntil("Host did not resume", 10_000) {
            compose.runOnUiThread {
                host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it.javaClass == ComponentActivity::class.java } as? ComponentActivity
            }
            host != null
        }
        compose.runOnUiThread {
            host!!.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            root = ViewModelProvider(store, TravellerRootViewModelFactory(auth,
                object : IntroCompletionPreference {
                    override fun isCompleted() = true
                    override fun complete() = error("Unexpected preference write")
                }, object : CreateAccountProgressPreference {
                    override fun pendingOwnerId(): String? = null
                    override fun markStarted(ownerId: String) = error("Unexpected preference write")
                    override fun clear(ownerId: String) = error("Unexpected preference write")
                }, object : CreateAccountEntryPreference {
                    override fun isPending() = false
                    override fun markPending() = error("Unexpected preference write")
                    override fun clear() = error("Unexpected preference write")
                }, object : LoginEntryPreference {
                    override fun shouldReturnToEmailEntry() = false
                    override fun markEmailEntry() = error("Unexpected preference write")
                    override fun clear() = error("Unexpected preference write")
                }))[TravellerRootViewModel::class.java]
            host!!.setContent { TravellerRootHost(root) { Text("Admitted existing Traveller") } }
        }
        compose.waitForIdle()
    }
    private fun awaitFailure() {
        compose.waitUntil("Bounded startup did not finish", 12_000) {
            root.state.value.startupTemporarilyUnavailable
        }
        compose.onNodeWithText("Unable to connect").assertIsDisplayed()
    }
    @After fun close() { compose.runOnUiThread { store.clear(); host?.finish() } }

    @Test fun retryShowsProgressAndFreshFailureThenAllowsSuccess() {
        val auth = Auth()
        open(auth)
        awaitFailure()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Opening Alabarin").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        awaitFailure()
        compose.runOnIdle { assertEquals(6, auth.checks); auth.outcome = TravellerAuthOutcome.Authenticated(AuthenticatedTraveller("owner-a")) }
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { root.state.value.route == TravellerRootRoute.ADMITTED_EXISTING }
        compose.onNodeWithText("Admitted existing Traveller").assertIsDisplayed()
        compose.runOnIdle { assertEquals(7, auth.checks) }
    }
    @Test fun restoredSdkSessionLeavesFailureWithoutTap() {
        val auth = Auth()
        open(auth)
        awaitFailure()
        compose.runOnIdle {
            auth.outcome = TravellerAuthOutcome.Authenticated(AuthenticatedTraveller("owner-a"))
            auth.sessions.value = TravellerSessionState.Authenticated("owner-a")
        }
        compose.waitUntil(5_000) { root.state.value.route == TravellerRootRoute.ADMITTED_EXISTING }
        compose.onNodeWithText("Admitted existing Traveller").assertIsDisplayed()
    }
    @Test fun missingConfigurationHasNoDeadRetryAction() {
        val auth = Auth().apply { startupConfigured = false }
        open(auth)
        compose.waitUntil(5_000) { root.state.value.route == TravellerRootRoute.RECOVERY }
        compose.onNodeWithText("Unable to open Alabarin").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, auth.checks) }
    }
}
