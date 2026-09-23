package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.journeycontinuity.app.degraded.FallbackBindingStatus
import com.journeycontinuity.app.degraded.FallbackTransportState
import com.journeycontinuity.app.degraded.FallbackTransportOutcome

@Dao
interface FallbackAttemptDao {
    @Query("SELECT * FROM fallback_attempts WHERE localAttemptId = :localAttemptId LIMIT 1")
    suspend fun getByLocalAttemptId(localAttemptId: Long): FallbackAttemptEntity?

    @Query(
        """SELECT * FROM fallback_attempts
           WHERE journeyId = :journeyId
             AND (transportState = :allocated
               OR (transportState = :retryPending AND (nextRetryAt IS NULL OR nextRetryAt <= :atMillis)))
           ORDER BY envelopeSequence LIMIT 1""",
    )
    suspend fun getNextReadyAttempt(
        journeyId: String,
        atMillis: Long,
        allocated: FallbackTransportState = FallbackTransportState.ALLOCATED,
        retryPending: FallbackTransportState = FallbackTransportState.RETRY_PENDING,
    ): FallbackAttemptEntity?
    @Query(
        """SELECT * FROM fallback_attempts
           WHERE journeyId = :journeyId AND degradationEpisodeId = :episodeId
             AND telemetrySequence = :telemetrySequence LIMIT 1""",
    )
    suspend fun getLogicalAttempt(
        journeyId: String,
        episodeId: Long,
        telemetrySequence: Long,
    ): FallbackAttemptEntity?

