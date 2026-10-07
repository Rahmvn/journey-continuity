package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModelStore
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.degraded.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.service.JourneyMonitoringController
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import com.journeycontinuity.app.trusted.*
import com.journeycontinuity.app.ui.destination.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyAdmissionTest {
    private fun admissionTest(block: suspend TestScope.(Rig) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig()
        val store = ViewModelStore().apply { put("journey", rig.viewModel) }
        try {
            advanceUntilIdle()
            rig.viewModel.openStart()
            rig.viewModel.updateExpectedArrival(5_000)
            block(rig)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun draft(value: StructuredDestination): DestinationDraft = when (value.kind) {
        DestinationKind.LOCALITY -> DestinationDraft.Locality("${value.localUnitName}, ${value.stateName}", value)
        DestinationKind.POINT -> DestinationDraft.ExactPoint("Frozen destination, Offa", value,
            value.boundaryDatasetVersion!!, value.providerAttribution)
    }

    @Test fun allFiveSubtypesAdmitCompleteFrozenEvidenceWithoutNetwork() = admissionTest { rig ->
        val destinations = listOf(DestinationEvidenceFixtures.locality(), DestinationEvidenceFixtures.areaCouncil(),
            DestinationEvidenceFixtures.mapPin(), DestinationEvidenceFixtures.mapPinWithProvider(),
            DestinationEvidenceFixtures.placeSearch())
        for (destination in destinations) {
            val draft = draft(destination)
            rig.viewModel.updateExpectedArrival(5_000)
            rig.viewModel.requestStructuredStart(draft, false) { error("Checkpoint must precede permission") }
            rig.viewModel.startStructuredAnyway(rig.viewModel::startJourney)
            advanceUntilIdle()
            val admitted = rig.repository.active.value!!
            assertSame(destination, admitted.structuredDestination)
            assertEquals(draft.displayLabel, admitted.destination)
            assertEquals(JourneyStatus.ACTIVE, admitted.status)
            assertNull(rig.viewModel.uiState.value.draftExpectedArrivalAt)
            // Compatibility remains available for completion, but later Start gets a new UUID.
            rig.repository.completeActive(2_000)
            advanceUntilIdle()
            rig.viewModel.openStart()
        }
        assertEquals(5, rig.repository.created.size)
        assertEquals(5, rig.repository.created.map { it.id }.distinct().size)
        assertEquals(0, rig.monitoring.starts) // Activity alone starts monitoring after observing persistence.
    }

    @Test fun checkpointAndPermissionPreserveTheSameRequestDespiteUiEdits() = admissionTest { rig ->
        val selected = draft(DestinationEvidenceFixtures.mapPin())
        rig.viewModel.requestStructuredStart(selected, false) {}
        rig.viewModel.updateExpectedArrival(9_000)
        rig.viewModel.updateDestination("Must never downgrade to this text")
        var pending: JourneyStartRequest? = null
        rig.viewModel.startStructuredAnyway { pending = it }
        assertSame(pending, rig.viewModel.pendingStartRequest)
        assertSame(selected.destination, pending!!.destination)
        assertEquals(5_000L, pending!!.expectedArrivalAt)
        rig.viewModel.startJourney(pending!!)
        rig.viewModel.startJourney(pending!!)
        rig.viewModel.startStructuredAnyway { error("Replayed checkpoint") }
        advanceUntilIdle()
        assertEquals(1, rig.repository.created.size)
        assertEquals(selected.displayLabel, rig.repository.active.value!!.destination)
    }

    @Test fun permissionDenialAndLateCallbackCreateNothingAndAllowRetry() = admissionTest { rig ->
        val selected = draft(DestinationEvidenceFixtures.mapPin())
        rig.viewModel.requestStructuredStart(selected, false) {}
        lateinit var rejected: JourneyStartRequest
        rig.viewModel.startStructuredAnyway { rejected = it }
        rig.viewModel.cancelPendingStart()
        rig.viewModel.startJourney(rejected)
        advanceUntilIdle()
        assertTrue(rig.repository.created.isEmpty())
        assertFalse(rig.viewModel.uiState.value.isActionInProgress)
        assertEquals(5_000L, rig.viewModel.uiState.value.draftExpectedArrivalAt)
        rig.viewModel.backToStart()
        rig.viewModel.requestStructuredStart(selected, false) {}
        rig.viewModel.startStructuredAnyway(rig.viewModel::startJourney)
        advanceUntilIdle()
        assertEquals(1, rig.repository.created.size)
        assertEquals(0, rig.monitoring.starts)
    }

    @Test fun checkpointBackCancelsAndReplayCannotCreateJourney() = admissionTest { rig ->
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.locality()), false) {}
        rig.viewModel.backToStart()
        rig.viewModel.startStructuredAnyway { error("Cancelled checkpoint must not continue") }
        advanceUntilIdle()
        assertTrue(rig.repository.created.isEmpty())
        assertEquals(0, rig.monitoring.starts)
    }

    @Test fun etaExpiresDuringPermissionsAndFinalAdmissionRejectsIt() = admissionTest { rig ->
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.placeSearch()), false) {}
        lateinit var pending: JourneyStartRequest
        rig.viewModel.startStructuredAnyway { pending = it }
        rig.now = 5_000
        rig.viewModel.startJourney(pending)
        advanceUntilIdle()
        assertTrue(rig.repository.created.isEmpty())
        assertEquals("Expected arrival must be in the future.", rig.viewModel.uiState.value.message)
        assertEquals(5_000L, rig.viewModel.uiState.value.draftExpectedArrivalAt)
    }

    @Test fun etaExpiresDuringCheckpointAndNoPermissionRequestIsSent() = admissionTest { rig ->
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.locality()), false) {}
        rig.now = 5_001
        rig.viewModel.startStructuredAnyway { error("Expired ETA must not enter permissions") }
        assertEquals(JourneyProductRoute.START, rig.viewModel.uiState.value.productRoute)
        assertTrue(rig.repository.created.isEmpty())
    }

    @Test fun missingDraftCannotFallBackToLegacyText() = admissionTest { rig ->
        rig.viewModel.updateDestination("Text is not structured evidence")
        rig.viewModel.requestStructuredStart(null, true) { error("Must reject absent evidence") }
        advanceUntilIdle()
        assertTrue(rig.repository.created.isEmpty())
        assertFalse(canSubmitStructuredJourneyStart(null, 5_000, 1_000))
    }

    @Test fun contradictoryDraftLabelFailsClosed() = admissionTest { rig ->
        val malformed = DestinationDraft.Locality("Different canonical context", DestinationEvidenceFixtures.locality())
        rig.viewModel.requestStructuredStart(malformed, false) { error("Must reject contradictory label") }
        assertFalse(canSubmitStructuredJourneyStart(malformed, 5_000, 1_000))
        assertTrue(rig.repository.created.isEmpty())
    }

    @Test fun localFailureKeepsInputsAndNeverStartsMonitoringOrExposesSqlDetails() = admissionTest { rig ->
        rig.repository.failure = IllegalStateException("Sensitive provider and exact coordinate SQL details")
        val selected = draft(DestinationEvidenceFixtures.mapPinWithProvider())
        rig.viewModel.requestStructuredStart(selected, false) {}
        rig.viewModel.startStructuredAnyway(rig.viewModel::startJourney)
        advanceUntilIdle()
        assertNull(rig.repository.active.value)
        assertEquals(0, rig.monitoring.starts)
        assertEquals("Could not start journey. Try again.", rig.viewModel.uiState.value.message)
        assertEquals(5_000L, rig.viewModel.uiState.value.draftExpectedArrivalAt)
        rig.repository.failure = null
        rig.viewModel.requestStructuredStart(selected, false) {}
        rig.viewModel.startStructuredAnyway(rig.viewModel::startJourney)
        advanceUntilIdle()
        assertEquals(1, rig.repository.created.size)
    }

    @Test fun doubleTapDuringSlowPersistenceAdmitsOnce() = admissionTest { rig ->
        val gate = CompletableDeferred<Unit>()
        rig.repository.gate = gate
        val selected = draft(DestinationEvidenceFixtures.mapPin())
        rig.viewModel.requestStructuredStart(selected, false) {}
        lateinit var pending: JourneyStartRequest
        rig.viewModel.startStructuredAnyway { pending = it; rig.viewModel.startJourney(it) }
        runCurrent()
        rig.viewModel.startJourney(pending)
        rig.viewModel.requestStructuredStart(selected, true) { error("Double tap must not dispatch") }
        assertNull(rig.repository.active.value)
        gate.complete(Unit)
        advanceUntilIdle()
        rig.viewModel.startJourney(pending)
        assertEquals(1, rig.repository.created.size)
    }

    @Test fun requestStringIsRedacted() {
        val request = draft(DestinationEvidenceFixtures.placeSearch()).toJourneyStartRequest(5_000)
        assertEquals("JourneyStartRequest(redacted)", request.toString())
    }

    @Test fun readyContactsAndSmsSkipOnlyAdvisoryCheckpoint() = admissionTest { rig ->
        rig.contacts += TrustedContactSummary("contact", TrustedContactSubjectKind.RELATIONSHIP,
            "Contact", "contact@example.com", TrustedContactStatus.ACCEPTED, null, null, 1)
        rig.viewModel.refreshTrustedContacts()
        advanceUntilIdle()
        var calls = 0
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.locality()), true) {
            calls++
            rig.viewModel.startJourney(it)
        }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(1, rig.repository.created.size)
    }

    @Test fun replacingSelectedContextCannotBorrowAnEarlierFrozenProof() = admissionTest { rig ->
        val catalogue = catalogue()
        val picker = DestinationPickerViewModel(catalogue,
            DestinationSearchGateway { error("Start must not search or verify") }, { true })
        picker.selectRegion("ng-kwara"); picker.selectUnit("ng-kwara-offa")
        picker.acceptDraft(draft(DestinationEvidenceFixtures.mapPin()))
        assertNotNull(picker.state.value.selectedDraft)
        picker.selectRegion("ng-fct")
        assertNull(picker.state.value.selectedDraft)
        rig.viewModel.requestStructuredStart(picker.state.value.selectedDraft, true) { error("Old proof reused") }
        assertTrue(rig.repository.created.isEmpty())
    }

    @Test fun finalLifecycleCatalogueValidationRejectsCanonicalContradiction() = admissionTest { rig ->
        val lifecycle = JourneyLifecycle(rig.repository, JourneyClock { rig.now }, catalogue = catalogue())
        val request = JourneyStartRequest("Frozen pin, Offa",
            DestinationEvidenceFixtures.mapPin().copy(localUnitName = "Incorrect canonical name"), 5_000)
        assertEquals(StartJourneyResult.InvalidStructuredDestination, lifecycle.start(request))
        assertTrue(rig.repository.created.isEmpty())
    }

    @Test fun monitoringStartupFailureDoesNotUndoAdmissionOrPermitCallbackReplay() = admissionTest { rig ->
        lateinit var pending: JourneyStartRequest
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.mapPin()), false) {}
        rig.viewModel.startStructuredAnyway { pending = it; rig.viewModel.startJourney(it) }
        advanceUntilIdle()
        val admitted = rig.repository.active.value!!
        rig.monitoring.failure = IllegalStateException("Service startup unavailable")
        assertTrue(runCatching { rig.monitoring.start() }.isFailure)
        rig.viewModel.startJourney(pending)
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.locality()), true) {
            error("Must not replace an admitted Journey")
        }
        advanceUntilIdle()
        assertEquals(admitted, rig.repository.active.value)
        assertEquals(1, rig.repository.created.size)
    }

    @Test fun concurrentActiveJourneyCancelsPendingPermissionsWithoutLeavingUiBusy() = admissionTest { rig ->
        lateinit var pending: JourneyStartRequest
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.mapPin()), false) {}
        rig.viewModel.startStructuredAnyway { pending = it }
        val concurrent = Journey("concurrent", "Legacy compatible", 5_000, 1_000, JourneyStatus.ACTIVE, null)
        rig.repository.createIfNoActive(concurrent)
        advanceUntilIdle()
        rig.viewModel.startJourney(pending)
        assertEquals(concurrent, rig.repository.active.value)
        assertNull(rig.viewModel.pendingStartRequest)
        assertFalse(rig.viewModel.uiState.value.isActionInProgress)
        assertEquals(1, rig.repository.created.size)
    }

    @Test fun cancellingPendingStartDoesNotUnlockAnUnrelatedCompletionAction() = admissionTest { rig ->
        rig.viewModel.requestStructuredStart(draft(DestinationEvidenceFixtures.locality()), false) {}
        rig.viewModel.startStructuredAnyway(rig.viewModel::startJourney)
        advanceUntilIdle()
        rig.viewModel.endJourney()
        assertTrue(rig.viewModel.uiState.value.isActionInProgress)
        rig.viewModel.openContacts()
        assertTrue(rig.viewModel.uiState.value.isActionInProgress)
        advanceUntilIdle()
        assertFalse(rig.viewModel.uiState.value.isActionInProgress)
        assertEquals(1, rig.repository.created.size)
    }

    private fun catalogue(): AdminCatalogue = AdminCatalogue.parse(listOf(
        java.io.File("src/main/assets/catalogue/ng_admin_v2.json"),
        java.io.File("app/src/main/assets/catalogue/ng_admin_v2.json"),
    ).first { it.isFile }.readBytes())

    private class Rig {
        var now = 1_000L
        var nextId = 0
        val repository = Repository()
        val contacts = mutableListOf<TrustedContactSummary>()
        val monitoring = FakeMonitoring()
        val viewModel = JourneyViewModel(repository,
            JourneyLifecycle(repository, JourneyClock { now }, JourneyIdGenerator { "journey-${++nextId}" }),
            monitoring, object : TrustedContactGateway {
                override val invitationSharingAvailable = false
                override suspend fun list() = contacts.toList()
                override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation = error("Unused")
                override suspend fun revoke(subjectId: String) = false
            }, DegradedConnectivityCoordinator(object : DegradedConnectivityStateStore {
                override fun observe(journeyId: String) = flowOf<DegradedConnectivityState?>(null)
                override suspend fun updateAtomically(journeyId: String,
                    transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?) = null
            }, LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig())), MutableStateFlow<CurrentJourneyConnectivity?>(null))
    }

    private class Repository : JourneyRepository {
        val active = MutableStateFlow<Journey?>(null)
        val created = mutableListOf<Journey>()
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override val activeJourney = active
        override suspend fun createIfNoActive(journey: Journey): Boolean {
            gate?.await()
            failure?.let { throw it }
            if (active.value != null) return false
            created += journey
            active.value = journey
            return true
        }
        override suspend fun completeActive(completedAt: Long) = active.value?.also { active.value = null }
        override suspend fun stopMonitoringActive(journeyId: String, endedAt: Long) = active.value?.also { active.value = null }
        override fun observeTelemetry(journeyId: String) = flowOf(TelemetrySummary())
        override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? = null
        override fun observeSyncState(journeyId: String) = flowOf<JourneySyncState?>(null)
        override fun observeMonitoringState(journeyId: String) = flowOf<CloudMonitoringState?>(null)
    }

    private class FakeMonitoring : JourneyMonitoringController {
        var starts = 0
        var failure: Exception? = null
        override fun start() { starts++; failure?.let { throw it } }
        override fun stop() {}
    }
}
