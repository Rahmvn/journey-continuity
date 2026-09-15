package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.journeycontinuity.app.domain.JourneyStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class JourneyDao {
    @Query("SELECT * FROM journeys WHERE activeSlot = 1 LIMIT 1")
    abstract fun observeActive(): Flow<JourneyEntity?>

    @Query("SELECT * FROM journeys WHERE activeSlot = 1 LIMIT 1")
    abstract suspend fun getActive(): JourneyEntity?

    @Insert
    protected abstract suspend fun insert(journey: JourneyEntity)

    @Query(
        """UPDATE journeys
           SET status = :completedStatus, completedAt = :completedAt, activeSlot = NULL
           WHERE id = :journeyId AND activeSlot = 1""",
    )
    protected abstract suspend fun markCompleted(
        journeyId: String,
        completedAt: Long,
        completedStatus: JourneyStatus,
    ): Int

    @Transaction
    open suspend fun insertIfNoActive(journey: JourneyEntity): Boolean {
        if (getActive() != null) return false
        insert(journey)
        return true
    }

    @Transaction
    open suspend fun completeActive(completedAt: Long): JourneyEntity? {
        val active = getActive() ?: return null
        if (markCompleted(active.id, completedAt, JourneyStatus.COMPLETED) != 1) return null
        return active.copy(
            status = JourneyStatus.COMPLETED,
            completedAt = completedAt,
            activeSlot = null,
        )
    }
}
