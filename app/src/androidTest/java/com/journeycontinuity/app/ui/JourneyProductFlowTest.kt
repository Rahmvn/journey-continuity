package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/** Exercises the real product root and JourneyLifecycle against isolated in-memory state. */
class JourneyProductFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
