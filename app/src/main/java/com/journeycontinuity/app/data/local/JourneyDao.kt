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

    @Query("SELECT * FROM journeys WHERE id = :journeyId LIMIT 1")
    abstract suspend fun getById(journeyId: String): JourneyEntity?

    @Insert
    protected abstract suspend fun insert(journey: JourneyEntity)

    @Insert
    protected abstract suspend fun insertSyncState(syncState: JourneySyncStateEntity)

    @Insert
    protected abstract suspend fun insertHeartbeatState(state: JourneyHeartbeatStateEntity)

    @Query(
        """UPDATE journey_sync_states
           SET changeVersion = changeVersion + 1,
               phase = CASE WHEN permanentlyBlocked = 1 THEN phase ELSE 'PENDING' END,
               workRequested = CASE WHEN permanentlyBlocked = 1 THEN workRequested ELSE 1 END
           WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun markSyncPending(journeyId: String)

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
        insertSyncState(JourneySyncStateEntity(journeyId = journey.id))
        insertHeartbeatState(JourneyHeartbeatStateEntity(journeyId = journey.id))
        return true
    }

    @Transaction
    open suspend fun completeActive(completedAt: Long): JourneyEntity? {
        val active = getActive() ?: return null
        if (markCompleted(active.id, completedAt, JourneyStatus.COMPLETED) != 1) return null
        markSyncPending(active.id)
        return active.copy(
            status = JourneyStatus.COMPLETED,
            completedAt = completedAt,
            activeSlot = null,
        )
    }
}
