package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OperationalBoundaryDao {
    @Insert
    suspend fun insert(row: OperationalBoundaryEntity)

    @Query("SELECT * FROM journey_operational_boundaries WHERE journeyId = :journeyId")
    suspend fun get(journeyId: String): OperationalBoundaryEntity?

    @Query("""UPDATE journey_operational_boundaries SET state = 'PENDING_AUTHORITY',
        pauseReason = :reason, lastAssessment = :assessment, pausedAtElapsedRealtimeMillis = :elapsed,
        revision = revision + 1 WHERE journeyId = :journeyId AND admissionId = :admissionId
        AND state = 'MONITORING' AND revision = :revision""")
    suspend fun pauseExact(journeyId: String, admissionId: String, revision: Long,
                           reason: String, assessment: String, elapsed: Long?): Int

    @Query("""UPDATE journey_operational_boundaries SET lastAssessment = :assessment,
        revision = revision + 1 WHERE journeyId = :journeyId AND admissionId = :admissionId
        AND state = 'MONITORING' AND revision = :revision AND
        (lastAssessment IS NULL OR lastAssessment != :assessment)""")
    suspend fun assessExact(journeyId: String, admissionId: String, revision: Long,
                            assessment: String): Int

    @Query("""UPDATE journey_operational_boundaries SET lastAssessment = 'BOUNDARY_REACHED_WITH_PROOF',
        revision = revision + 1 WHERE journeyId = :journeyId AND admissionId = :admissionId
        AND state = 'PENDING_AUTHORITY' AND revision = :revision
        AND (lastAssessment IS NULL OR lastAssessment != 'BOUNDARY_REACHED_WITH_PROOF')""")
    suspend fun recordAuthoritativeProof(journeyId: String, admissionId: String, revision: Long): Int

    @Query("""UPDATE journey_degradation_states SET journeyActive = 0,
        fallbackDisposition = 'INACTIVE', recoveryStartedAtMillis = NULL
        WHERE journeyId = :journeyId""")
    suspend fun deactivateDegradation(journeyId: String): Int

    @Query("""UPDATE probable_end_presentations SET status = 'INVALIDATED', liveSlot = NULL,
        closedAtMillis = :atMillis, closureReason = 'OPERATIONAL_BOUNDARY',
        deadlineStatus = CASE WHEN deadlineStatus IS NULL THEN NULL ELSE 'CANCELLED' END,
        revision = revision + 1 WHERE journeyId = :journeyId AND liveSlot = 1
        AND status != 'AUTO_END_CLAIMED'""")
    suspend fun invalidateUnclaimedProbableEnd(journeyId: String, atMillis: Long): Int
}
