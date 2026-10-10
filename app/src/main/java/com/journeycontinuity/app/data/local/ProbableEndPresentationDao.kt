package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProbableEndPresentationDao {
    @Query("SELECT * FROM probable_end_presentations WHERE journeyId = :journeyId AND liveSlot = 1")
    suspend fun live(journeyId: String): ProbableEndPresentationEntity?

    @Query("SELECT * FROM probable_end_presentations WHERE id = :id")
    suspend fun byId(id: String): ProbableEndPresentationEntity?

    @Query("""SELECT p.* FROM probable_end_presentations p JOIN journeys j ON j.id = p.journeyId
        WHERE p.deadlineStatus = 'ACTIVE' AND p.status = 'PRESENTED' AND p.liveSlot = 1
          AND j.status = 'ACTIVE' AND j.activeSlot = 1""")
    suspend fun activeDeadlines(): List<ProbableEndPresentationEntity>

    @Query("""SELECT p.* FROM probable_end_presentations p JOIN journeys j ON j.id = p.journeyId
        WHERE p.status = 'PRESENTED' AND p.liveSlot = 1 AND p.respondedAtMillis IS NULL
          AND p.deadlineStatus IN ('ACTIVE', 'EXPIRED_PENDING_FINALIZATION')
          AND j.status = 'ACTIVE' AND j.activeSlot = 1""")
    suspend fun windowsNeedingRegistration(): List<ProbableEndPresentationEntity>

    @Query("""SELECT * FROM probable_end_presentations
        WHERE status = 'AUTO_END_CLAIMED'""")
    suspend fun pendingAutomaticClaims(): List<ProbableEndPresentationEntity>

    @Query("""SELECT * FROM probable_end_presentations
        WHERE status = 'AUTO_ENDED' AND automaticEffectsAppliedAtMillis IS NULL""")
    suspend fun pendingAutomaticEffects(): List<ProbableEndPresentationEntity>

    @Query("""UPDATE probable_end_presentations SET serverWindowRegisteredAtMillis = :registeredAt,
        serverWindowNotBeforeAtMillis = :notBefore
        WHERE id = :episodeId AND journeyId = :journeyId AND candidateEpisodeId = :candidateId
          AND generation = :generation AND revision = :expectedRevision
          AND status = 'PRESENTED' AND liveSlot = 1 AND respondedAtMillis IS NULL
          AND serverWindowRegisteredAtMillis IS NULL AND confirmationDeadlineAtMillis IS NOT NULL""")
    suspend fun saveServerWindow(episodeId: String, journeyId: String, candidateId: String,
        generation: Long, expectedRevision: Long, registeredAt: Long, notBefore: Long): Int

    @Query("""UPDATE probable_end_presentations SET status = 'AUTO_END_CLAIMED', liveSlot = NULL,
        finalizationClaimedAtMillis = :claimedAt, revision = revision + 1
        WHERE id = :episodeId AND journeyId = :journeyId AND candidateEpisodeId = :candidateId
          AND generation = :generation AND revision = :expectedRevision
          AND status = 'PRESENTED' AND liveSlot = 1 AND respondedAtMillis IS NULL
          AND deadlineStatus = 'EXPIRED_PENDING_FINALIZATION'
          AND serverWindowNotBeforeAtMillis IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM journey_terminal_reconciliations r WHERE r.journeyId = :journeyId
              AND (r.claimState = 'PENDING' OR r.explicitState IN ('PENDING','ACCOUNT_RESERVED','ACCOUNT_UNCERTAIN')))""")
    suspend fun claimAutomaticEnd(episodeId: String, journeyId: String, candidateId: String,
        generation: Long, expectedRevision: Long, claimedAt: Long): Int

    @Query("""UPDATE probable_end_presentations SET status = 'PRESENTED', liveSlot = 1,
        finalizationClaimedAtMillis = NULL, revision = revision + 1
        WHERE id = :episodeId AND status = 'AUTO_END_CLAIMED'
          AND finalizationClaimedAtMillis = :claimedAt""")
    suspend fun releaseEarlyClaim(episodeId: String, claimedAt: Long): Int

    @Query("""UPDATE probable_end_presentations SET status = 'AUTO_ENDED',
        automaticEndedAtMillis = :endedAt, closedAtMillis = :endedAt,
        closureReason = 'AUTHORITATIVE_AUTO_END', revision = revision + 1
        WHERE id = :episodeId AND status = 'AUTO_END_CLAIMED'
          AND automaticEndedAtMillis IS NULL AND respondedAtMillis IS NULL""")
    suspend fun recordAutomaticEnd(episodeId: String, endedAt: Long): Int

    @Query("""UPDATE probable_end_presentations SET status = 'INVALIDATED',
        closedAtMillis = :at, closureReason = :reason, deadlineStatus = 'CANCELLED',
        revision = revision + 1 WHERE id = :episodeId AND status = 'AUTO_END_CLAIMED'""")
    suspend fun invalidateAutomaticClaim(episodeId: String, at: Long, reason: String): Int

    @Query("""UPDATE probable_end_presentations SET status = 'INVALIDATED', liveSlot = NULL,
        closedAtMillis = :at, closureReason = :reason, deadlineStatus = 'CANCELLED',
        revision = revision + 1 WHERE id = :episodeId AND journeyId = :journeyId
          AND revision = :expectedRevision AND status = 'PRESENTED' AND liveSlot = 1""")
    suspend fun invalidateExactPresented(episodeId: String, journeyId: String,
        expectedRevision: Long, at: Long, reason: String): Int

    @Query("""UPDATE probable_end_presentations SET automaticEffectsAppliedAtMillis = :at,
        revision = revision + 1 WHERE id = :episodeId AND status = 'AUTO_ENDED'
          AND automaticEffectsAppliedAtMillis IS NULL""")
    suspend fun markAutomaticEffectsApplied(episodeId: String, at: Long): Int

    @Query("""UPDATE probable_end_presentations SET automaticResultAcknowledgedAtMillis = :at,
        revision = revision + 1 WHERE id = :episodeId AND status = 'AUTO_ENDED'
          AND automaticResultAcknowledgedAtMillis IS NULL""")
    suspend fun acknowledgeAutomaticResult(episodeId: String, at: Long): Int

    @Query("""SELECT p.id AS episodeId, p.journeyId AS journeyId,
        j.destination AS destination, j.expectedArrivalAt AS expectedArrivalAt
        FROM probable_end_presentations p JOIN journeys j ON j.id = p.journeyId
        WHERE p.status = 'AUTO_ENDED' AND p.automaticResultAcknowledgedAtMillis IS NULL
          AND j.status = 'AUTO_ENDED' ORDER BY p.automaticEndedAtMillis DESC LIMIT 1""")
    fun observePendingAutomaticResult(): Flow<ProbableEndCompletedResult?>

    @Query("""UPDATE probable_end_presentations SET status = :responseStatus, liveSlot = NULL,
        respondedAtMillis = :whenMillis, closedAtMillis = :whenMillis,
        closureReason = 'TRAVELLER_RESPONSE',
        deadlineStatus = CASE WHEN deadlineStatus IS NULL THEN NULL ELSE 'CANCELLED' END,
        revision = revision + 1
        WHERE id = :episodeId AND journeyId = :journeyId AND candidateEpisodeId = :candidateId
          AND generation = :generation AND revision = :expectedRevision
          AND status = 'PRESENTED' AND liveSlot = 1 AND presentedAtMillis IS NOT NULL""")
    suspend fun acceptResponse(episodeId: String, journeyId: String, candidateId: String,
        generation: Long, expectedRevision: Long, responseStatus: String, whenMillis: Long): Int

    @Query("""SELECT p.id AS episodeId, p.journeyId AS journeyId,
        j.destination AS destination, j.expectedArrivalAt AS expectedArrivalAt
        FROM probable_end_presentations p JOIN journeys j ON j.id = p.journeyId
        WHERE p.status = 'RESPONDED_COMPLETED' AND p.resultAcknowledgedAtMillis IS NULL
          AND j.status = 'COMPLETED' ORDER BY p.respondedAtMillis DESC LIMIT 1""")
    fun observePendingCompletedResult(): Flow<ProbableEndCompletedResult?>

    @Query("""SELECT * FROM probable_end_presentations
        WHERE status = 'RESPONDED_COMPLETED' AND completionEffectsAppliedAtMillis IS NULL""")
    suspend fun pendingCompletionEffects(): List<ProbableEndPresentationEntity>

    @Query("""UPDATE probable_end_presentations SET completionEffectsAppliedAtMillis = :whenMillis,
        revision = revision + 1 WHERE id = :episodeId AND status = 'RESPONDED_COMPLETED'
          AND completionEffectsAppliedAtMillis IS NULL""")
    suspend fun markCompletionEffectsApplied(episodeId: String, whenMillis: Long): Int

    @Query("""UPDATE probable_end_presentations SET resultAcknowledgedAtMillis = :whenMillis,
        revision = revision + 1 WHERE id = :episodeId AND status = 'RESPONDED_COMPLETED'
          AND resultAcknowledgedAtMillis IS NULL""")
    suspend fun acknowledgeCompletedResult(episodeId: String, whenMillis: Long): Int

    @Insert suspend fun insert(episode: ProbableEndPresentationEntity)

    @Query("""UPDATE probable_end_presentations SET status = 'PRESENTED', presentedAtMillis = :whenMillis,
        confirmationDeadlineAtMillis = :deadlineAtMillis, deadlineStatus = 'ACTIVE',
        revision = revision + 1 WHERE id = :id AND status = 'READY_TO_PRESENT' AND liveSlot = 1""")
    suspend fun markPresented(id: String, whenMillis: Long, deadlineAtMillis: Long): Int

    @Query("""UPDATE probable_end_presentations SET deadlineStatus = 'EXPIRED_PENDING_FINALIZATION',
        deadlineExpiredAtMillis = :whenMillis
        WHERE id = :episodeId AND journeyId = :journeyId AND candidateEpisodeId = :candidateId
          AND generation = :generation AND revision = :expectedRevision
          AND status = 'PRESENTED' AND liveSlot = 1 AND respondedAtMillis IS NULL
          AND deadlineStatus = 'ACTIVE' AND confirmationDeadlineAtMillis = :deadlineAtMillis
          AND :whenMillis >= confirmationDeadlineAtMillis
          AND EXISTS (SELECT 1 FROM journeys j WHERE j.id = :journeyId
              AND j.status = 'ACTIVE' AND j.activeSlot = 1)""")
    suspend fun expireWindow(episodeId: String, journeyId: String, candidateId: String,
        generation: Long, expectedRevision: Long, deadlineAtMillis: Long, whenMillis: Long): Int

    @Query("""UPDATE probable_end_presentations SET status = 'INVALIDATED', liveSlot = NULL,
        closedAtMillis = :whenMillis, closureReason = :reason,
        deadlineStatus = CASE WHEN deadlineStatus IS NULL THEN NULL ELSE 'CANCELLED' END,
        revision = revision + 1
        WHERE journeyId = :journeyId AND liveSlot = 1""")
    suspend fun invalidate(journeyId: String, whenMillis: Long, reason: String): Int

    @Query("""SELECT c.episodeId FROM probable_end_candidate_continuity c
        JOIN probable_end_episodes e ON e.id = c.episodeId
        JOIN journeys j ON j.id = c.journeyId
        WHERE j.activeSlot = 1 AND j.status = 'ACTIVE' AND e.liveSlot = 1 AND e.lifecycle = 'CANDIDATE'
          AND c.status = 'CONTINUITY_PROVEN' AND c.generation = e.generation LIMIT 1""")
    fun observeProvenCandidateId(): Flow<String?>

    @Query("""SELECT p.* FROM probable_end_presentations p
        JOIN journeys j ON j.id = p.journeyId
        JOIN probable_end_episodes e ON e.id = p.candidateEpisodeId
        JOIN probable_end_candidate_continuity c ON c.episodeId = e.id
        JOIN probable_end_guard_bindings b ON b.journeyId = p.journeyId
        JOIN probable_end_detector_states s ON s.journeyId = p.journeyId
        WHERE p.liveSlot = 1 AND j.activeSlot = 1 AND j.status = 'ACTIVE'
          AND e.liveSlot = 1 AND e.lifecycle = 'CANDIDATE' AND e.generation = p.generation
          AND c.status = 'CONTINUITY_PROVEN' AND c.generation = p.generation
          AND s.generation = p.generation AND s.proximity_firstSequence = e.first_telemetrySequence
          AND b.status = 'BOUND' AND b.historyBaselineAtMillis = p.historyBaselineAtMillis
          AND b.guardVersion = p.guardVersion LIMIT 1""")
    fun observeActionableLocalEpisode(): Flow<ProbableEndPresentationEntity?>
}

data class ProbableEndCompletedResult(
    val episodeId: String,
    val journeyId: String,
    val destination: String,
    val expectedArrivalAt: Long,
) {
    override fun toString() = "ProbableEndCompletedResult(redacted)"
}
