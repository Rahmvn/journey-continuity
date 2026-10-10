package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.JourneyStatus
import kotlinx.coroutines.flow.Flow

data class HeartbeatAllocation(
    val journeyId: String,
    val sequence: Long,
    val clientSentAt: Long,
    val latestTelemetrySequence: Long,
)

@Dao
abstract class HeartbeatDao {
    @Query("SELECT * FROM journey_heartbeat_states WHERE journeyId = :journeyId LIMIT 1")
    abstract fun observe(journeyId: String): Flow<JourneyHeartbeatStateEntity?>

    @Query("SELECT status FROM journeys WHERE id = :journeyId AND activeSlot = 1 LIMIT 1")
    protected abstract suspend fun journeyStatus(journeyId: String): JourneyStatus?

    @Query("SELECT * FROM journey_heartbeat_states WHERE journeyId = :journeyId LIMIT 1")
    protected abstract suspend fun state(journeyId: String): JourneyHeartbeatStateEntity?

    @Query(
        """UPDATE journey_heartbeat_states
           SET lastAllocatedHeartbeatSequence = lastAllocatedHeartbeatSequence + 1,
               lastHeartbeatAttemptAt = :attemptedAt,
               lastError = NULL
           WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun allocate(journeyId: String, attemptedAt: Long): Int

    @Query(
        """SELECT COALESCE(MAX(sequence), 0) FROM telemetry_observations
           WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun latestTelemetrySequence(journeyId: String): Long

    @Transaction
    open suspend fun allocateForActiveJourney(
        journeyId: String,
        attemptedAt: Long,
        minimumSpacingMillis: Long,
    ): HeartbeatAllocation? {
        if (journeyStatus(journeyId) != JourneyStatus.ACTIVE || operationallyPaused(journeyId) != 0) return null
        val before = state(journeyId) ?: return null
        if (before.lastHeartbeatAttemptAt != null &&
            attemptedAt - before.lastHeartbeatAttemptAt < minimumSpacingMillis
        ) return null
        if (allocate(journeyId, attemptedAt) != 1) return null
        val after = state(journeyId) ?: return null
        return HeartbeatAllocation(
            journeyId = journeyId,
            sequence = after.lastAllocatedHeartbeatSequence,
            clientSentAt = attemptedAt,
            latestTelemetrySequence = latestTelemetrySequence(journeyId),
        )
    }

    @Query("""SELECT (SELECT COUNT(*) FROM journey_operational_boundaries
        WHERE journeyId = :journeyId AND state = 'PENDING_AUTHORITY') +
        (SELECT COUNT(*) FROM journey_terminal_reconciliations WHERE journeyId = :journeyId
        AND explicitAction = 'STOP' AND explicitState IN ('PENDING', 'ACCOUNT_RESERVED', 'ACCOUNT_UNCERTAIN'))""")
    protected abstract suspend fun operationallyPaused(journeyId: String): Int

    @Query("""SELECT EXISTS(SELECT 1 FROM journeys j JOIN journey_heartbeat_states h ON h.journeyId=j.id
        WHERE j.id=:journeyId AND j.status='ACTIVE' AND j.activeSlot=1
        AND h.lastAllocatedHeartbeatSequence=:sequence AND h.lastHeartbeatAttemptAt=:clientSentAt
        AND NOT EXISTS (SELECT 1 FROM journey_operational_boundaries o
            WHERE o.journeyId=j.id AND o.state='PENDING_AUTHORITY')
        AND NOT EXISTS (SELECT 1 FROM journey_terminal_reconciliations r
            WHERE r.journeyId=j.id AND r.explicitAction='STOP' AND r.explicitState IN ('PENDING', 'ACCOUNT_RESERVED', 'ACCOUNT_UNCERTAIN')))""")
    abstract suspend fun canDispatchAllocation(journeyId: String, sequence: Long, clientSentAt: Long): Boolean

    @Query(
        """UPDATE journey_heartbeat_states
           SET lastSuccessfulHeartbeatAt = :succeededAt,
               monitoringPhase = :phase,
               lastCloudContactAt = :lastCloudContactAt,
               latestCloudHeartbeatSequence = :latestCloudHeartbeatSequence,
               lastError = NULL
           WHERE journeyId = :journeyId""",
    )
    abstract suspend fun recordSuccess(
        journeyId: String,
        succeededAt: Long,
        phase: CloudMonitoringPhase,
        lastCloudContactAt: Long,
        latestCloudHeartbeatSequence: Long,
    )

    @Query(
        """UPDATE journey_heartbeat_states SET lastError = :safeError
           WHERE journeyId = :journeyId""",
    )
    abstract suspend fun recordFailure(journeyId: String, safeError: String)
}
