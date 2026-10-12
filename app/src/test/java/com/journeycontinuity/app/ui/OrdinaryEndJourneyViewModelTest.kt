package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModelStore
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.degraded.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import com.journeycontinuity.app.service.JourneyMonitoringController
import com.journeycontinuity.app.trusted.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrdinaryEndJourneyViewModelTest {
    private val journey = Journey("ordinary-end", "Ilorin", 9_000, 1_000,
        JourneyStatus.ACTIVE, null)

    private fun scenario(acceptStop: Boolean = true,
        block: suspend TestScope.(FakeRepository, JourneyViewModel, () -> Int) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeRepository(journey)
        var stopCalls = 0
        val vm = newViewModel(repo) { stopCalls++; acceptStop }
        val store = ViewModelStore().apply { put("end", vm) }
        try {
            runCurrent()
            block(repo, vm) { stopCalls }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun newViewModel(repo: FakeRepository,
        requestStop: suspend (String) -> Boolean): JourneyViewModel =
        JourneyViewModel(repo, JourneyLifecycle(repo, JourneyClock { 2_000 }),
            object : JourneyMonitoringController {
                override fun start() = Unit
                override fun stop() = Unit
            }, object : TrustedContactGateway {
                override val invitationSharingAvailable = false
                override suspend fun list() = emptyList<TrustedContactSummary>()
                override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation =
                    error("Unused")
                override suspend fun revoke(subjectId: String) = false
            }, DegradedConnectivityCoordinator(object : DegradedConnectivityStateStore {
                override fun observe(journeyId: String) = flowOf<DegradedConnectivityState?>(null)
                override suspend fun updateAtomically(journeyId: String,
                    transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?) = null
            }, LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(
                DegradedConnectivityLabConfiguration.policyConfig())),
            MutableStateFlow<CurrentJourneyConnectivity?>(null),
            requestOrdinaryStop = requestStop)

    @Test fun choiceBackAndConfirmationCancellationKeepExactJourneyActive() = scenario { repo, vm, calls ->
        vm.openActive()
        vm.openEndJourney(journey.id)
        assertEquals(JourneyProductRoute.END, vm.uiState.value.productRoute)
        vm.returnToActiveFromEnd()
        assertEquals(JourneyProductRoute.ACTIVE, vm.uiState.value.productRoute)
        assertEquals(journey, repo.active.value)
        assertEquals(0, calls())
    }

    @Test fun completedResultIsLocalExactAndOnlyAfterSuccessfulWrite() = scenario { repo, vm, _ ->
        vm.openEndJourney(journey.id)
        vm.endJourney(journey.id)
        vm.endJourney(journey.id)
        runCurrent()
        assertEquals(1, repo.completions)
        assertEquals(JourneyProductRoute.END_RESULT, vm.uiState.value.productRoute)
        assertEquals(JourneyStatus.COMPLETED, vm.uiState.value.endResult?.status)
        assertEquals(journey.id, vm.uiState.value.endResult?.id)
        vm.doneWithEndResult()
        assertEquals(JourneyProductRoute.HOME, vm.uiState.value.productRoute)
    }

    @Test fun stopWaitsForAcknowledgementAndTeardownAndIgnoresDuplicateTaps() = scenario { repo, vm, calls ->
        vm.openEndJourney(journey.id)
        vm.stopJourney(journey.id)
        vm.stopJourney(journey.id)
        runCurrent()
        assertEquals(1, calls())
        assertTrue(vm.uiState.value.endStopPending)
        assertEquals(JourneyProductRoute.END, vm.uiState.value.productRoute)
        val cancelled = journey.copy(status = JourneyStatus.CANCELLED, endedAt = 3_000)
        repo.byId.value = cancelled
        repo.active.value = null
        runCurrent()
        assertEquals(JourneyProductRoute.END, vm.uiState.value.productRoute)
        repo.acknowledged.value = cancelled
        runCurrent()
        assertEquals(JourneyProductRoute.END_RESULT, vm.uiState.value.productRoute)
        assertEquals(cancelled, vm.uiState.value.endResult)
    }

    @Test fun unavailableStopRetainsActiveJourneyAndExactRoute() = scenario { repo, vm, calls ->
        vm.openEndJourney(journey.id)
        vm.stopJourney("different-journey")
        runCurrent()
        assertEquals(0, calls())
        assertEquals(journey, repo.active.value)
        assertEquals(JourneyProductRoute.END, vm.uiState.value.productRoute)
    }

    @Test fun rejectedStopShowsErrorWithoutReleasingSlot() = scenario(acceptStop = false) { repo, vm, calls ->
        vm.openEndJourney(journey.id)
        vm.stopJourney(journey.id)
        runCurrent()
        assertEquals(1, calls())
        assertEquals(journey, repo.active.value)
        assertFalse(vm.uiState.value.endStopPending)
        assertEquals(JourneyProductRoute.END, vm.uiState.value.productRoute)
        assertNotNull(vm.uiState.value.endError)
    }

    @Test fun unrelatedTerminalCannotBecomeStoppedResult() = scenario { repo, vm, _ ->
        vm.openEndJourney(journey.id)
        vm.stopJourney(journey.id)
        runCurrent()
        repo.byId.value = journey.copy(status = JourneyStatus.AUTO_ENDED, endedAt = 3_000)
        repo.active.value = null
        runCurrent()
        assertEquals(JourneyProductRoute.HOME, vm.uiState.value.productRoute)
        assertNull(vm.uiState.value.endResult)
    }

    @Test fun processRecreationDoesNotRestoreAnUnprovedResult() = scenario { repo, vm, _ ->
        vm.openEndJourney(journey.id)
        vm.stopJourney(journey.id)
        runCurrent()
        assertTrue(vm.uiState.value.endStopPending)
        val restarted = newViewModel(repo) { error("A restored screen must not resubmit Stop") }
        val restoredStore = ViewModelStore().apply { put("restored", restarted) }
        try {
            runCurrent()
            assertEquals(JourneyProductRoute.HOME, restarted.uiState.value.productRoute)
            assertNull(restarted.uiState.value.endResult)
            assertEquals(journey, restarted.uiState.value.activeJourney)
        } finally { restoredStore.clear() }
    }

    @Test fun ordinaryStopIsUnavailableOutsideItsExactEndRoute() = scenario { repo, vm, calls ->
        vm.openActive()
        vm.stopJourney(journey.id)
        vm.openEndJourney("unrelated")
        runCurrent()
        assertEquals(0, calls())
        assertEquals(JourneyProductRoute.ACTIVE, vm.uiState.value.productRoute)
        assertEquals(journey, repo.active.value)
    }

    private class FakeRepository(initial: Journey) : JourneyRepository {
        val active = MutableStateFlow<Journey?>(initial)
        val byId = MutableStateFlow<Journey?>(initial)
        val acknowledged = MutableStateFlow<Journey?>(null)
        var completions = 0
        override val activeJourney = active
        override fun observeJourney(journeyId: String) = byId.filter { it?.id == journeyId }
        override fun observeAcknowledgedStop(journeyId: String) = acknowledged.filter {
            it == null || it.id == journeyId
        }
        override suspend fun createIfNoActive(journey: Journey) = false
        override suspend fun completeActive(completedAt: Long): Journey? = null
        override suspend fun completeExact(journeyId: String, completedAt: Long): Journey? {
            if (active.value?.id != journeyId) return null
            completions++
            val completed = active.value!!.copy(status = JourneyStatus.COMPLETED,
                completedAt = completedAt, endedAt = completedAt)
            byId.value = completed
            active.value = null
            return completed
        }
        override suspend fun stopMonitoringActive(journeyId: String, endedAt: Long): Journey? = null
        override fun observeTelemetry(journeyId: String) = flowOf(TelemetrySummary())
        override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? = null
        override fun observeSyncState(journeyId: String) = flowOf<JourneySyncState?>(null)
        override fun observeMonitoringState(journeyId: String) = flowOf<CloudMonitoringState?>(null)
    }
}
