package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Query
import com.journeycontinuity.app.domain.SyncPhase
import com.journeycontinuity.app.sync.LegacyAuthorizationBlock
import kotlinx.coroutines.flow.Flow

data class SyncCandidate(
    val journeyId: String,
    val changeVersion: Long,
)

@Dao
interface SyncStateDao {
    @Query("""SELECT journeyId, changeVersion, lastError FROM journey_sync_states
        WHERE permanentlyBlocked = 1 AND phase = 'ERROR' AND workRequested = 0
          AND lastError IN (:eligibleErrors)
          AND legacyAuthorizationRecoveredAtMillis IS NULL""")
    suspend fun legacyAuthorizationBlocks(eligibleErrors: List<String>): List<LegacyAuthorizationBlock>

    @Query("""UPDATE journey_sync_states SET
        legacyAuthorizationFailure = lastError,
        legacyAuthorizationFailureAtMillis = lastAttemptAt,
        legacyAuthorizationRecoveredAtMillis = :verifiedAt,
        permanentlyBlocked = 0, workRequested = 1, phase = 'PENDING',
        changeVersion = changeVersion + 1
        WHERE journeyId = :journeyId AND changeVersion = :changeVersion
          AND permanentlyBlocked = 1 AND phase = 'ERROR' AND workRequested = 0
          AND lastError = :expectedError
          AND lastError IN (:eligibleErrors) AND legacyAuthorizationRecoveredAtMillis IS NULL""")
    suspend fun reactivateLegacyAuthorization(
        journeyId: String, changeVersion: Long, expectedError: String,
        eligibleErrors: List<String>, verifiedAt: Long,
    ): Int

    @Query("""SELECT EXISTS(SELECT 1 FROM journey_sync_states s
        WHERE s.workRequested = 1 OR s.permanentlyBlocked = 1 OR
          s.highestTelemetrySequenceSynced < COALESCE(
            (SELECT MAX(sequence) FROM telemetry_observations t WHERE t.journeyId = s.journeyId), 0))""")
    suspend fun hasOutstandingWork(): Boolean

    @Query("SELECT * FROM journey_sync_states WHERE journeyId = :journeyId LIMIT 1")
    fun observe(journeyId: String): Flow<JourneySyncStateEntity?>

    @Query("SELECT * FROM journey_sync_states WHERE journeyId = :journeyId LIMIT 1")
    suspend fun get(journeyId: String): JourneySyncStateEntity?

    @Query(
        """SELECT sync.journeyId, sync.changeVersion
           FROM journey_sync_states AS sync
           INNER JOIN journeys AS journey ON journey.id = sync.journeyId
           WHERE sync.workRequested = 1 AND sync.permanentlyBlocked = 0
           ORDER BY journey.startedAt ASC
           LIMIT 1""",
    )
    suspend fun getNextCandidate(): SyncCandidate?

    @Query(
        """UPDATE journey_sync_states
           SET phase = :phase, lastAttemptAt = :attemptedAt, lastError = NULL
           WHERE journeyId = :journeyId AND permanentlyBlocked = 0""",
    )
    suspend fun markSyncing(
        journeyId: String,
        attemptedAt: Long,
        phase: SyncPhase = SyncPhase.SYNCING,
    )

    @Query(
        """UPDATE journey_sync_states
           SET highestTelemetrySequenceSynced =
               CASE WHEN highestTelemetrySequenceSynced < :sequence
                    THEN :sequence ELSE highestTelemetrySequenceSynced END
           WHERE journeyId = :journeyId""",
    )
    suspend fun advanceCheckpoint(journeyId: String, sequence: Long)

    @Query(
        """UPDATE journey_sync_states
           SET phase = :idlePhase,
               workRequested = 0,
               lastSuccessfulSyncAt = :successfulAt,
               lastError = NULL
           WHERE journeyId = :journeyId
             AND changeVersion = :expectedChangeVersion
             AND permanentlyBlocked = 0""",
    )
    suspend fun finishIfUnchanged(
        journeyId: String,
        expectedChangeVersion: Long,
        successfulAt: Long,
        idlePhase: SyncPhase = SyncPhase.IDLE,
    ): Int

    @Query(
        """UPDATE journey_sync_states
           SET phase = :errorPhase,
               lastError = :message,
               permanentlyBlocked = :permanentlyBlocked,
               workRequested = CASE WHEN :permanentlyBlocked THEN 0 ELSE 1 END
           WHERE journeyId = :journeyId""",
    )
    suspend fun markFailure(
        journeyId: String,
        message: String,
        permanentlyBlocked: Boolean,
        errorPhase: SyncPhase = SyncPhase.ERROR,
    )
}
