package com.journeycontinuity.app.heartbeat

import com.journeycontinuity.app.data.local.HeartbeatAllocation
import com.journeycontinuity.app.data.local.HeartbeatDao
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.DeviceHeartbeat
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.NoOpSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncDiagnosticLogger
import kotlinx.coroutines.CancellationException

object HeartbeatConfiguration {
    const val INTERVAL_MILLIS = 60_000L
    const val MINIMUM_ATTEMPT_SPACING_MILLIS = 10_000L
}

data class HeartbeatServerState(
    val phase: CloudMonitoringPhase,
    val lastCloudContactAt: Long,
    val latestHeartbeatSequence: Long,
)

fun interface HeartbeatGateway {
    suspend fun requireAuthenticatedTraveller() = Unit
    /** Resolve outside the handoff gate; the returned check must be nonblocking and must not acquire admission. */
    suspend fun prepareDispatch(): HeartbeatDispatchCheck {
        requireAuthenticatedTraveller()
        return HeartbeatDispatchCheck { true }
    }
    suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState
}

fun interface HeartbeatDispatchCheck { fun isCurrent(): Boolean }

interface HeartbeatLocalStore {
    suspend fun allocate(
        journeyId: String,
        attemptedAt: Long,
        minimumSpacingMillis: Long,
    ): HeartbeatAllocation?

    suspend fun canDispatch(allocation: HeartbeatAllocation): Boolean = true

    suspend fun recordSuccess(
        journeyId: String,
        succeededAt: Long,
        state: HeartbeatServerState,
    )

    suspend fun recordFailure(journeyId: String, safeError: String)
}

class RoomHeartbeatLocalStore(private val dao: HeartbeatDao) : HeartbeatLocalStore {
    override suspend fun allocate(journeyId: String, attemptedAt: Long, minimumSpacingMillis: Long) =
        dao.allocateForActiveJourney(journeyId, attemptedAt, minimumSpacingMillis)

    override suspend fun canDispatch(allocation: HeartbeatAllocation) = dao.canDispatchAllocation(
        allocation.journeyId, allocation.sequence, allocation.clientSentAt)

    override suspend fun recordSuccess(
        journeyId: String,
        succeededAt: Long,
        state: HeartbeatServerState,
    ) = dao.recordSuccess(
        journeyId,
        succeededAt,
        state.phase,
        state.lastCloudContactAt,
        state.latestHeartbeatSequence,
    )

    override suspend fun recordFailure(journeyId: String, safeError: String) =
        dao.recordFailure(journeyId, safeError)
}

sealed interface HeartbeatAttemptResult {
    data object Sent : HeartbeatAttemptResult
    data object Skipped : HeartbeatAttemptResult
    data object RetryableFailure : HeartbeatAttemptResult
    data object Failed : HeartbeatAttemptResult
}

class HeartbeatCoordinator(
    private val local: HeartbeatLocalStore,
    private val remote: HeartbeatGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
    private val provisioningObserver: suspend (journeyId: String) -> Unit = {},
    private val terminalHandoffGate: JourneyTerminalHandoffGate = JourneyTerminalHandoffGate(),
) {
    suspend fun sendFreshHeartbeat(
        journeyId: String,
        batteryPercent: Int?,
        charging: Boolean?,
        connectivity: ConnectivityState,
        networkUsable: Boolean,
    ): HeartbeatAttemptResult {
        if (!networkUsable || connectivity == ConnectivityState.NONE) {
            logger.info("Heartbeat skipped: no usable network reported")
            return HeartbeatAttemptResult.Skipped
        }
        val identity = try {
            remote.prepareDispatch()
        } catch (error: CloudSyncException) {
            local.recordFailure(journeyId, error.safeMessage)
            return if (error.kind == com.journeycontinuity.app.sync.SyncFailureKind.TRANSIENT) {
                logger.info("Heartbeat skipped: traveller authentication is temporarily unavailable")
                HeartbeatAttemptResult.Skipped
            } else {
                logger.warning("Heartbeat blocked by traveller identity state")
                HeartbeatAttemptResult.Failed
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Throwable) {
            local.recordFailure(journeyId, "Fresh heartbeat is blocked by an authentication error.")
            logger.warning("Heartbeat blocked by an unexpected authentication error")
            return HeartbeatAttemptResult.Failed
        }
        val result = terminalHandoffGate.withLock {
            sendFreshHeartbeatUnderGate(journeyId, batteryPercent, charging, connectivity, identity)
        }
        // Optional provisioning keeps its established after-success semantics. It can resolve
        // identity/acquire admission, so neither it nor its callbacks may run under the handoff gate.
        if (result == HeartbeatAttemptResult.Sent) {
            try { provisioningObserver(journeyId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { logger.warning("Fallback provisioning attempt after heartbeat could not complete.") }
        }
        return result
    }

    private suspend fun sendFreshHeartbeatUnderGate(
        journeyId: String, batteryPercent: Int?, charging: Boolean?,
        connectivity: ConnectivityState, identity: HeartbeatDispatchCheck,
    ): HeartbeatAttemptResult {
        // A preparation result is not a lease. These checks perform no owner resolution or refresh.
        if (!identity.isCurrent()) return HeartbeatAttemptResult.Skipped
        val now = clock()
        val allocation = local.allocate(
            journeyId,
            now,
            HeartbeatConfiguration.MINIMUM_ATTEMPT_SPACING_MILLIS,
        ) ?: return HeartbeatAttemptResult.Skipped
        if (allocation.journeyId != journeyId || allocation.clientSentAt != now || allocation.sequence <= 0 ||
            !local.canDispatch(allocation) || !identity.isCurrent()) return HeartbeatAttemptResult.Skipped
        val heartbeat = DeviceHeartbeat(
            journeyId = allocation.journeyId,
            sequence = allocation.sequence,
            clientSentAt = allocation.clientSentAt,
            batteryPercent = batteryPercent,
            isCharging = charging,
            connectivity = connectivity,
            latestTelemetrySequence = allocation.latestTelemetrySequence,
        )
        logger.info(
            "Heartbeat object battery: percentage=${heartbeat.batteryPercent}, " +
                "charging=${heartbeat.isCharging}",
        )
        return try {
            logger.info("Starting fresh heartbeat sequence ${heartbeat.sequence}")
            val serverState = remote.recordFreshHeartbeat(heartbeat)
            local.recordSuccess(journeyId, clock(), serverState)
            logger.info("Fresh heartbeat sequence ${heartbeat.sequence} succeeded")
            HeartbeatAttemptResult.Sent
        } catch (error: CloudSyncException) {
            local.recordFailure(journeyId, error.safeMessage)
            logger.warning("Fresh heartbeat sequence ${heartbeat.sequence} failed; it will not be replayed")
            if (error.kind == com.journeycontinuity.app.sync.SyncFailureKind.TRANSIENT) {
                HeartbeatAttemptResult.RetryableFailure
            } else {
                HeartbeatAttemptResult.Failed
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Throwable) {
            local.recordFailure(journeyId, "Fresh heartbeat failed because of an unexpected client error.")
            logger.warning("Fresh heartbeat sequence ${heartbeat.sequence} failed; it will not be replayed")
            HeartbeatAttemptResult.Failed
        }
    }
}
