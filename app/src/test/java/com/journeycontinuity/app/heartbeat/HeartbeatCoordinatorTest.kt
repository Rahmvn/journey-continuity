package com.journeycontinuity.app.heartbeat

import com.journeycontinuity.app.data.local.HeartbeatAllocation
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.DeviceHeartbeat
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import com.journeycontinuity.app.auth.*
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.sync.RemoteJourneyState
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.SyncFailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatCoordinatorTest {
    @Test fun accountStopAndHeartbeatAuthenticationFinishWithSharedProductionLocks() = runBlocking {
        val f = SharedStopFixture()
        val stop = async(start = CoroutineStart.UNDISPATCHED) { f.account.confirmAndSignOut(f.id, f.owner) }
        f.stopEntered.await()
        val heartbeat = async(start = CoroutineStart.UNDISPATCHED) {
            f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true)
        }
        f.authEntered.await()
        f.releaseStop.complete(Unit)
        try {
            val completed = withTimeoutOrNull(1_000) { stop.await() to heartbeat.await() }
            assertTrue("Account acknowledgement and heartbeat authentication must not deadlock", completed != null)
            assertEquals(ActiveJourneySignOutResult.SignedOut(f.row), completed!!.first)
            assertEquals(HeartbeatAttemptResult.Failed, completed.second)
            assertEquals(JourneyStatus.CANCELLED, f.row.status)
            assertEquals(9_000L, f.row.endedAt)
            assertEquals(0, f.sends); assertTrue(f.signedOut)
            withTimeout(1_000) { f.admission.withLock { f.handoff.withLock { } } }
        } finally { stop.cancelAndJoin(); heartbeat.cancelAndJoin() }
    }

    @Test fun accountStopFinishesWhileAfterSuccessProvisioningWaitsForAdmission() = runBlocking {
        val f = SharedStopFixture()
        val sent = CompletableDeferred<Unit>(); val releaseSend = CompletableDeferred<Unit>()
        val provisionEntered = CompletableDeferred<Unit>()
        f.beforeHeartbeatResponse = { sent.complete(Unit); releaseSend.await() }
        f.onProvision = { provisionEntered.complete(Unit); f.identity.requireAuthenticatedTraveller() }
        val heartbeat = async { f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true) }
        sent.await()
        val stop = async { f.account.confirmAndSignOut(f.id, f.owner) }; f.stopEntered.await()
        releaseSend.complete(Unit); provisionEntered.await(); f.releaseStop.complete(Unit)
        try {
            withTimeout(1_000) {
                assertEquals(ActiveJourneySignOutResult.SignedOut(f.row.copy(status = JourneyStatus.CANCELLED, endedAt = 9_000)), stop.await())
                assertEquals(HeartbeatAttemptResult.Sent, heartbeat.await())
                f.admission.withLock { f.handoff.withLock { } }
            }
            assertEquals(1, f.sends); assertTrue(f.signedOut)
            assertEquals(JourneyStatus.CANCELLED, f.row.status)
        } finally { stop.cancelAndJoin(); heartbeat.cancelAndJoin() }
    }

    @Test fun preparedOwnerSessionCannotAuthorizeDispatchAfterIdentityChangedWhileWaiting() = runBlocking {
        for (state in listOf(TravellerSessionState.Authenticated("other-owner"),
            TravellerSessionState.Authenticated("shared-owner", accessTokenExpired = true),
            TravellerSessionState.NotAuthenticated, TravellerSessionState.RefreshFailure)) {
            val f = SharedStopFixture()
            val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val blocker = async { f.handoff.withLock { held.complete(Unit); release.await() } }; held.await()
            val send = async { f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true) }
            f.prepared.await(); f.session = state; release.complete(Unit)
            assertEquals(HeartbeatAttemptResult.Skipped, withTimeout(1_000) { send.await() })
            blocker.await(); assertEquals(0, f.allocations); assertEquals(0, f.sends)
        }
    }

    @Test fun privacyPauseCommittedAfterPreparationBlocksFinalAllocation() = runBlocking {
        val f = SharedStopFixture()
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pause = async { f.handoff.withLock { held.complete(Unit); release.await(); f.paused = true } }; held.await()
        val send = async { f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true) }
        f.prepared.await(); release.complete(Unit)
        assertEquals(HeartbeatAttemptResult.Skipped, withTimeout(1_000) { send.await() })
        pause.await(); assertEquals(0, f.allocations); assertEquals(0, f.sends)
    }

    @Test fun duplicatePreparedCallbacksStillSerializeAllocationAndNeverReplayAHandoff() = runBlocking {
        val f = SharedStopFixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeHeartbeatResponse = { entered.complete(Unit); release.await() }
        val first = async { f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true) }; entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true)
        }
        assertFalse(second.isCompleted); release.complete(Unit)
        withTimeout(1_000) {
            assertEquals(HeartbeatAttemptResult.Sent, first.await())
            assertEquals(HeartbeatAttemptResult.Skipped, second.await())
        }
        assertEquals(1, f.allocations); assertEquals(1, f.sends)
    }

    @Test fun cancellingAuthenticationOrDispatchReleasesBothRealLocksWithoutRecordingSuccess() = runBlocking {
        val f = SharedStopFixture()
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = async { f.admission.withLock { held.complete(Unit); release.await() } }; held.await()
        val authentication = async { f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true) }
        f.authEntered.await(); authentication.cancelAndJoin()
        release.complete(Unit); owner.await()
        assertTrue(authentication.isCancelled); assertEquals(0, f.allocations)
        val dispatch = CompletableDeferred<Unit>()
        f.beforeHeartbeatResponse = { dispatch.complete(Unit); CompletableDeferred<Unit>().await() }
        val send = async { f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true) }
        dispatch.await(); send.cancelAndJoin()
        assertTrue(send.isCancelled); assertEquals(0, f.successes); assertEquals(0, f.failures)
        withTimeout(1_000) { f.admission.withLock { f.handoff.withLock { } } }
    }

    @Test fun invalidAllocationAndStorageExceptionCannotLeakTheHandoffLock() = runBlocking {
        val f = SharedStopFixture()
        f.allowDispatch = false
        assertEquals(HeartbeatAttemptResult.Skipped,
            f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true))
        assertEquals(0, f.sends)
        f.now += HeartbeatConfiguration.MINIMUM_ATTEMPT_SPACING_MILLIS
        f.allocationFailure = true
        try { f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true); org.junit.Assert.fail("Expected storage failure") }
        catch (_: IllegalStateException) { }
        withTimeout(1_000) { f.admission.withLock { f.handoff.withLock { } } }
        assertEquals(0, f.sends)
    }

    /** Real account/identity/heartbeat coordinators and both production locks; no HTTP or Android runtime. */
    private class SharedStopFixture {
        val admission = OwnerAdmissionGate()
        val handoff = JourneyTerminalHandoffGate()
        val owner = "shared-owner"
        val id = "shared-journey"
        var session: TravellerSessionState = TravellerSessionState.Authenticated(owner)
        var row = Journey(id, "Synthetic", 20_000, 1_000, JourneyStatus.ACTIVE, null)
        var server = RemoteJourneyState(id, owner, JourneyStatus.ACTIVE, null, null)
        var sends = 0
        var allocations = 0
        var successes = 0
        var failures = 0
        var now = 20_000L
        var lastAttempt: Long? = null
        var paused = false
        var allowDispatch = true
        var allocationFailure = false
        var beforeHeartbeatResponse: suspend () -> Unit = {}
        var onProvision: suspend (String) -> Unit = {}
        var signedOut = false
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val authEntered = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<Unit>()
        val backend = object : TravellerAuthBackend {
            override suspend fun awaitInitialization() = Unit
            override fun sessionState() = session
            override suspend fun signInAnonymously() = error("No authentication writes")
        }
        val store = object : TravellerIdentityStore {
            override fun expectedTravellerUserId() = owner
            override fun persistExpectedTravellerUserIdIfAbsent(userId: String) = false
        }
        val identity = TravellerIdentityCoordinator(backend, store, admissionGate = admission)
        val local = object : ActiveJourneyStopLocal {
            override suspend fun activeJourney() = row.takeIf { it.status == JourneyStatus.ACTIVE }
            override suspend fun journey(journeyId: String) = row
            override suspend fun persistStopped(journeyId: String, endedAt: Long): Journey {
                row = row.copy(status = JourneyStatus.CANCELLED, endedAt = endedAt); return row
            }
            override suspend fun fallbackStopped(journeyId: String) = true
        }
        val remote = object : ActiveJourneyStopRemote {
            override suspend fun ownerJourneyState(journeyId: String, ownerId: String) = server
            override suspend fun stop(journeyId: String, ownerId: String): MonitoringStopAcknowledgement {
                stopEntered.complete(Unit); releaseStop.await()
                server = server.copy(status = JourneyStatus.CANCELLED, endedAt = 9_000)
                return MonitoringStopAcknowledgement(JourneyStatus.CANCELLED, 9_000)
            }
        }
        val reservation = object : AccountStopReservations {
            override suspend fun reserve(journeyId: String, ownerId: String) = AccountStopOperation("op", id, owner)
            override suspend fun markDispatch(operation: AccountStopOperation) = true
            override suspend fun uncertain(operation: AccountStopOperation) = Unit
            override suspend fun releaseUndispatched(operation: AccountStopOperation) = Unit
            override suspend fun acknowledge(operation: AccountStopOperation, endedAt: Long) = handoff.withLock {
                local.persistStopped(operation.journeyId, endedAt); true
            }
        }
        val pointer = object : MonitoringStoppedResultStore {
            var pending: String? = null
            override fun pendingJourneyId() = pending
            override fun savePendingJourneyId(journeyId: String): Boolean { pending = journeyId; return true }
            override fun clearPendingJourneyId(journeyId: String): Boolean { pending = null; return true }
        }
        val account = GuardedTravellerActiveJourneySignOutGateway(local, remote, identity, store, backend,
            AccountLocalSession { signedOut = true; session = TravellerSessionState.NotAuthenticated },
            ActiveJourneyRuntimeStop { true }, pointer, admission, reservations = reservation)
        val heartbeat = HeartbeatCoordinator(object : HeartbeatLocalStore {
            override suspend fun allocate(journeyId: String, attemptedAt: Long, minimumSpacingMillis: Long): HeartbeatAllocation? {
                if (allocationFailure) error("Injected storage failure")
                if (row.id != journeyId || row.status != JourneyStatus.ACTIVE || paused ||
                    lastAttempt?.let { attemptedAt - it < minimumSpacingMillis } == true) return null
                allocations++; lastAttempt = attemptedAt
                return HeartbeatAllocation(journeyId, allocations.toLong(), attemptedAt, 0)
            }
            override suspend fun canDispatch(allocation: HeartbeatAllocation) = allowDispatch && !paused &&
                row.status == JourneyStatus.ACTIVE && row.id == allocation.journeyId &&
                allocation.sequence == allocations.toLong() && lastAttempt == allocation.clientSentAt
            override suspend fun recordSuccess(journeyId: String, succeededAt: Long, state: HeartbeatServerState) { successes++ }
            override suspend fun recordFailure(journeyId: String, safeError: String) { failures++ }
        }, object : HeartbeatGateway {
            override suspend fun prepareDispatch(): HeartbeatDispatchCheck {
                authEntered.complete(Unit)
                return prepareHeartbeatDispatch(identity).also { prepared.complete(Unit) }
            }
            override suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState {
                sends++; beforeHeartbeatResponse()
                return HeartbeatServerState(CloudMonitoringPhase.EVIDENCE_FRESH, 9_000, heartbeat.sequence)
            }
        }, clock = { now }, terminalHandoffGate = handoff, provisioningObserver = { onProvision(it) })
    }

    @Test
    fun temporaryAuthFailureSkipsHeartbeatWithoutAllocationOrReplay() = runBlocking {
        val local = FakeLocalStore()
        val remote = FakeGateway(
            authFailure = CloudSyncException(
                SyncFailureKind.TRANSIENT,
                "Cloud authentication is temporarily unavailable while reconnecting.",
            ),
        )
        val coordinator = HeartbeatCoordinator(local, remote, { 20_000L })

        assertEquals(
            HeartbeatAttemptResult.Skipped,
            coordinator.sendFreshHeartbeat("journey", 70, false, ConnectivityState.CELLULAR, true),
        )
        assertEquals(0L, local.sequence)
        assertEquals(emptyList<DeviceHeartbeat>(), remote.received)
        assertEquals(
            "Cloud authentication is temporarily unavailable while reconnecting.",
            local.failure,
        )
    }

    @Test
    fun failedHeartbeatIsNotReplayedAndNextAttemptUsesFreshSequence() = runBlocking {
        val local = FakeLocalStore()
        val remote = FakeGateway(failFirst = true)
        var now = 20_000L
        val coordinator = HeartbeatCoordinator(local, remote, { now })

        assertEquals(
            HeartbeatAttemptResult.RetryableFailure,
            coordinator.sendFreshHeartbeat("journey", 70, false, ConnectivityState.CELLULAR, true),
        )
        now += HeartbeatConfiguration.MINIMUM_ATTEMPT_SPACING_MILLIS
        assertEquals(
            HeartbeatAttemptResult.Sent,
            coordinator.sendFreshHeartbeat("journey", 69, false, ConnectivityState.CELLULAR, true),
        )

        assertEquals(listOf(1L, 2L), remote.received.map { it.sequence })
        assertEquals(70, remote.received[0].batteryPercent)
        assertEquals(false, remote.received[0].isCharging)
        assertEquals(69, remote.received[1].batteryPercent)
        assertEquals(false, remote.received[1].isCharging)
        assertEquals(2L, local.success?.latestHeartbeatSequence)
    }

    @Test
    fun unvalidatedConnectivityDoesNotAllocateOrQueueHeartbeat() = runBlocking {
        val local = FakeLocalStore()
        val remote = FakeGateway()
        val coordinator = HeartbeatCoordinator(local, remote, { 20_000L })

        assertEquals(
            HeartbeatAttemptResult.Skipped,
            coordinator.sendFreshHeartbeat("journey", 70, false, ConnectivityState.CELLULAR, false),
        )

        assertEquals(0L, local.sequence)
        assertEquals(emptyList<DeviceHeartbeat>(), remote.received)
        assertNull(local.success)
    }

    @Test fun localCapExpiringWhileAuthenticationWaitsCannotDispatchHeartbeat() = runBlocking {
        val f = SharedStopFixture()
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var permitted = true
        val blocker = async { f.handoff.withLock { held.complete(Unit); release.await() } }
        held.await()
        val send = async {
            f.heartbeat.sendFreshHeartbeat(f.id, null, null, ConnectivityState.WIFI, true,
                permitNow = { permitted })
        }
        f.prepared.await()
        permitted = false
        release.complete(Unit)
        assertEquals(HeartbeatAttemptResult.Skipped, withTimeout(1_000) { send.await() })
        blocker.await()
        assertEquals(0, f.allocations)
        assertEquals(0, f.sends)
    }

    @Test
    fun permanentAuthenticationFailureIsNotClassifiedAsRetryableConnectivityEvidence() = runBlocking {
        val coordinator = HeartbeatCoordinator(
            FakeLocalStore(),
            FakeGateway(
                authFailure = CloudSyncException(
                    SyncFailureKind.AUTHENTICATION,
                    "Traveller sign-in is required.",
                ),
            ),
            { 20_000L },
        )

        assertEquals(
            HeartbeatAttemptResult.Failed,
            coordinator.sendFreshHeartbeat("journey", 70, false, ConnectivityState.CELLULAR, true),
        )
    }

    @Test
    fun successfulHeartbeatRetriesProvisioningWithoutChangingHeartbeatOutcome() = runBlocking {
        var provisioningAttempts = 0
        val coordinator = HeartbeatCoordinator(
            local = FakeLocalStore(),
            remote = FakeGateway(),
            clock = { 20_000L },
            provisioningObserver = {
                provisioningAttempts += 1
                error("provisioning remains unavailable")
            },
        )

        assertEquals(
            HeartbeatAttemptResult.Sent,
            coordinator.sendFreshHeartbeat("journey", 70, false, ConnectivityState.CELLULAR, true),
        )
        assertEquals(1, provisioningAttempts)
    }

    @Test
    fun operationalPauseWaitsForInFlightHeartbeatAndBlocksLaterHeartbeat() = runBlocking {
        val gate = JourneyTerminalHandoffGate()
        val local = FakeLocalStore()
        val enteredRemote = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        val remote = object : HeartbeatGateway {
            var sends = 0
            override suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState {
                sends++
                enteredRemote.complete(Unit)
                releaseRemote.await()
                return HeartbeatServerState(CloudMonitoringPhase.EVIDENCE_FRESH,
                    heartbeat.clientSentAt, heartbeat.sequence)
            }
        }
        val coordinator = HeartbeatCoordinator(local, remote, { 20_000L }, terminalHandoffGate = gate)
        val send = async { coordinator.sendFreshHeartbeat("journey", null, null,
            ConnectivityState.WIFI, true) }
        enteredRemote.await()
        val pause = async { gate.withLock { local.paused = true } }
        yield()
        assertFalse(pause.isCompleted)
        releaseRemote.complete(Unit)
        assertEquals(HeartbeatAttemptResult.Sent, send.await())
        pause.await()
        assertEquals(HeartbeatAttemptResult.Skipped,
            coordinator.sendFreshHeartbeat("journey", null, null, ConnectivityState.WIFI, true))
        assertEquals(1, remote.sends)
    }

    private class FakeLocalStore : HeartbeatLocalStore {
        var paused = false
        var sequence = 0L
        var success: HeartbeatServerState? = null
        var failure: String? = null

        override suspend fun allocate(
            journeyId: String,
            attemptedAt: Long,
            minimumSpacingMillis: Long,
        ) = if (paused) null else HeartbeatAllocation(journeyId, ++sequence, attemptedAt, 8)

        override suspend fun recordSuccess(
            journeyId: String,
            succeededAt: Long,
            state: HeartbeatServerState,
        ) {
            success = state
        }

        override suspend fun recordFailure(journeyId: String, safeError: String) {
            failure = safeError
        }
    }

    private class FakeGateway(
        private var failFirst: Boolean = false,
        private val authFailure: CloudSyncException? = null,
    ) : HeartbeatGateway {
        val received = mutableListOf<DeviceHeartbeat>()

        override suspend fun requireAuthenticatedTraveller() {
            authFailure?.let { throw it }
        }

        override suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState {
            received += heartbeat
            if (failFirst) {
                failFirst = false
                throw CloudSyncException(SyncFailureKind.TRANSIENT, "Offline")
            }
            return HeartbeatServerState(
                CloudMonitoringPhase.EVIDENCE_FRESH,
                heartbeat.clientSentAt,
                heartbeat.sequence,
            )
        }
    }
}
