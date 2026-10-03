package com.journeycontinuity.app.auth

import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.sync.RemoteJourneyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TravellerActiveJourneySignOutGatewayTest {
    private val owner = "owner-a"
    private val journey = Journey("journey-j", "Ilorin", 20_000L, 1_000L,
        JourneyStatus.ACTIVE, null)
    private val endedAt = 9_123L

    @Test fun exactAcknowledgementPersistsBeforeTeardownAndLocalSignOut() = runTest {
        val f = Fixture()
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(listOf("local_active", "remote_lookup", "remote_stop", "remote_lookup",
            "local_persist", "local_read",
            "local_active", "fallback_verify", "runtime_stop", "local_signout"),
            f.events.filter { it != "auth_state" && it != "pending_save" })
        assertEquals(listOf(journey.id), f.remote.stopIds)
        assertEquals(1, f.remote.stopIds.size)
        assertTrue(f.events.indexOf("pending_save") > f.events.lastIndexOf("remote_lookup"))
        assertTrue(f.events.indexOf("pending_save") < f.events.indexOf("local_persist"))
        assertEquals(journey.stopped(), f.local.row)
        assertNull(f.local.activeJourney())
        assertEquals(owner, f.identityStore.expectedTravellerUserId())
        assertEquals(TravellerSessionState.NotAuthenticated, f.backend.state)
    }

    @Test fun ordinaryRpcFailureKeepsJourneyMonitoringAndSession() = runTest {
        val f = Fixture()
        f.remote.stopError = IllegalStateException("server rejected")
        assertEquals(ActiveJourneySignOutResult.ConnectionRequired,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.assertStillMonitoring()
        assertEquals(1, f.remote.stopIds.size)
        assertNull(f.resultStore.pendingJourneyId())
    }

    @Test fun ambiguousTimeoutUsesExactRemoteTerminalWithoutSecondStop() = runTest {
        val f = Fixture()
        f.remote.stopError = AmbiguousMonitoringStopException(IllegalStateException("timeout"))
        f.remote.commitBeforeError = true
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(1, f.remote.stopIds.size)
        assertEquals(endedAt, f.local.row.endedAt)
    }

    @Test fun ambiguousTimeoutWithoutRemoteConfirmationKeepsLocalActive() = runTest {
        val f = Fixture()
        f.remote.stopError = AmbiguousMonitoringStopException(IllegalStateException("timeout"))
        assertEquals(ActiveJourneySignOutResult.ConnectionRequired,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.assertStillMonitoring()
        assertEquals(1, f.remote.stopIds.size)
        assertNull(f.resultStore.pendingJourneyId())
    }

    @Test fun retryAfterAmbiguityReadsRemoteBeforeAnyNewStop() = runTest {
        val f = Fixture()
        f.remote.stopError = AmbiguousMonitoringStopException(IllegalStateException("timeout"))
        assertEquals(ActiveJourneySignOutResult.ConnectionRequired,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.remote.state = f.remote.cancelledState()
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(1, f.remote.stopIds.size)
    }

    @Test fun wrongJourneyIdInRemoteReadFailsClosed() = runTest {
        val f = Fixture()
        f.remote.lookupAfterStop = f.remote.cancelledState().copy(journeyId = "other")
        assertEquals(ActiveJourneySignOutResult.ConnectionRequired,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.assertStillMonitoring()
    }

    @Test fun activeRpcResponseFailsClosed() = runTest {
        val f = Fixture()
        f.remote.response = MonitoringStopAcknowledgement(JourneyStatus.ACTIVE, null)
        assertEquals(ActiveJourneySignOutResult.RemoteResultInvalid,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.assertStillMonitoring()
    }

    @Test fun completedRpcResponseIsNeverRecastAsMonitoringStopped() = runTest {
        val f = Fixture()
        f.remote.response = MonitoringStopAcknowledgement(JourneyStatus.COMPLETED, endedAt)
        assertEquals(ActiveJourneySignOutResult.RemoteResultInvalid,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.assertStillMonitoring()
    }

    @Test fun cancelledWithoutEndedAtFailsClosed() = runTest {
        val f = Fixture()
        f.remote.response = MonitoringStopAcknowledgement(JourneyStatus.CANCELLED, null)
        assertEquals(ActiveJourneySignOutResult.RemoteResultInvalid,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.assertStillMonitoring()
    }

    @Test fun mismatchedRemoteTimestampFailsClosed() = runTest {
        val f = Fixture()
        f.remote.lookupAfterStop = f.remote.cancelledState().copy(endedAt = endedAt + 1)
        assertEquals(ActiveJourneySignOutResult.RemoteResultInvalid,
            f.gateway.confirmAndSignOut(journey.id, owner))
        f.assertStillMonitoring()
    }

    @Test fun persistenceFailureAfterServerStopKeepsSessionAndRequestsReconciliation() = runTest {
        val f = Fixture()
        f.local.failPersist = true
        assertEquals(ActiveJourneySignOutResult.LocalPersistenceFailed,
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(JourneyStatus.ACTIVE, f.local.row.status)
        assertEquals(JourneyStatus.CANCELLED, f.remote.state.status)
        assertEquals(1, f.reconciliationCalls)
        assertEquals(0, f.session.calls)
        assertEquals(1, f.remote.stopIds.size)
        assertEquals(owner, f.identityStore.expectedTravellerUserId())
    }

    @Test fun persistenceFailureRetryReusesRemoteCancelledWithoutAnotherStop() = runTest {
        val f = Fixture()
        f.local.failPersist = true
        assertEquals(ActiveJourneySignOutResult.LocalPersistenceFailed,
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(JourneyStatus.ACTIVE, f.local.row.status)
        assertEquals(JourneyStatus.CANCELLED, f.remote.state.status)
        assertEquals(endedAt, f.remote.state.endedAt)
        assertEquals(0, f.session.calls)

        val stopCallsBeforeRetry = f.remote.stopIds.size
        val eventsBeforeRetry = f.events.size
        f.local.failPersist = false
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))

        val retryEvents = f.events.drop(eventsBeforeRetry)
        assertEquals(0, f.remote.stopIds.size - stopCallsBeforeRetry)
        assertFalse(retryEvents.contains("remote_stop"))
        assertTrue(retryEvents.indexOf("remote_lookup") < retryEvents.indexOf("local_persist"))
        assertTrue(retryEvents.indexOf("local_persist") < retryEvents.indexOf("runtime_stop"))
        assertTrue(retryEvents.indexOf("runtime_stop") < retryEvents.indexOf("local_signout"))
        assertEquals(journey.id, f.local.row.id)
        assertEquals(JourneyStatus.CANCELLED, f.local.row.status)
        assertEquals(endedAt, f.local.row.endedAt)
        assertNull(f.local.row.completedAt)
        assertNull(f.local.activeJourney())
        assertEquals(1, f.runtime.calls)
        assertEquals(1, f.session.calls)
        assertEquals(owner, f.identityStore.expectedTravellerUserId())
    }

    @Test fun teardownFailurePreservesDurableCancelledAndDoesNotSignOut() = runTest {
        val f = Fixture()
        f.runtime.succeeds = false
        assertEquals(ActiveJourneySignOutResult.TeardownRetryRequired(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(journey.stopped(), f.local.row)
        assertEquals(0, f.session.calls)
        f.runtime.succeeds = true
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.retryTerminalSignOut(journey.id))
        assertEquals(1, f.remote.stopIds.size)
    }

    @Test fun fallbackVerificationFailurePreservesTerminalAndBlocksSignOut() = runTest {
        val f = Fixture()
        f.local.fallbackReady = false
        assertEquals(ActiveJourneySignOutResult.TeardownRetryRequired(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(0, f.runtime.calls)
        assertEquals(0, f.session.calls)
        assertEquals(JourneyStatus.CANCELLED, f.local.row.status)
    }

    @Test fun signOutThrowWithOwnerSessionRetriesOnlyAuth() = runTest {
        val f = Fixture()
        f.session.failBeforeClear = true
        assertEquals(ActiveJourneySignOutResult.SignOutRetryRequired(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(journey.stopped(), f.local.row)
        assertEquals(TravellerSessionState.Authenticated(owner), f.backend.state)
        f.session.failBeforeClear = false
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.retryTerminalSignOut(journey.id))
        assertEquals(1, f.remote.stopIds.size)
        assertEquals(2, f.session.calls)
        assertEquals(owner, f.identityStore.expectedTravellerUserId())
    }

    @Test fun signOutThrowAfterLocalRemovalStillShowsStoppedResult() = runTest {
        val f = Fixture()
        f.session.failAfterClear = true
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(1, f.session.calls)
        assertEquals(owner, f.identityStore.expectedTravellerUserId())
        assertEquals(journey.stopped(), f.local.row)
    }

    @Test fun changedActiveJourneyCannotStopEitherJourney() = runTest {
        val f = Fixture()
        f.local.row = journey.copy(id = "new-journey")
        assertEquals(ActiveJourneySignOutResult.JourneyChanged,
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(0, f.remote.stopIds.size)
        assertEquals(0, f.session.calls)
        assertEquals("new-journey", f.local.row.id)
    }

    @Test fun ownerMismatchCannotReachRemoteStop() = runTest {
        val f = Fixture()
        assertEquals(ActiveJourneySignOutResult.AuthenticationRequired,
            f.gateway.confirmAndSignOut(journey.id, "owner-b"))
        f.assertStillMonitoring()
    }

    @Test fun concurrentConfirmationIssuesAtMostOneStop() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        f.remote.release = release
        val first = async { f.gateway.confirmAndSignOut(journey.id, owner) }
        runCurrent()
        val second = async { f.gateway.confirmAndSignOut(journey.id, owner) }
        runCurrent()
        assertEquals(1, f.remote.stopIds.size)
        release.complete(Unit)
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()), first.await())
        assertEquals(ActiveJourneySignOutResult.AuthenticationRequired, second.await())
        assertEquals(1, f.remote.stopIds.size)
    }

    @Test fun pendingResultSurvivesGatewayRecreationAndDoneConsumesOnlyAfterSignOut() = runTest {
        val f = Fixture()
        f.session.failAfterClear = true
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.newGateway().restorePendingResult())
        assertTrue(f.gateway.completeResult(journey.id))
        assertNull(f.resultStore.pendingJourneyId())
        assertNull(f.gateway.restorePendingResult())
    }

    @Test fun restartAfterTerminalPersistenceRetriesOnlyLocalAuthAndNeverStopsAgain() = runTest {
        val f = Fixture()
        f.session.failBeforeClear = true
        assertEquals(ActiveJourneySignOutResult.SignOutRetryRequired(journey.stopped()),
            f.gateway.confirmAndSignOut(journey.id, owner))
        assertFalse(f.gateway.completeResult(journey.id))
        assertEquals(journey.id, f.resultStore.pendingJourneyId())
        f.session.failBeforeClear = false
        assertEquals(ActiveJourneySignOutResult.SignedOut(journey.stopped()),
            f.newGateway().restorePendingResult())
        assertEquals(1, f.remote.stopIds.size)
        assertEquals(2, f.session.calls)
    }

    private fun Journey.stopped() = copy(status = JourneyStatus.CANCELLED,
        endedAt = this@TravellerActiveJourneySignOutGatewayTest.endedAt, completedAt = null)

    private inner class Fixture {
        val events = mutableListOf<String>()
        val gate = OwnerAdmissionGate()
        val backend = FakeBackend(events)
        val identityStore = FakeIdentityStore()
        val coordinator = TravellerIdentityCoordinator(backend, identityStore,
            admissionGate = gate)
        val local = FakeLocal(events)
        val remote = FakeRemote(events)
        val runtime = FakeRuntime(events)
        val session = FakeSession(events, backend)
        val resultStore = FakeResultStore(events)
        var reconciliationCalls = 0
        val gateway = newGateway()

        fun newGateway() = GuardedTravellerActiveJourneySignOutGateway(local, remote,
            coordinator, identityStore, backend, session, runtime, resultStore, gate,
            reconciliationRequested = { reconciliationCalls++ })

        fun assertStillMonitoring() {
            assertEquals(journey, local.row)
            assertEquals(0, session.calls)
            assertEquals(0, runtime.calls)
            assertEquals(TravellerSessionState.Authenticated(owner), backend.state)
            assertEquals(owner, identityStore.expectedTravellerUserId())
        }
    }

    private inner class FakeBackend(private val events: MutableList<String>) : TravellerAuthBackend {
        var state: TravellerSessionState = TravellerSessionState.Authenticated(owner)
        override suspend fun awaitInitialization() = Unit
        override fun sessionState(): TravellerSessionState = state.also { events += "auth_state" }
        override suspend fun signInAnonymously() = error("Not used")
    }

    private inner class FakeIdentityStore : TravellerIdentityStore {
        override fun expectedTravellerUserId(): String = owner
        override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean = false
    }

    private inner class FakeLocal(private val events: MutableList<String>) : ActiveJourneyStopLocal {
        var row: Journey = journey
        var failPersist = false
        var fallbackReady = true
        override suspend fun activeJourney(): Journey? {
            events += "local_active"
            return row.takeIf { it.status == JourneyStatus.ACTIVE }
        }
        override suspend fun journey(journeyId: String): Journey? {
            events += "local_read"
            return row.takeIf { it.id == journeyId }
        }
        override suspend fun persistStopped(journeyId: String, endedAt: Long): Journey? {
            events += "local_persist"
            if (failPersist) error("Room unavailable")
            if (row.id != journeyId || row.status != JourneyStatus.ACTIVE) return null
            row = row.copy(status = JourneyStatus.CANCELLED, endedAt = endedAt, completedAt = null)
            return row
        }
        override suspend fun fallbackStopped(journeyId: String): Boolean {
            events += "fallback_verify"
            return fallbackReady
        }
    }

    private inner class FakeRemote(private val events: MutableList<String>) : ActiveJourneyStopRemote {
        val stopIds = mutableListOf<String>()
        var state: RemoteJourneyState = RemoteJourneyState(journey.id, owner, JourneyStatus.ACTIVE,
            null, null)
        var response = MonitoringStopAcknowledgement(JourneyStatus.CANCELLED, endedAt)
        var stopError: Throwable? = null
        var commitBeforeError = false
        var lookupAfterStop: RemoteJourneyState? = null
        var release: CompletableDeferred<Unit>? = null
        fun cancelledState() = RemoteJourneyState(journey.id, owner, JourneyStatus.CANCELLED,
            endedAt, null)
        override suspend fun stop(journeyId: String, ownerId: String): MonitoringStopAcknowledgement {
            events += "remote_stop"
            stopIds += journeyId
            assertEquals(owner, ownerId)
            release?.await()
            if (commitBeforeError || stopError == null) state = cancelledState()
            stopError?.let { throw it }
            return response
        }
        override suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState? {
            events += "remote_lookup"
            assertEquals(journey.id, journeyId)
            assertEquals(owner, ownerId)
            return if (stopIds.isNotEmpty()) lookupAfterStop ?: state else state
        }
    }

    private class FakeRuntime(private val events: MutableList<String>) : ActiveJourneyRuntimeStop {
        var calls = 0
        var succeeds = true
        override suspend fun stopAndAwait(journeyId: String): Boolean {
            events += "runtime_stop"
            calls++
            return succeeds
        }
    }

    private class FakeSession(private val events: MutableList<String>,
        private val backend: FakeBackend) : AccountLocalSession {
        var calls = 0
        var failBeforeClear = false
        var failAfterClear = false
        override suspend fun signOutLocal() {
            events += "local_signout"
            calls++
            if (failBeforeClear) error("local session remained")
            backend.state = TravellerSessionState.NotAuthenticated
            if (failAfterClear) error("remote response failed")
        }
    }

    private class FakeResultStore(private val events: MutableList<String>) : MonitoringStoppedResultStore {
        var pending: String? = null
        override fun pendingJourneyId(): String? = pending
        override fun savePendingJourneyId(journeyId: String): Boolean {
            events += "pending_save"
            pending = journeyId
            return true
        }
        override fun clearPendingJourneyId(journeyId: String): Boolean {
            if (pending != journeyId) return false
            pending = null
            return true
        }
    }
}
