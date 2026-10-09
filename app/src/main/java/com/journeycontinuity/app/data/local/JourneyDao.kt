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

    /** ACTIVE remains occupied while a privacy pause removes collection permission. */
    @Query("""SELECT j.* FROM journeys j LEFT JOIN journey_operational_boundaries o
        ON o.journeyId = j.id WHERE j.activeSlot = 1
        AND (o.state IS NULL OR o.state = 'MONITORING') LIMIT 1""")
    abstract fun observeCollectable(): Flow<JourneyEntity?>

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

    @Insert
    protected abstract suspend fun insertBoundary(state: UnresolvedBoundaryEntity)

    @Insert
    protected abstract suspend fun insertOperationalBoundary(state: OperationalBoundaryEntity)

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
           WHERE id = :journeyId AND activeSlot = 1
             AND NOT EXISTS (SELECT 1 FROM probable_end_presentations p
                 WHERE p.journeyId = :journeyId AND p.status = 'AUTO_END_CLAIMED')""",
    )
    protected abstract suspend fun markCompleted(
        journeyId: String,
        completedAt: Long,
        completedStatus: JourneyStatus,
    ): Int

    @Query(
        """UPDATE journeys
           SET status = :cancelledStatus, endedAt = :endedAt, activeSlot = NULL
           WHERE id = :journeyId AND activeSlot = 1 AND status = :activeStatus
             AND completedAt IS NULL
             AND NOT EXISTS (SELECT 1 FROM probable_end_presentations p
                 WHERE p.journeyId = :journeyId AND p.status = 'AUTO_END_CLAIMED')""",
    )
    protected abstract suspend fun markMonitoringStopped(
        journeyId: String,
        endedAt: Long,
        cancelledStatus: JourneyStatus = JourneyStatus.CANCELLED,
        activeStatus: JourneyStatus = JourneyStatus.ACTIVE,
    ): Int

    @Query("""UPDATE journeys SET status = 'AUTO_ENDED', endedAt = :endedAt,
        activeSlot = NULL WHERE id = :journeyId AND status = 'ACTIVE'
          AND activeSlot = 1 AND completedAt IS NULL AND endedAt IS NULL""")
    protected abstract suspend fun markAutomaticEnd(journeyId: String, endedAt: Long): Int

    /** Called inside the exact presentation reconciliation transaction only. */
    @Transaction
    open suspend fun autoEndExactInTransaction(journeyId: String, endedAt: Long): Boolean {
        if (markAutomaticEnd(journeyId, endedAt) != 1) return false
        deactivateFallback(journeyId)
        supersedeUnsentFallback(journeyId, endedAt)
        markSyncPending(journeyId)
        return true
    }

    @Query(
        """UPDATE journey_degradation_states
           SET journeyActive = 0, fallbackDisposition = 'INACTIVE',
               recoveryStartedAtMillis = NULL
           WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun deactivateFallback(journeyId: String)

    @Query(
        """UPDATE fallback_attempts
           SET transportState = 'SUPERSEDED', terminalAt = :endedAt
           WHERE journeyId = :journeyId
             AND transportState IN ('ALLOCATED', 'RETRY_PENDING')""",
    )
    protected abstract suspend fun supersedeUnsentFallback(journeyId: String, endedAt: Long)

    @Query("""UPDATE probable_end_presentations SET status = 'INVALIDATED', liveSlot = NULL,
        closedAtMillis = :endedAt, closureReason = 'JOURNEY_TERMINAL',
        deadlineStatus = CASE WHEN deadlineStatus IS NULL THEN NULL ELSE 'CANCELLED' END,
        revision = revision + 1
        WHERE journeyId = :journeyId AND liveSlot = 1""")
    protected abstract suspend fun closeProbableEndPresentation(journeyId: String, endedAt: Long)

    @Transaction
    open suspend fun insertIfNoActive(
        journey: JourneyEntity,
        admissionReceipt: com.journeycontinuity.app.domain.BoundaryReceipt? = null,
    ): Boolean {
        if (getActive() != null) return false
        insert(journey)
        // Freeze the admission identity before sync can observe this newly admitted Journey.
        val boundary = com.journeycontinuity.app.domain.JourneyEtaPolicy.boundary(journey.startedAt, journey.expectedArrivalAt)
        val admissionId = java.util.UUID.randomUUID().toString()
        insertBoundary(UnresolvedBoundaryEntity(journey.id, admissionId,
            journey.startedAt, journey.expectedArrivalAt, boundary,
            com.journeycontinuity.app.domain.JourneyEtaPolicy.VERSION))
        val anchor = admissionReceipt?.takeIf { it.elapsedRealtimeMillis >= 0 &&
            it.installationId.isNotBlank() }
        insertOperationalBoundary(OperationalBoundaryEntity(journey.id, admissionId,
            anchorElapsedRealtimeMillis = anchor?.elapsedRealtimeMillis,
            anchorBootCount = anchor?.bootCount,
            anchorInstallationId = anchor?.installationId))
        insertSyncState(JourneySyncStateEntity(journeyId = journey.id))
        insertHeartbeatState(JourneyHeartbeatStateEntity(journeyId = journey.id))
        return true
    }

    @Transaction
    open suspend fun completeActive(completedAt: Long): JourneyEntity? {
        val active = getActive() ?: return null
        if (markCompleted(active.id, completedAt, JourneyStatus.COMPLETED) != 1) return null
        closeProbableEndPresentation(active.id, completedAt)
        markSyncPending(active.id)
        return active.copy(
            status = JourneyStatus.COMPLETED,
            completedAt = completedAt,
            activeSlot = null,
        )
    }

    @Transaction
    open suspend fun stopMonitoringActive(journeyId: String, endedAt: Long): JourneyEntity? {
        val active = getActive() ?: return null
        if (active.id != journeyId) return null
        if (markMonitoringStopped(active.id, endedAt) != 1) return null
        closeProbableEndPresentation(active.id, endedAt)
        deactivateFallback(active.id)
        supersedeUnsentFallback(active.id, endedAt)
        markSyncPending(active.id)
        return active.copy(
            status = JourneyStatus.CANCELLED,
            endedAt = endedAt,
            activeSlot = null,
        )
    }
}
