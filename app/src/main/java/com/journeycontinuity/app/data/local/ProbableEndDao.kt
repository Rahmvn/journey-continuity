package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProbableEndDao {
    @Query("SELECT * FROM probable_end_detector_states WHERE journeyId = :journeyId")
    suspend fun state(journeyId: String): ProbableEndDetectorEntity?

    @Upsert
    suspend fun save(state: ProbableEndDetectorEntity)

    @Insert
    suspend fun insert(episode: ProbableEndEpisodeEntity)

    @Query("SELECT * FROM probable_end_episodes WHERE journeyId = :journeyId AND (liveSlot IS NOT NULL OR lifecycle = 'CANDIDATE' OR closedAtMillis IS NULL)")
    suspend fun unclosed(journeyId: String): List<ProbableEndEpisodeEntity>

    @Query("SELECT * FROM probable_end_episodes WHERE journeyId = :journeyId ORDER BY generation")
    suspend fun episodes(journeyId: String): List<ProbableEndEpisodeEntity>

    @Query("SELECT COALESCE(MAX(generation), -1) FROM probable_end_episodes WHERE journeyId = :journeyId")
    suspend fun highestGeneration(journeyId: String): Long

    @Query("""SELECT COALESCE(MAX(eventTime), 0) FROM telemetry_observations
        WHERE journeyId = :journeyId AND eventTime > 0 AND eventTime <= :latestPermittedTime""")
    suspend fun maximumObservedTime(journeyId: String, latestPermittedTime: Long): Long

    @Query("""UPDATE probable_end_episodes SET lifecycle = :lifecycle, liveSlot = NULL,
        closedAtMillis = :closedAt, closureReason = :reason
        WHERE journeyId = :journeyId AND (liveSlot IS NOT NULL OR lifecycle = 'CANDIDATE' OR closedAtMillis IS NULL)""")
    suspend fun closeUnclosed(journeyId: String, lifecycle: String, closedAt: Long, reason: String)
}
