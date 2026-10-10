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
        ON o.journeyId = j.id WHERE j.activeSlot = 1 AND j.status = 'ACTIVE'
        AND (o.state IS NULL OR o.state = 'MONITORING')
        AND NOT EXISTS (SELECT 1 FROM journey_terminal_reconciliations r WHERE r.journeyId = j.id
            AND r.explicitAction = 'STOP' AND r.explicitState = 'PENDING') LIMIT 1""")
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

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTerminalWorkflow(row: JourneyTerminalReconciliationEntity)

    @Query("""UPDATE journey_terminal_reconciliations SET explicitActionId = :actionId,
        explicitAction = 'COMPLETE', explicitAtMillis = :atMillis, explicitState = 'PENDING',
        effectsPending = 1, explicitDispatch = 'NEVER_DISPATCHED', revision = revision + 1
        WHERE journeyId = :journeyId AND (explicitAction IS NULL OR explicitState = 'REFUSED')""")
    protected abstract suspend fun recordCompletionIntent(journeyId: String, actionId: String, atMillis: Long)

    @Query(
        """UPDATE journey_sync_states
           SET changeVersion = changeVersion + 1,
               phase = CASE WHEN permanentlyBlocked = 1 THEN phase ELSE 'PENDING' END,
               workRequested = CASE WHEN permanentlyBlocked = 1 THEN workRequested ELSE 1 END
           WHERE journeyId = :journeyId""",
    )
    protected abstract suspend fun markSyncPending(journeyId: String)

    /** Legacy Complete cannot vacate a slot after the exact Journey enters pending authority. */
    @Query(
        """UPDATE journeys
           SET status = :completedStatus, completedAt = :completedAt,
               activeSlot = CASE WHEN :retainActiveSlot THEN 1 ELSE NULL END
           WHERE id = :journeyId AND activeSlot = 1 AND status = 'ACTIVE'
             AND (:retainActiveSlot OR NOT EXISTS (
                 SELECT 1 FROM journey_operational_boundaries o
                 WHERE o.journeyId = :journeyId AND o.state = 'PENDING_AUTHORITY'))
             AND NOT EXISTS (SELECT 1 FROM probable_end_presentations p
                 WHERE p.journeyId = :journeyId AND p.status = 'AUTO_END_CLAIMED')
             AND NOT EXISTS (SELECT 1 FROM journey_terminal_reconciliations r
                 WHERE r.journeyId = :journeyId AND (r.claimState = 'PENDING'
                   OR (r.explicitState IN ('PENDING','ACCOUNT_RESERVED','ACCOUNT_UNCERTAIN') AND r.explicitAction = 'STOP')))""",
    )
    protected abstract suspend fun markCompleted(
        journeyId: String,
        completedAt: Long,
        completedStatus: JourneyStatus,
        retainActiveSlot: Boolean,
    ): Int

    @Query(
        """UPDATE journeys
           SET status = :cancelledStatus, endedAt = :endedAt, activeSlot = NULL
           WHERE id = :journeyId AND activeSlot = 1 AND status = :activeStatus
             AND completedAt IS NULL
             AND NOT EXISTS (SELECT 1 FROM probable_end_presentations p
                 WHERE p.journeyId = :journeyId AND p.status = 'AUTO_END_CLAIMED')
             AND NOT EXISTS (SELECT 1 FROM journey_terminal_reconciliations r
                 WHERE r.journeyId = :journeyId AND (r.claimState = 'PENDING'
                   OR (r.explicitState IN ('PENDING','ACCOUNT_RESERVED','ACCOUNT_UNCERTAIN') AND r.explicitAction = 'STOP')))""",
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
        closedAtMillis = MAX(:endedAt, createdAtMillis, COALESCE(presentedAtMillis, 0),
            COALESCE(deadlineExpiredAtMillis, 0)), closureReason = 'JOURNEY_TERMINAL',
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
    open suspend fun completeActive(completedAt: Long, retainActiveSlot: Boolean = false): JourneyEntity? {
        val active = getActive() ?: return null
        if (markCompleted(active.id, completedAt, JourneyStatus.COMPLETED, retainActiveSlot) != 1) return null
        insertTerminalWorkflow(JourneyTerminalReconciliationEntity(active.id))
        recordCompletionIntent(active.id, java.util.UUID.randomUUID().toString(), completedAt)
        closeProbableEndPresentation(active.id, completedAt)
        if (retainActiveSlot) {
            deactivateFallback(active.id)
            supersedeUnsentFallback(active.id, completedAt)
        }
        markSyncPending(active.id)
        return active.copy(
            status = JourneyStatus.COMPLETED,
            completedAt = completedAt,
            activeSlot = if (retainActiveSlot) 1 else null,
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

    @Query("""UPDATE journeys SET status = :status, completedAt = :completedAt,
        endedAt = :endedAt, activeSlot = NULL WHERE id = :journeyId""")
    protected abstract suspend fun saveAuthoritativeTerminal(journeyId: String, status: JourneyStatus,
        completedAt: Long?, endedAt: Long?)

    /** Caller supplies authenticated exact-ID authority, within the provenance transaction. */
    @Transaction
    open suspend fun reconcileAuthoritativeTerminalInTransaction(journeyId: String, status: JourneyStatus,
        completedAt: Long?, endedAt: Long?, pendingExplicitCompletion: Boolean): Boolean {
        require(status != JourneyStatus.ACTIVE)
        val row = getById(journeyId) ?: return false
        if (row.status != JourneyStatus.ACTIVE &&
            !(row.status == JourneyStatus.COMPLETED && pendingExplicitCompletion)) {
            return row.status == status && row.completedAt == completedAt && row.endedAt == endedAt
        }
        require(if (status == JourneyStatus.COMPLETED) completedAt != null && completedAt > 0 && endedAt == null
            else endedAt != null && endedAt > 0 && completedAt == null)
        saveAuthoritativeTerminal(journeyId, status, completedAt, endedAt)
        val at = requireNotNull(endedAt ?: completedAt)
        closeProbableEndPresentation(journeyId, at)
        deactivateFallback(journeyId)
        supersedeUnsentFallback(journeyId, at)
        markSyncPending(journeyId)
        return true
    }
}