    @Query(
        """SELECT * FROM fallback_attempts
           WHERE journeyId = :journeyId AND envelopeSequence = :envelopeSequence LIMIT 1""",
    )
    suspend fun getByEnvelopeSequence(
        journeyId: String,
        envelopeSequence: Long,
    ): FallbackAttemptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attempt: FallbackAttemptEntity): Long

    @Query("SELECT * FROM journey_fallback_bindings WHERE journeyId = :journeyId LIMIT 1")
    suspend fun getBinding(journeyId: String): JourneyFallbackBindingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBinding(binding: JourneyFallbackBindingEntity)

    @Query(
        """UPDATE fallback_attempts
           SET transportState = :superseded, terminalAt = :atMillis
           WHERE journeyId = :journeyId
             AND transportState IN (:allocated, :retryPending)""",
    )
    suspend fun supersedeUnsent(
        journeyId: String,
        atMillis: Long,
        superseded: FallbackTransportState = FallbackTransportState.SUPERSEDED,
        allocated: FallbackTransportState = FallbackTransportState.ALLOCATED,
        retryPending: FallbackTransportState = FallbackTransportState.RETRY_PENDING,
    ): Int

    @Query(
        """UPDATE fallback_attempts
           SET transportState = :inProgress,
               transportAttemptCount = transportAttemptCount + 1,
               lastAttemptAt = :atMillis,
               handoffGeneration = handoffGeneration + 1,
               handoffStartedAt = :atMillis,
               nextRetryAt = NULL,
               lastTransportOutcome = NULL,
               lastTransportResultCode = NULL,
               uncertainSince = NULL
           WHERE localAttemptId = :localAttemptId
             AND (transportState = :allocated
               OR (transportState = :retryPending AND (nextRetryAt IS NULL OR nextRetryAt <= :atMillis)))""",
    )
    suspend fun claimForHandoff(
        localAttemptId: Long,
        atMillis: Long,
        inProgress: FallbackTransportState = FallbackTransportState.HANDOFF_IN_PROGRESS,
        allocated: FallbackTransportState = FallbackTransportState.ALLOCATED,
        retryPending: FallbackTransportState = FallbackTransportState.RETRY_PENDING,
    ): Int

    @Query(
        """UPDATE fallback_attempts
           SET transportState = :handedOff, terminalAt = :atMillis,
               lastTransportOutcome = :outcome, lastTransportResultCode = :resultCode
           WHERE localAttemptId = :localAttemptId AND transportState = :inProgress
             AND handoffGeneration = :generation""",
    )
    suspend fun markHandedOff(
        localAttemptId: Long,
        generation: Int,
        atMillis: Long,
        resultCode: Int,
        handedOff: FallbackTransportState = FallbackTransportState.HANDED_OFF,
        inProgress: FallbackTransportState = FallbackTransportState.HANDOFF_IN_PROGRESS,
        outcome: FallbackTransportOutcome = FallbackTransportOutcome.ANDROID_HANDOFF_SUCCEEDED,
    ): Int

    @Query(
        """UPDATE fallback_attempts
           SET transportState = :retryPending, nextRetryAt = :nextRetryAt,
               lastTransportOutcome = :outcome, lastTransportResultCode = :resultCode
           WHERE localAttemptId = :localAttemptId AND transportState = :inProgress
             AND handoffGeneration = :generation""",
    )
    suspend fun markRetryPending(
        localAttemptId: Long,
        generation: Int,
        nextRetryAt: Long,
        outcome: FallbackTransportOutcome,
        resultCode: Int?,
        retryPending: FallbackTransportState = FallbackTransportState.RETRY_PENDING,
        inProgress: FallbackTransportState = FallbackTransportState.HANDOFF_IN_PROGRESS,
    ): Int

    @Query(
        """UPDATE fallback_attempts
           SET transportState = :permanentFailure, terminalAt = :atMillis,
               lastTransportOutcome = :outcome, lastTransportResultCode = :resultCode
           WHERE localAttemptId = :localAttemptId AND transportState = :inProgress
             AND handoffGeneration = :generation""",
    )
    suspend fun markPermanentFailure(
        localAttemptId: Long,
        generation: Int,
        atMillis: Long,
        resultCode: Int?,
        permanentFailure: FallbackTransportState = FallbackTransportState.PERMANENT_FAILURE,
        inProgress: FallbackTransportState = FallbackTransportState.HANDOFF_IN_PROGRESS,
        outcome: FallbackTransportOutcome = FallbackTransportOutcome.PERMANENT_FAILURE,
    ): Int

    @Query(
        """UPDATE fallback_attempts
           SET transportState = :retryPending, nextRetryAt = :nextRetryAt,
               lastTransportOutcome = :unknownOutcome, uncertainSince = :atMillis,
               lastTransportResultCode = NULL
           WHERE localAttemptId = :localAttemptId AND transportState = :inProgress
             AND handoffGeneration = :generation
             AND handoffStartedAt IS NOT NULL AND handoffStartedAt <= :staleBefore""",
    )
    suspend fun markUnknownOutcomeForRetry(
        localAttemptId: Long,
        generation: Int,
        staleBefore: Long,
        atMillis: Long,
        nextRetryAt: Long,
        retryPending: FallbackTransportState = FallbackTransportState.RETRY_PENDING,
        inProgress: FallbackTransportState = FallbackTransportState.HANDOFF_IN_PROGRESS,
        unknownOutcome: FallbackTransportOutcome = FallbackTransportOutcome.UNKNOWN_OUTCOME,
    ): Int

    @Query(
        """UPDATE fallback_attempts
           SET transportState = :permanentFailure, terminalAt = :atMillis,
               lastTransportOutcome = :outcome
           WHERE localAttemptId = :localAttemptId
             AND transportState IN (:allocated, :retryPending)""",
    )
    suspend fun markPreflightPermanentFailure(
        localAttemptId: Long,
        atMillis: Long,
        permanentFailure: FallbackTransportState = FallbackTransportState.PERMANENT_FAILURE,
        allocated: FallbackTransportState = FallbackTransportState.ALLOCATED,
        retryPending: FallbackTransportState = FallbackTransportState.RETRY_PENDING,
        outcome: FallbackTransportOutcome = FallbackTransportOutcome.PERMANENT_FAILURE,
    ): Int

    @Query(
        """SELECT COUNT(*) FROM journey_fallback_bindings
           WHERE journeyId = :journeyId AND status = :status""",
    )
    suspend fun hasProvisionedBinding(
        journeyId: String,
        status: FallbackBindingStatus = FallbackBindingStatus.PROVISIONED,
    ): Int

    @Query("SELECT * FROM fallback_attempts WHERE journeyId = :journeyId ORDER BY envelopeSequence")
    suspend fun allForJourney(journeyId: String): List<FallbackAttemptEntity>
}
