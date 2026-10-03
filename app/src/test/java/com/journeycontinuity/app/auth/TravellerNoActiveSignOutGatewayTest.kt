package com.journeycontinuity.app.auth

import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.domain.TelemetrySummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravellerNoActiveSignOutGatewayTest {
    private val ownerId = "owner-a"
    private val activeJourney = Journey("journey-a", "Ilorin", 2L, 1L, JourneyStatus.ACTIVE, null)

    @Test fun localSignOutRequiresVerifiedOwnerAndRetainsInstallationOwner() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.gateway.hasNoActiveJourney())
        assertEquals(NoActiveSignOutResult.SignedOut, fixture.gateway.signOut(ownerId))
        assertEquals(1, fixture.session.calls)
        assertEquals(ownerId, fixture.store.expectedTravellerUserId())
        assertEquals(TravellerSessionState.NotAuthenticated, fixture.backend.state)
        assertNull(fixture.repository.active.value)
    }

    @Test fun activeJourneyAppearingAfterInitialReadPreventsSignOutWithoutMutation() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.gateway.hasNoActiveJourney())
        fixture.repository.active.value = activeJourney
        assertEquals(NoActiveSignOutResult.ActiveJourney, fixture.gateway.signOut(ownerId))
        assertEquals(0, fixture.session.calls)
        assertEquals(activeJourney, fixture.repository.active.value)
        assertEquals(ownerId, fixture.store.expectedTravellerUserId())
    }

    @Test fun pendingJourneyCreationHoldsSharedGateUntilActiveRead() = runTest {
        val fixture = Fixture()
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val starting = async {
            fixture.gate.withLock {
                fixture.repository.active.value = activeJourney
                inserted.complete(Unit)
                release.await()
            }
        }
        inserted.await()
        val signingOut = async { fixture.gateway.signOut(ownerId) }
        release.complete(Unit)
        starting.await()
        assertEquals(NoActiveSignOutResult.ActiveJourney, signingOut.await())
        assertEquals(0, fixture.session.calls)
    }

    @Test fun wrongOrMissingSessionCannotSignOutBoundOwner() = runTest {
        val fixture = Fixture()
        assertEquals(NoActiveSignOutResult.AuthenticationRequired, fixture.gateway.signOut("owner-b"))
        fixture.backend.state = TravellerSessionState.NotAuthenticated
        assertEquals(NoActiveSignOutResult.AuthenticationRequired, fixture.gateway.signOut(ownerId))
        assertEquals(0, fixture.session.calls)
        assertEquals(ownerId, fixture.store.expectedTravellerUserId())
    }

    @Test fun thrownSignOutWithOwnerSessionStillPresentRemainsFailure() = runTest {
        val fixture = Fixture()
        fixture.session.fail = true
        assertEquals(NoActiveSignOutResult.Failed, fixture.gateway.signOut(ownerId))
        assertEquals(ownerId, fixture.store.expectedTravellerUserId())
        assertEquals(TravellerSessionState.Authenticated(ownerId), fixture.backend.state)
        assertEquals(TravellerAuthOutcome.Authenticated(AuthenticatedTraveller(ownerId)),
            fixture.coordinator.resolve())
        assertNull(fixture.repository.active.value)
        assertEquals(1, fixture.session.calls)
    }

    @Test fun thrownSignOutAfterLocalSessionRemovalCountsAsSignedOut() = runTest {
        val fixture = Fixture()
        fixture.session.fail = true
        fixture.session.clearBeforeFailure = true
        assertEquals(NoActiveSignOutResult.SignedOut, fixture.gateway.signOut(ownerId))
        assertEquals(TravellerSessionState.NotAuthenticated, fixture.backend.state)
        assertEquals(TravellerAuthOutcome.TravellerIdentityRecoveryRequired,
            fixture.coordinator.resolve())
        assertEquals(ownerId, fixture.store.expectedTravellerUserId())
        assertNull(fixture.repository.active.value)
        assertEquals(1, fixture.session.calls)
    }

    @Test fun thrownSignOutWithInitializingAuthDefersToRootAssessment() = runTest {
        val fixture = Fixture()
        fixture.session.fail = true
        fixture.session.initializingBeforeFailure = true
        assertEquals(NoActiveSignOutResult.AuthenticationReassessmentRequired,
            fixture.gateway.signOut(ownerId))
        assertEquals(ownerId, fixture.store.expectedTravellerUserId())
        assertEquals(1, fixture.session.calls)
    }

    private inner class Fixture {
        val gate = OwnerAdmissionGate()
        val backend = FakeBackend()
        val store = FakeStore()
        val repository = FakeRepository()
        val session = FakeSession(backend)
        val coordinator = TravellerIdentityCoordinator(backend, store, admissionGate = gate)
        val gateway = GuardedTravellerNoActiveSignOutGateway(repository, coordinator, store,
            backend, gate, session)
    }

    private inner class FakeBackend : TravellerAuthBackend {
        var state: TravellerSessionState = TravellerSessionState.Authenticated(ownerId)
        override suspend fun awaitInitialization() = Unit
        override fun sessionState(): TravellerSessionState = state
        override suspend fun signInAnonymously() = error("Not used")
    }

    private inner class FakeStore : TravellerIdentityStore {
        override fun expectedTravellerUserId(): String = ownerId
        override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean = false
    }

    private class FakeSession(private val backend: FakeBackend) : AccountLocalSession {
        var calls = 0
        var fail = false
        var clearBeforeFailure = false
        var initializingBeforeFailure = false
        override suspend fun signOutLocal() {
            calls++
            if (clearBeforeFailure) backend.state = TravellerSessionState.NotAuthenticated
            if (initializingBeforeFailure) backend.state = TravellerSessionState.Initializing
            if (fail) error("network failure")
            backend.state = TravellerSessionState.NotAuthenticated
        }
    }

    private class FakeRepository : JourneyRepository {
        val active = MutableStateFlow<Journey?>(null)
        override val activeJourney: Flow<Journey?> = active
        override suspend fun createIfNoActive(journey: Journey): Boolean = error("Not used")
        override suspend fun completeActive(completedAt: Long): Journey? = error("Not used")
        override suspend fun stopMonitoringActive(journeyId: String, endedAt: Long): Journey? =
            error("Not used")
        override fun observeTelemetry(journeyId: String): Flow<TelemetrySummary> = flowOf(TelemetrySummary())
        override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? = error("Not used")
        override fun observeSyncState(journeyId: String): Flow<JourneySyncState?> = flowOf(null)
        override fun observeMonitoringState(journeyId: String): Flow<CloudMonitoringState?> = flowOf(null)
    }
}
