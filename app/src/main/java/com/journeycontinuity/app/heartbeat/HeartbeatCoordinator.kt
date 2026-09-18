package com.journeycontinuity.app.heartbeat

import com.journeycontinuity.app.data.local.HeartbeatAllocation
import com.journeycontinuity.app.data.local.HeartbeatDao
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.DeviceHeartbeat
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.NoOpSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncDiagnosticLogger

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
    suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState
}

interface HeartbeatLocalStore {
    suspend fun allocate(
        journeyId: String,
        attemptedAt: Long,
        minimumSpacingMillis: Long,
    ): HeartbeatAllocation?

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
    data object Failed : HeartbeatAttemptResult
}

class HeartbeatCoordinator(
    private val local: HeartbeatLocalStore,
    private val remote: HeartbeatGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
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
        try {
            remote.requireAuthenticatedTraveller()
        } catch (error: CloudSyncException) {
            local.recordFailure(journeyId, error.safeMessage)
            return if (error.kind == com.journeycontinuity.app.sync.SyncFailureKind.TRANSIENT) {
                logger.info("Heartbeat skipped: traveller authentication is temporarily unavailable")
                HeartbeatAttemptResult.Skipped
            } else {
                logger.warning("Heartbeat blocked by traveller identity state")
                HeartbeatAttemptResult.Failed
            }
        } catch (_: Throwable) {
            local.recordFailure(journeyId, "Fresh heartbeat is blocked by an authentication error.")
            logger.warning("Heartbeat blocked by an unexpected authentication error")
            return HeartbeatAttemptResult.Failed
        }
        val now = clock()
        val allocation = local.allocate(
            journeyId,
            now,
            HeartbeatConfiguration.MINIMUM_ATTEMPT_SPACING_MILLIS,
        ) ?: return HeartbeatAttemptResult.Skipped
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
            HeartbeatAttemptResult.Failed
        } catch (_: Throwable) {
            local.recordFailure(journeyId, "Fresh heartbeat failed because of an unexpected client error.")
            logger.warning("Fresh heartbeat sequence ${heartbeat.sequence} failed; it will not be replayed")
            HeartbeatAttemptResult.Failed
        }
    }
}
