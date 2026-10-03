package com.journeycontinuity.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.journeycontinuity.app.auth.TravellerAccountGateway
import com.journeycontinuity.app.auth.TravellerAccountProfile
import com.journeycontinuity.app.auth.NoActiveSignOutResult
import com.journeycontinuity.app.auth.TravellerNoActiveSignOutGateway
import com.journeycontinuity.app.auth.ActiveJourneySignOutResult
import com.journeycontinuity.app.auth.TravellerActiveJourneySignOutGateway
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityCoordinator
import com.journeycontinuity.app.degraded.DegradedConnectivityLabConfiguration
import com.journeycontinuity.app.degraded.DegradedConnectivityPolicy
import com.journeycontinuity.app.degraded.DegradedConnectivityReduction
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.degraded.DegradedConnectivityStateStore
import com.journeycontinuity.app.degraded.LatestTelemetryReader
import com.journeycontinuity.app.degraded.RecoveryBacklogSnapshot
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.degraded.SmsSubscriptionChoice
import com.journeycontinuity.app.degraded.evaluateSmsFallbackStatus
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.domain.TelemetrySummary
import com.journeycontinuity.app.service.JourneyServiceController
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import com.journeycontinuity.app.trusted.CreatedTrustedContactInvitation
import com.journeycontinuity.app.trusted.TrustedContactGateway
import com.journeycontinuity.app.trusted.TrustedContactSummary
import com.journeycontinuity.app.trusted.TrustedContactStatus
import com.journeycontinuity.app.trusted.TrustedContactSubjectKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the real product root and JourneyLifecycle against isolated in-memory state. */
class JourneyProductFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun accountOpensAsSheetOverHomeAndSignOutIsCallbackOnly() {
        val gateway = FakeAccountGateway()
        var signOutRequests = 0
        val (journey, account) = showAccount(gateway) { signOutRequests++ }
        compose.onNodeWithText("Account").performClick()
        compose.waitUntil("Account profile did not load", 10_000) { account.state.value.profile != null }
        compose.onNodeWithTag("account_sheet").assertIsDisplayed()
        compose.onNodeWithText("Ada Owner").assertIsDisplayed()
        compose.onNodeWithText("@ada_owner").assertIsDisplayed()
        compose.onNodeWithTag("account_verified_email").assertIsDisplayed()
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.runOnIdle {
            assertEquals(1, signOutRequests)
            assertEquals(JourneyProductRoute.HOME, journey.uiState.value.productRoute)
            assertEquals(0, gateway.saves)
        }
        compose.onNodeWithTag("account_sheet").assertIsDisplayed()
        compose.onNodeWithTag("account_close").performClick()
        compose.onNodeWithTag("account_sheet").assertDoesNotExist()
    }

    @Test fun accountEditOverlayPreservesEmailAndSavesIdentity() {
        val gateway = FakeAccountGateway()
        val (_, account) = showAccount(gateway) {}
        compose.onNodeWithText("Account").performClick()
        compose.waitUntil("Account profile did not load", 10_000) { account.state.value.profile != null }
        compose.onNodeWithTag("account_edit").performClick()
        compose.onNodeWithTag("account_edit_overlay").assertIsDisplayed()
        compose.onNodeWithTag("account_full_name_input").performTextReplacement("Ada New")
        compose.onNodeWithTag("account_handle_input").performTextReplacement("@new_handle")
        compose.waitUntil("Handle was not checked", 10_000) {
            account.state.value.handleStatus == AccountHandleStatus.AVAILABLE
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag("account_save").performClick()
        compose.waitUntil("Account save did not finish", 10_000) { !account.state.value.editOpen }
        compose.onNodeWithTag("account_edit_overlay").assertDoesNotExist()
        compose.onNodeWithText("Ada New").assertIsDisplayed()
        compose.onNodeWithText("@new_handle").assertIsDisplayed()
        compose.onNodeWithTag("account_verified_email").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, gateway.saves) }
    }

    @Test fun noActiveSignOutConfirmationStaysOverAccountAndStaySignedInDismissesOnlyDialog() {
        val signOut = FakeNoActiveSignOut()
        val (_, account) = showAccount(FakeAccountGateway(), signOut) {}
        compose.onNodeWithText("Account").performClick()
        compose.waitUntil("Account profile did not load", 10_000) { account.state.value.profile != null }
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.waitUntil("Sign out confirmation did not open", 10_000) {
            account.state.value.signOutStatus == AccountSignOutStatus.CONFIRMING
        }
        compose.onNodeWithTag("account_sheet").assertExists()
        compose.onNodeWithTag("account_sign_out_confirmation").assertIsDisplayed()
        compose.onNodeWithText("Sign out?").assertIsDisplayed()
        compose.onNodeWithText("You’ll need a new one-time code the next time you log in on this device.")
            .assertIsDisplayed()
        compose.onNodeWithTag("account_stay_signed_in").performClick()
        compose.onNodeWithTag("account_sign_out_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("account_sheet").assertIsDisplayed()
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.waitUntil("Sign out confirmation did not reopen", 10_000) {
            account.state.value.signOutStatus == AccountSignOutStatus.CONFIRMING
        }
        Espresso.pressBack()
        compose.onNodeWithTag("account_sign_out_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("account_sheet").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, signOut.calls) }
    }

    @Test fun activeJourneyDiscoveredAtConfirmationKeepsAccountAndShowsTruthfulError() {
        val signOut = FakeNoActiveSignOut()
        val (_, account) = showAccount(FakeAccountGateway(), signOut) {}
        compose.onNodeWithText("Account").performClick()
        compose.waitUntil("Account profile did not load", 10_000) { account.state.value.profile != null }
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.waitUntil("Sign out confirmation did not open", 10_000) {
            account.state.value.signOutStatus == AccountSignOutStatus.CONFIRMING
        }
        signOut.result = NoActiveSignOutResult.ActiveJourney
        compose.onNodeWithTag("account_confirm_sign_out").performClick()
        compose.waitUntil("Failure was not shown", 10_000) {
            account.state.value.signOutStatus == AccountSignOutStatus.FAILURE
        }
        compose.onNodeWithTag("account_sign_out_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("account_sign_out_error").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, signOut.calls) }
    }

    @Test fun activeJourneyAccountUsesActiveConfirmationAndKeepOrBackDoesNotStop() {
        val activeJourney = Journey("active-account-journey", "Ilorin",
            System.currentTimeMillis() + 3_600_000L, System.currentTimeMillis(),
            JourneyStatus.ACTIVE, null)
        val repository = FakeRepository().apply { active.value = activeJourney }
        val activeSignOut = FakeActiveSignOut(activeJourney)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(FakeDegradationStore(),
            LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig()))
        lateinit var journey: JourneyViewModel
        lateinit var account: TravellerAccountViewModel
        setContent {
            journey = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), FakeContacts(), coordinator) }
            account = remember { TravellerAccountViewModel(FakeAccountGateway(), null, activeSignOut) }
            JourneyProductRoot(journey, LocationUiState(), unavailableSms, "test@example.com",
                journey::startJourney, {}, {}, {}, {}, accountViewModel = account,
                onSignOutRequested = {
                    account.requestSignOut(!journey.uiState.value.isRestoring,
                        journey.uiState.value.activeJourney?.id)
                },
                onConfirmActiveSignOutRequested = { account.confirmActiveSignOut(true) },
                onCancelActiveSignOutRequested = account::cancelActiveSignOut)
        }
        compose.waitUntil("Active Journey did not restore", 10_000) {
            journey.uiState.value.activeJourney?.id == activeJourney.id
        }
        compose.onNodeWithText("Account").performClick()
        compose.waitUntil("Account did not load", 10_000) { account.state.value.profile != null }
        captureSliceCFrame("active-account.png")
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.onNodeWithTag("active_sign_out_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("account_sign_out_confirmation").assertDoesNotExist()
        captureSliceCFrame("active-confirmation.png")
        compose.onNodeWithTag("active_keep_monitoring").performClick()
        compose.onNodeWithTag("active_sign_out_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("account_sheet").assertIsDisplayed()
        captureSliceCFrame("keep-monitoring-account.png")
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.onNodeWithTag("active_sign_out_confirmation").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("active_sign_out_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("account_sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(activeJourney, repository.active.value)
            assertEquals(0, activeSignOut.stopCalls)
            assertEquals(0, activeSignOut.retryCalls)
        }
    }

    @Test fun stoppedResultShowsExactJourneyAndDoneAction() {
        val stopped = Journey("durable-stopped-id", "Ilorin",
            System.currentTimeMillis() + 3_600_000L, System.currentTimeMillis(),
            JourneyStatus.CANCELLED, null, 9_123L)
        var done = 0
        setContent {
            TravellerMonitoringStoppedResult(stopped, signedOut = true, error = null,
                inProgress = false, onRetry = { error("Must not retry") }, onDone = { done++ })
        }
        compose.onNodeWithTag("monitoring_stopped_result").assertIsDisplayed()
        compose.onNodeWithText("Monitoring stopped").assertIsDisplayed()
        compose.onNodeWithTag("stopped_destination").assertIsDisplayed()
        compose.onNodeWithText("Ilorin").assertIsDisplayed()
        captureSliceCFrame("monitoring-stopped-result.png")
        compose.onNodeWithTag("monitoring_stopped_done").performClick()
        compose.runOnIdle { assertEquals(1, done) }
    }

    @Test fun captureNoActiveSignOutFrames() {
        val signOut = FakeNoActiveSignOut()
        val (_, account) = showAccount(FakeAccountGateway(), signOut) {}
        compose.onNodeWithText("Account").performClick()
        compose.waitUntil("Account profile did not load", 10_000) { account.state.value.profile != null }
        captureAccountFrame("account-before-sign-out.png")
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.waitUntil("Sign out confirmation did not open", 10_000) {
            account.state.value.signOutStatus == AccountSignOutStatus.CONFIRMING
        }
        captureAccountFrame("sign-out-no-active-confirmation.png")
        compose.onNodeWithTag("account_stay_signed_in").performClick()
        compose.onNodeWithTag("account_sign_out_confirmation").assertDoesNotExist()
        captureAccountFrame("stay-signed-in-account.png")
        compose.runOnIdle { assertEquals(0, signOut.calls) }
    }

    private fun captureAccountFrame(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "traveller-account-slice-b")
        check(output.mkdirs() || output.isDirectory)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(output, name).outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun captureSliceCFrame(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "traveller-account-slice-c")
        check(output.mkdirs() || output.isDirectory)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(output, name).outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun showAccount(gateway: FakeAccountGateway,
        signOutGateway: FakeNoActiveSignOut? = null, onSignOut: () -> Unit):
        Pair<JourneyViewModel, TravellerAccountViewModel> {
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(FakeDegradationStore(),
            LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig()))
        lateinit var journey: JourneyViewModel
        lateinit var account: TravellerAccountViewModel
        setContent {
            journey = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), FakeContacts(), coordinator) }
            account = remember { TravellerAccountViewModel(gateway, signOutGateway) }
            JourneyProductRoot(journey, LocationUiState(), unavailableSms, "test@example.com",
                journey::startJourney, {}, {}, {}, {}, accountViewModel = account,
                onSignOutRequested = {
                    if (signOutGateway == null) onSignOut()
                    else account.requestNoActiveSignOut(!journey.uiState.value.isRestoring)
                },
                onConfirmSignOutRequested = { account.confirmNoActiveSignOut(true) {} },
                onCancelSignOutRequested = account::cancelNoActiveSignOut)
        }
        compose.waitUntil("Home did not restore", 10_000) { !journey.uiState.value.isRestoring }
        return journey to account
    }

    private class FakeAccountGateway : TravellerAccountGateway {
        var saves = 0
        private var profile = TravellerAccountProfile("owner-a", "Ada Owner", "ada_owner", "ada@example.com")
        override suspend fun load(): TravellerAccountProfile = profile
        override suspend fun checkHandleAvailability(ownerId: String, handle: String): Boolean = true
        override suspend fun save(ownerId: String, fullName: String, handle: String): TravellerAccountProfile {
            saves++
            return profile.copy(fullName = fullName, handle = handle).also { profile = it }
        }
    }

    private class FakeNoActiveSignOut : TravellerNoActiveSignOutGateway {
        var calls = 0
        var result: NoActiveSignOutResult = NoActiveSignOutResult.SignedOut
        override suspend fun hasNoActiveJourney(): Boolean = true
        override suspend fun signOut(expectedOwnerId: String): NoActiveSignOutResult {
            calls++
            return result
        }
    }

    private class FakeActiveSignOut(private val journey: Journey) : TravellerActiveJourneySignOutGateway {
        var stopCalls = 0
        var retryCalls = 0
        override suspend fun confirmAndSignOut(journeyId: String,
            expectedOwnerId: String): ActiveJourneySignOutResult {
            stopCalls++
            error("The safe-dismiss test must not reach remote stop")
        }
        override suspend fun restorePendingResult(): ActiveJourneySignOutResult? = null
        override suspend fun retryTerminalSignOut(journeyId: String): ActiveJourneySignOutResult {
            retryCalls++
            error("The safe-dismiss test must not retry")
        }
        override suspend fun completeResult(journeyId: String): Boolean = false
    }

    @Test fun resilienceIsOneSheetOverHomeAndCloseDoesNotChangeJourney() {
        val contacts = FakeContacts()
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(FakeDegradationStore(),
            LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig()))
        var refreshes = 0
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), contacts, coordinator) }
            JourneyProductRoot(viewModel, LocationUiState(), unavailableSms, "test@example.com",
                viewModel::startJourney, {}, {}, {}, {}, onRefreshSmsFallback = { refreshes++ })
        }
        compose.waitUntil("Home did not restore", 10_000) {
            !viewModel.uiState.value.isRestoring &&
                viewModel.uiState.value.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE
        }
        val priorContactLoads = contacts.listCalls
        compose.onNodeWithText("Resilience").performClick()
        compose.onNodeWithTag("resilience_sheet").assertIsDisplayed()
        compose.onNodeWithText("Device SMS fallback is not available in the current Alabarin configuration.")
            .assertIsDisplayed()
        compose.onNodeWithText("Destination").assertDoesNotExist()
        compose.onNodeWithText("Transport").assertDoesNotExist()
        compose.waitUntil("Opening did not refresh SMS status", 10_000) { refreshes == 1 }
        compose.waitUntil("Opening did not refresh trusted contacts", 10_000) {
            contacts.listCalls > priorContactLoads
        }
        compose.onNodeWithTag("resilience_close").performClick()
        compose.onNodeWithTag("resilience_sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(JourneyProductRoute.HOME, viewModel.uiState.value.productRoute)
            assertEquals(null, repository.active.value)
        }
    }

    @Test fun resilienceChooserStaysInSameSheetAndReturnsToStatus() {
        val contacts = FakeContacts().apply {
            items += TrustedContactSummary("relationship-1", TrustedContactSubjectKind.RELATIONSHIP,
                "Aisha", "aisha@example.com", TrustedContactStatus.ACCEPTED, null, null, 1)
        }
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(FakeDegradationStore(),
            LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig()))
        val choices = listOf(SmsSubscriptionChoice(7, "SIM 1 - MTN"),
            SmsSubscriptionChoice(8, "SIM 2 - Airtel"))
        val status = mutableStateOf(evaluateSmsFallbackStatus(true, true, true, choices, null, true))
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), contacts, coordinator) }
            JourneyProductRoot(viewModel, LocationUiState(), status.value, "test@example.com",
                viewModel::startJourney, {}, {}, {}, { id ->
                    status.value = evaluateSmsFallbackStatus(true, true, true, choices, id, true)
                })
        }
        compose.waitUntil("Contacts did not load", 10_000) {
            viewModel.uiState.value.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE
        }
        compose.onNodeWithText("Resilience").performClick()
        compose.onNodeWithTag("resilience_sheet").assertIsDisplayed()
        compose.onNodeWithText("Choose SIM").performClick()
        compose.onNodeWithTag("resilience_sheet").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ResiliencePage.CHOOSE_SIM, viewModel.uiState.value.resiliencePage) }
        compose.onNodeWithText("SIM 2 - Airtel").performClick()
        compose.onNodeWithTag("resilience_sheet").assertIsDisplayed()
        compose.onNodeWithText("SIM 2 - Airtel").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(ResiliencePage.STATUS, viewModel.uiState.value.resiliencePage)
            assertEquals(JourneyProductRoute.RESILIENCE, viewModel.uiState.value.productRoute)
        }
        compose.onNodeWithTag("resilience_close").performClick()
        compose.runOnIdle { assertEquals(JourneyProductRoute.HOME, viewModel.uiState.value.productRoute) }
    }

    @Test fun resilienceFromCheckpointAndContactsReturnToStart() {
        val contacts = FakeContacts().apply {
            items += TrustedContactSummary("relationship-1", TrustedContactSubjectKind.RELATIONSHIP,
                "Aisha", "aisha@example.com", TrustedContactStatus.ACCEPTED, null, null, 1)
        }
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(FakeDegradationStore(),
            LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig()))
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), contacts, coordinator) }
            JourneyProductRoot(viewModel, LocationUiState(), unavailableSms, "test@example.com",
                viewModel::startJourney, {}, {}, {}, {})
        }
        compose.waitUntil("Contacts did not load", 10_000) {
            viewModel.uiState.value.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE
        }
        compose.onNodeWithText("Start Journey").performClick()
        compose.runOnIdle {
            viewModel.updateDestination("Ilorin")
            viewModel.updateExpectedArrival(System.currentTimeMillis() + 7_200_000)
        }
        compose.onNodeWithTag("journey_primary_action").performClick()
        compose.waitUntil("Checkpoint did not open", 10_000) {
            viewModel.uiState.value.productRoute == JourneyProductRoute.CHECKPOINT
        }
        compose.onNodeWithText("Set up resilience").performClick()
        compose.onNodeWithTag("resilience_sheet").assertIsDisplayed()
        compose.runOnIdle { assertEquals(JourneyProductRoute.START, viewModel.uiState.value.utilityReturnRoute) }
        compose.onNodeWithTag("resilience_close").performClick()
        compose.runOnIdle { assertEquals(JourneyProductRoute.START, viewModel.uiState.value.productRoute) }

        // A contact handoff from Resilience retains the checkpoint's Start return.
        compose.onNodeWithTag("journey_primary_action").performClick()
        compose.waitUntil("Checkpoint did not reopen", 10_000) {
            viewModel.uiState.value.productRoute == JourneyProductRoute.CHECKPOINT
        }
        compose.onNodeWithText("Set up resilience").performClick()
        compose.onNodeWithTag("resilience_sheet").assertIsDisplayed()
        compose.runOnIdle { viewModel.openContacts() }
        compose.onNodeWithTag("trusted_contacts_sheet").assertIsDisplayed()
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.runOnIdle { assertEquals(JourneyProductRoute.START, viewModel.uiState.value.productRoute) }
    }

    @Test fun resiliencePermissionResultsUpdateTheOpenSheetWithoutRefreshButton() {
        val contacts = FakeContacts().apply {
            items += TrustedContactSummary("relationship-1", TrustedContactSubjectKind.RELATIONSHIP,
                "Aisha", "aisha@example.com", TrustedContactStatus.ACCEPTED, null, null, 1)
        }
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(FakeDegradationStore(),
            LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig()))
        val sim = listOf(SmsSubscriptionChoice(7, "SIM 1 - MTN"))
        val status = mutableStateOf(evaluateSmsFallbackStatus(false, true, true, sim, 7, true))
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), contacts, coordinator) }
            JourneyProductRoot(viewModel, LocationUiState(), status.value, "test@example.com",
                viewModel::startJourney, {}, {},
                onRequestSmsPermissions = {
                    status.value = evaluateSmsFallbackStatus(true, true, true, sim, 7, true)
                }, onSelectSmsSubscription = {},
                onRequestPhoneStatePermission = {
                    status.value = evaluateSmsFallbackStatus(true, true, true, sim, 7, true)
                })
        }
        compose.waitUntil("Contacts did not load", 10_000) {
            viewModel.uiState.value.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE
        }
        compose.onNodeWithText("Resilience").performClick()
        compose.onNodeWithText("Allow SMS").performClick()
        compose.onNodeWithText("Your current setup supports Alabarin's resilience path.")
            .assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.runOnIdle { status.value = evaluateSmsFallbackStatus(true, false, true, sim, 7, true) }
        compose.onNodeWithText("Phone access needed").assertIsDisplayed()
        compose.onNodeWithText("Allow access").performClick()
        compose.onNodeWithText("Your current setup supports Alabarin's resilience path.")
            .assertIsDisplayed()
    }

    @Test fun resilienceNoContactsActionUsesExistingContactsSheetAndReturnsHome() {
        val contacts = FakeContacts()
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(FakeDegradationStore(),
            LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig()))
        val status = evaluateSmsFallbackStatus(true, true, true,
            listOf(SmsSubscriptionChoice(7, "SIM 1 - MTN")), 7, true)
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), contacts, coordinator) }
            JourneyProductRoot(viewModel, LocationUiState(), status, "test@example.com",
                viewModel::startJourney, {}, {}, {}, {})
        }
        compose.waitUntil("Contacts did not load", 10_000) {
            viewModel.uiState.value.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE
        }
        compose.onNodeWithText("Resilience").performClick()
        compose.onNodeWithText("Add contact").performClick()
        compose.onNodeWithTag("resilience_sheet").assertDoesNotExist()
        compose.onNodeWithTag("trusted_contacts_sheet").assertIsDisplayed()
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.runOnIdle { assertEquals(JourneyProductRoute.HOME, viewModel.uiState.value.productRoute) }
    }

    @Test fun homeFirstStartCheckpointOpenAndReturn() {
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val degradationStore = FakeDegradationStore()
        val currentConnectivity = MutableStateFlow<CurrentJourneyConnectivity?>(null)
        val coordinator = DegradedConnectivityCoordinator(
            store = degradationStore,
            latestTelemetryReader = LatestTelemetryReader { null },
            policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig()),
        )
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember {
                JourneyViewModel(repository, JourneyLifecycle(repository),
                    JourneyServiceController(context), EmptyContacts, coordinator,
                    currentConnectivity)
            }
            JourneyProductRoot(viewModel, LocationUiState(), unavailableSms, "test@example.com",
                onStartRequested = viewModel::startJourney,
                onRetryMonitoring = {}, onOpenLocationSettings = {},
                onRequestSmsPermissions = {}, onSelectSmsSubscription = {})
        }
        compose.waitUntil("Home did not restore", 10_000) { !viewModel.uiState.value.isRestoring }
        compose.onNodeWithText("Alabarin").assertIsDisplayed()
        compose.onNodeWithText("Start Journey").performClick()
        compose.runOnIdle {
            viewModel.updateDestination("Ilorin")
            viewModel.updateExpectedArrival(System.currentTimeMillis() + 7_200_000)
        }
        compose.onNodeWithTag("journey_primary_action").performClick()
        compose.waitUntil("Checkpoint did not open", 10_000) {
            viewModel.uiState.value.productRoute == JourneyProductRoute.CHECKPOINT
        }
        compose.onNodeWithText("Set up trusted contact").performClick()
        compose.onNodeWithTag("trusted_contacts_sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(JourneyProductRoute.START, viewModel.uiState.value.utilityReturnRoute)
        }
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.onNodeWithTag("trusted_contacts_sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(JourneyProductRoute.START, viewModel.uiState.value.productRoute)
        }
        compose.onNodeWithTag("journey_primary_action").performClick()
        compose.waitUntil("Checkpoint did not reopen", 10_000) {
            viewModel.uiState.value.productRoute == JourneyProductRoute.CHECKPOINT
        }
        compose.onNodeWithText("Start anyway").performClick()
        compose.waitUntil("Journey did not become active", 10_000) {
            viewModel.uiState.value.activeJourney != null &&
                viewModel.uiState.value.productRoute == JourneyProductRoute.HOME
        }
        compose.runOnIdle { assertNotNull(repository.active.value) }
        compose.onNodeWithText("Open Journey").performClick()
        compose.onNodeWithTag("journey_sheet").assertIsDisplayed()
        compose.onNodeWithText("Monitoring needs attention").assertIsDisplayed()
        compose.onNodeWithText("Latitude").assertDoesNotExist()
        compose.onNodeWithText("Evidence fresh").assertDoesNotExist()
        degradationStore.state.value = DegradedConnectivityState(
            journeyId = repository.active.value!!.id, journeyActive = true,
            connectivityPhase = ConnectivityPhase.RECOVERING,
            fallbackBindingProvisioned = true, transportAvailable = true,
        )
        compose.onNodeWithText("Checking connection").assertIsDisplayed()
        compose.onNodeWithText("Connection restored").assertDoesNotExist()
        compose.onNodeWithTag("journey_sheet").assertIsDisplayed()

        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, true)
        compose.onNodeWithText("Connection restored").assertIsDisplayed()
        compose.onNodeWithTag("journey_sheet").assertIsDisplayed()
        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, false)
        compose.onNodeWithText("Limited connectivity").assertIsDisplayed()
        compose.onNodeWithTag("journey_sheet").assertIsDisplayed()
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.DEGRADED)
        compose.onNodeWithText("Limited connectivity").assertIsDisplayed()
        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, true)
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.RECOVERING)
        compose.onNodeWithText("Connection restored").assertIsDisplayed()
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.HEALTHY)
        compose.onNodeWithText("Connection restored").assertDoesNotExist()
        degradationStore.state.value = degradationStore.state.value!!.copy(
            connectivityPhase = ConnectivityPhase.RECOVERING)
        compose.onNodeWithText("End Journey").performClick()
        compose.onNodeWithText("Journey completed?").assertIsDisplayed()
        compose.onNodeWithText("Keep Journey running").performClick()
        compose.runOnIdle {
            assertEquals(JourneyStatus.ACTIVE, repository.active.value?.status)
            assertEquals(0, repository.completions)
        }
        compose.onNodeWithContentDescription("Return to Home").performClick()
        compose.onNodeWithTag("journey_sheet").assertDoesNotExist()
        compose.onNodeWithText("Open Journey").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(JourneyStatus.ACTIVE, repository.active.value?.status)
            assertEquals(0, repository.completions)
        }

        compose.onNodeWithText("Open Journey").performClick()
        compose.onNodeWithTag("journey_sheet").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("journey_sheet").assertDoesNotExist()
        compose.onNodeWithText("Open Journey").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(JourneyStatus.ACTIVE, repository.active.value?.status)
            assertEquals(0, repository.completions)
        }

        compose.onNodeWithText("Open Journey").performClick()
        compose.onNodeWithTag("journey_sheet").assertIsDisplayed()

        // A new ViewModel models a new process: persisted active state still opens Home first.
        currentConnectivity.value = null
        compose.runOnUiThread {
            compose.activity.setContent {
                val restarted = remember {
                    JourneyViewModel(repository, JourneyLifecycle(repository),
                        JourneyServiceController(context), EmptyContacts, coordinator,
                        currentConnectivity)
                }
                JourneyProductRoot(restarted, LocationUiState(), unavailableSms,
                    "test@example.com", restarted::startJourney, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Open Journey").assertIsDisplayed()
        compose.onNodeWithTag("journey_sheet").assertDoesNotExist()
        compose.onNodeWithText("Open Journey").performClick()
        compose.onNodeWithTag("journey_sheet").assertIsDisplayed()
        compose.onNodeWithText("Checking connection").assertIsDisplayed()
        currentConnectivity.value = CurrentJourneyConnectivity(repository.active.value!!.id, false)
        compose.onNodeWithText("Limited connectivity").assertIsDisplayed()
    }

    @Test fun trustedContactsSheetCreatesSharesRefreshesAndConfirmsRemoval() {
        val contacts = FakeContacts()
        val viewModel = showContacts(contacts)
        compose.onNodeWithText("No trusted contacts").assertIsDisplayed()
        compose.onNodeWithText("Trusted Contacts").performClick()
        compose.onNodeWithTag("trusted_contacts_sheet").assertIsDisplayed()
        compose.onNodeWithText("No trusted contacts yet").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Add trusted contact").performClick()
        compose.onNodeWithText("Invite a person using the email they will verify with Alabarin.")
            .assertIsDisplayed()
        compose.onNodeWithText("Create invitation").performClick()
        compose.runOnIdle { assertEquals(0, contacts.createCalls) }
        compose.onNodeWithTag("trusted_contact_name").performTextInput("Aisha")
        compose.onNodeWithTag("trusted_contact_email").performTextInput("aisha@example.com")
        compose.onNodeWithText("Create invitation").performClick()
        compose.waitUntil("Invitation ready did not appear", 10_000) {
            viewModel.uiState.value.trustedContactsPage == TrustedContactsPage.READY
        }
        compose.onNodeWithText("Aisha has not been added yet").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil("Pending invitation did not appear", 10_000) {
            viewModel.uiState.value.trustedContactsPage == TrustedContactsPage.LIST &&
                viewModel.uiState.value.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE
        }
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.runOnIdle {
            assertEquals(JourneyProductRoute.HOME, viewModel.uiState.value.productRoute)
        }
        compose.onNodeWithTag("trusted_contacts_sheet").assertDoesNotExist()
        compose.onNodeWithText("1 invitation pending").assertIsDisplayed()
        compose.onNodeWithText("Trusted Contacts").performClick()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.runOnIdle { contacts.items[0] = contacts.items[0].copy(
            kind = TrustedContactSubjectKind.RELATIONSHIP, status = TrustedContactStatus.ACCEPTED) }
        compose.runOnIdle { viewModel.refreshTrustedContacts() }
        compose.waitUntil("External acceptance did not update the open sheet", 10_000) {
            viewModel.uiState.value.trustedContacts.firstOrNull()?.status == TrustedContactStatus.ACCEPTED
        }
        compose.onNodeWithText("Accepted").assertIsDisplayed()
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.onNodeWithText("Aisha").assertIsDisplayed()
        compose.onNodeWithText("Trusted Contacts").performClick()
        compose.onNodeWithTag("trusted_contact_accepted_row").performClick()
        compose.onNodeWithText("Remove Aisha?").assertIsDisplayed()
        compose.onNodeWithText("Keep trusted contact").performClick()
        compose.runOnIdle { assertEquals(0, contacts.revokeCalls) }
        compose.onNodeWithTag("trusted_contact_accepted_row").performClick()
        compose.onNodeWithText("Remove trusted contact").performClick()
        compose.waitUntil("Revocation did not refresh", 10_000) {
            contacts.revokeCalls == 1 &&
                viewModel.uiState.value.trustedContacts.firstOrNull()?.status == TrustedContactStatus.REVOKED
        }
        compose.onNodeWithText("No trusted contacts yet").assertIsDisplayed()
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.onNodeWithTag("trusted_contacts_sheet").assertDoesNotExist()
        compose.onNodeWithText("No trusted contacts").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, contacts.revokeCalls) }
    }

    @Test fun invitationReadySharesTransientUrlWithoutClaimingAcceptance() {
        val url = "https://trusted.example/?token=secret"
        var shared: String? = null
        var done = 0
        var dismissed = 0
        compose.setContent {
            TrustedContactsSheet(JourneyUiState(
                trustedContactsPage = TrustedContactsPage.READY,
                invitationReadyName = "Aisha", invitationReadyEmail = "aisha@example.com",
                invitationShareUrl = url),
                onDismiss = { dismissed++ }, onAdd = {}, onCreate = { _, _ -> },
                onDone = { done++ }, onRemove = {}, onShare = { shared = it })
        }
        compose.onNodeWithText("Aisha has not been added yet").assertIsDisplayed()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Share invitation").performClick()
        compose.runOnIdle { assertEquals(url, shared) }
        val text = trustedContactShareIntent(url).getStringExtra(android.content.Intent.EXTRA_TEXT)!!
        assertEquals(true, text.contains("Alabarin trusted contact"))
        assertEquals(false, text.contains("already"))
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(1, done); assertEquals(0, dismissed) }
    }

    @Test fun acceptedAndPendingRowsExcludeInactiveHistory() {
        val rows = listOf(
            TrustedContactSummary("a", TrustedContactSubjectKind.RELATIONSHIP, "Aisha",
                "aisha@example.com", TrustedContactStatus.ACCEPTED, null, 1, 1),
            TrustedContactSummary("b", TrustedContactSubjectKind.INVITATION, "Tunde",
                "tunde@example.com", TrustedContactStatus.PENDING, 100_000, null, 2),
            TrustedContactSummary("c", TrustedContactSubjectKind.RELATIONSHIP, "Old contact",
                "old@example.com", TrustedContactStatus.REVOKED, null, 1, 3),
            TrustedContactSummary("d", TrustedContactSubjectKind.INVITATION, "Expired invite",
                "expired@example.com", TrustedContactStatus.EXPIRED, 100, null, 4),
        )
        compose.setContent {
            TrustedContactsSheet(JourneyUiState(trustedContacts = rows,
                trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE),
                onDismiss = {}, onAdd = {}, onCreate = { _, _ -> }, onDone = {},
                onRemove = {}, onShare = {})
        }
        compose.onNodeWithText("Accepted").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Old contact").assertDoesNotExist()
        compose.onNodeWithText("Expired invite").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
    }

    @Test fun failedListIsNotEmptyAndMissingShareConfigurationPreventsCreate() {
        val contacts = FakeContacts().apply { failList = true; sharingAvailable = false }
        val viewModel = showContacts(contacts)
        compose.onNodeWithText("Currently unavailable").assertIsDisplayed()
        compose.onNodeWithText("Trusted Contacts").performClick()
        compose.onNodeWithText("No trusted contacts yet").assertDoesNotExist()
        compose.runOnIdle { contacts.failList = false; viewModel.refreshTrustedContacts() }
        compose.waitUntil("Contacts did not recover", 10_000) {
            viewModel.uiState.value.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE
        }
        compose.onNodeWithText("Add trusted contact").performClick()
        compose.onNodeWithTag("trusted_contact_name").performTextInput("Aisha")
        compose.onNodeWithTag("trusted_contact_email").performTextInput("aisha@example.com")
        compose.onNodeWithText("Create invitation").performClick()
        compose.runOnIdle {
            assertEquals(0, contacts.createCalls)
            assertEquals(TrustedContactsPage.ADD, viewModel.uiState.value.trustedContactsPage)
        }
    }

    @Test fun dismissingInvitationReadyLeavesPendingInviteAndClearsTransientUrl() {
        val contacts = FakeContacts()
        val viewModel = showContacts(contacts)
        compose.onNodeWithText("Trusted Contacts").performClick()
        compose.onNodeWithText("Add trusted contact").performClick()
        compose.onNodeWithTag("trusted_contact_name").performTextInput("Aisha")
        compose.onNodeWithTag("trusted_contact_email").performTextInput("aisha@example.com")
        compose.onNodeWithText("Create invitation").performClick()
        compose.waitUntil("Invitation ready did not appear", 10_000) {
            viewModel.uiState.value.trustedContactsPage == TrustedContactsPage.READY
        }
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.runOnIdle {
            assertEquals(JourneyProductRoute.HOME, viewModel.uiState.value.productRoute)
            assertEquals(null, viewModel.uiState.value.invitationShareUrl)
            assertEquals(TrustedContactStatus.PENDING, contacts.items.single().status)
            assertEquals(0, contacts.revokeCalls)
        }
        compose.onNodeWithText("1 invitation pending").assertIsDisplayed()
    }

    @Test fun creationFinishingAfterAddSheetClosesCanStillBeSharedOnNextOpen() {
        val contacts = FakeContacts().apply { createGate = CompletableDeferred() }
        val viewModel = showContacts(contacts)
        compose.onNodeWithText("Trusted Contacts").performClick()
        compose.onNodeWithText("Add trusted contact").performClick()
        compose.onNodeWithTag("trusted_contact_name").performTextInput("Aisha")
        compose.onNodeWithTag("trusted_contact_email").performTextInput("aisha@example.com")
        compose.onNodeWithText("Create invitation").performClick()
        compose.waitUntil("Create RPC did not begin", 10_000) { contacts.createCalls == 1 }
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.runOnIdle { assertEquals(JourneyProductRoute.HOME, viewModel.uiState.value.productRoute) }
        compose.runOnIdle { contacts.createGate!!.complete(Unit) }
        compose.waitUntil("Late invitation result was lost", 10_000) {
            viewModel.uiState.value.trustedContactsPage == TrustedContactsPage.READY &&
                viewModel.uiState.value.invitationShareUrl != null
        }
        compose.onNodeWithText("Trusted Contacts").performClick()
        compose.onNodeWithText("Invitation ready").assertIsDisplayed()
        compose.onNodeWithTag("trusted_contacts_close").performClick()
        compose.runOnIdle {
            assertEquals(null, viewModel.uiState.value.invitationShareUrl)
            assertEquals(TrustedContactStatus.PENDING, contacts.items.single().status)
            assertEquals(0, contacts.revokeCalls)
        }
    }

    private fun showContacts(contacts: TrustedContactGateway): JourneyViewModel {
        val repository = FakeRepository()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DegradedConnectivityCoordinator(
            store = FakeDegradationStore(),
            latestTelemetryReader = LatestTelemetryReader { null },
            policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig()))
        lateinit var viewModel: JourneyViewModel
        setContent {
            viewModel = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), contacts, coordinator) }
            JourneyProductRoot(viewModel, LocationUiState(), unavailableSms,
                "test@example.com", viewModel::startJourney, {}, {}, {}, {})
        }
        compose.waitUntil("Home did not restore", 10_000) {
            !viewModel.uiState.value.isRestoring &&
                viewModel.uiState.value.trustedContactsAvailability != TrustedContactsAvailability.LOADING
        }
        return viewModel
    }

    private class FakeContacts : TrustedContactGateway {
        var sharingAvailable = true
        override val invitationSharingAvailable get() = sharingAvailable
        val items = mutableListOf<TrustedContactSummary>()
        var failList = false
        var listCalls = 0
        var createCalls = 0
        var revokeCalls = 0
        var createGate: CompletableDeferred<Unit>? = null
        override suspend fun list(): List<TrustedContactSummary> {
            listCalls++
            if (failList) error("Service unavailable")
            return items.toList()
        }
        override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation {
            createCalls++
            createGate?.await()
            items += TrustedContactSummary("invitation-1", TrustedContactSubjectKind.INVITATION,
                displayName, email, TrustedContactStatus.PENDING, null, null, 1)
            return CreatedTrustedContactInvitation("invitation-1", displayName, email,
                10_000, "secret", "https://trusted.example/?token=secret")
        }
        override suspend fun revoke(subjectId: String): Boolean {
            revokeCalls++
            val index = items.indexOfFirst { it.id == subjectId }
            if (index < 0) return false
            items[index] = items[index].copy(status = TrustedContactStatus.REVOKED)
            return true
        }
    }

    private fun setContent(content: @Composable () -> Unit) = compose.setContent { content() }

    private class FakeRepository : JourneyRepository {
        val active = MutableStateFlow<Journey?>(null)
        var completions = 0
        override val activeJourney: Flow<Journey?> = active
        override suspend fun createIfNoActive(journey: Journey): Boolean {
            if (active.value != null) return false
            active.value = journey
            return true
        }
        override suspend fun completeActive(completedAt: Long): Journey? {
            val journey = active.value ?: return null
            completions++
            active.value = null
            return journey.copy(status = JourneyStatus.COMPLETED, completedAt = completedAt)
        }
        override suspend fun stopMonitoringActive(journeyId: String, endedAt: Long): Journey? {
            val journey = active.value ?: return null
            if (journey.id != journeyId) return null
            active.value = null
            return journey.copy(status = JourneyStatus.CANCELLED, endedAt = endedAt)
        }
        override fun observeTelemetry(journeyId: String) = flowOf(TelemetrySummary())
        override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? = null
        override fun observeSyncState(journeyId: String) = flowOf<JourneySyncState?>(null)
        override fun observeMonitoringState(journeyId: String) = flowOf<CloudMonitoringState?>(null)
    }

    private class FakeDegradationStore : DegradedConnectivityStateStore {
        val state = MutableStateFlow<DegradedConnectivityState?>(null)
        override suspend fun updateAtomically(journeyId: String,
            transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) ->
                DegradedConnectivityReduction?): DegradedConnectivityReduction? = null
        override fun observe(journeyId: String): Flow<DegradedConnectivityState?> = state
    }

    private object EmptyContacts : TrustedContactGateway {
        override val invitationSharingAvailable = true
        override suspend fun list(): List<TrustedContactSummary> = emptyList()
        override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation =
            error("Not used")
        override suspend fun revoke(subjectId: String): Boolean = error("Not used")
    }

    private val unavailableSms = SmsFallbackStatus(false, false, false, emptyList(), null,
        false, false, "SMS fallback unavailable")
}
