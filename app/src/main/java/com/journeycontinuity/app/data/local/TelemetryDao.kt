package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.TelemetrySample
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TelemetryDao {
    @Query("SELECT COUNT(*) FROM telemetry_observations WHERE journeyId = :journeyId")
    abstract fun observeCount(journeyId: String): Flow<Long>

    @Query(
        """SELECT * FROM telemetry_observations
           WHERE journeyId = :journeyId
           ORDER BY sequence DESC LIMIT 1""",
    )
    abstract fun observeLatest(journeyId: String): Flow<TelemetryObservationEntity?>

    @Query(
        """SELECT * FROM telemetry_observations
           WHERE journeyId = :journeyId AND sequence > :afterSequence
           ORDER BY sequence ASC LIMIT :limit""",
    )
    abstract suspend fun getAfterSequence(
        journeyId: String,
        afterSequence: Long,
        limit: Int,
    ): List<TelemetryObservationEntity>

    @Query("SELECT status FROM journeys WHERE id = :journeyId LIMIT 1")
    protected abstract suspend fun getJourneyStatus(journeyId: String): JourneyStatus?

    @Query(
        """SELECT COALESCE(MAX(sequence), 0) + 1
           FROM telemetry_observations WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun nextSequence(journeyId: String): Long

    @Insert
    protected abstract suspend fun insert(observation: TelemetryObservationEntity): Long

    @Query(
        """UPDATE journey_sync_states
           SET changeVersion = changeVersion + 1,
               phase = CASE WHEN permanentlyBlocked = 1 THEN phase ELSE 'PENDING' END,
               workRequested = CASE WHEN permanentlyBlocked = 1 THEN workRequested ELSE 1 END
           WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun markSyncPending(journeyId: String)

    @Transaction
    open suspend fun insertForActiveJourney(sample: TelemetrySample): TelemetryObservationEntity? {
        if (getJourneyStatus(sample.journeyId) != JourneyStatus.ACTIVE) return null
        val entity = sample.toEntity(sequence = nextSequence(sample.journeyId))
        val inserted = entity.copy(id = insert(entity))
        markSyncPending(sample.journeyId)
        return inserted
    }
}
