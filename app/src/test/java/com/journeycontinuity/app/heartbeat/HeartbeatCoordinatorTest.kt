package com.journeycontinuity.app.heartbeat

import com.journeycontinuity.app.data.local.HeartbeatAllocation
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.DeviceHeartbeat
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.SyncFailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HeartbeatCoordinatorTest {
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
