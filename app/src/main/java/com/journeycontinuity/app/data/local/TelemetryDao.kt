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

    @Query("SELECT status FROM journeys WHERE id = :journeyId LIMIT 1")
    protected abstract suspend fun getJourneyStatus(journeyId: String): JourneyStatus?

    @Query(
        """SELECT COALESCE(MAX(sequence), 0) + 1
           FROM telemetry_observations WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun nextSequence(journeyId: String): Long

    @Insert
    protected abstract suspend fun insert(observation: TelemetryObservationEntity): Long

    @Transaction
    open suspend fun insertForActiveJourney(sample: TelemetrySample): TelemetryObservationEntity? {
        if (getJourneyStatus(sample.journeyId) != JourneyStatus.ACTIVE) return null
        val entity = sample.toEntity(sequence = nextSequence(sample.journeyId))
        return entity.copy(id = insert(entity))
    }
}
