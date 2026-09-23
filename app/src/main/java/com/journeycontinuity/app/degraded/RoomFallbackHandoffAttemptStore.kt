package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.data.local.FallbackAttemptDao

class RoomFallbackHandoffAttemptStore(
    private val dao: FallbackAttemptDao,
) : FallbackHandoffAttemptStore {
    override suspend fun nextReady(journeyId: String, atMillis: Long) =
        dao.getNextReadyAttempt(journeyId, atMillis)

    override suspend fun get(localAttemptId: Long) = dao.getByLocalAttemptId(localAttemptId)

    override suspend fun claim(localAttemptId: Long, atMillis: Long) =
        dao.claimForHandoff(localAttemptId, atMillis) == 1

    override suspend fun preflightPermanent(localAttemptId: Long, atMillis: Long) =
        dao.markPreflightPermanentFailure(localAttemptId, atMillis) == 1

    override suspend fun handedOff(localAttemptId: Long, generation: Int, atMillis: Long, resultCode: Int) =
        dao.markHandedOff(localAttemptId, generation, atMillis, resultCode) == 1

    override suspend fun retryPending(
        localAttemptId: Long,
        generation: Int,
        nextRetryAt: Long,
        outcome: FallbackTransportOutcome,
        resultCode: Int?,
    ) = dao.markRetryPending(localAttemptId, generation, nextRetryAt, outcome, resultCode) == 1

    override suspend fun permanentFailure(
        localAttemptId: Long,
        generation: Int,
        atMillis: Long,
        resultCode: Int?,
    ) = dao.markPermanentFailure(localAttemptId, generation, atMillis, resultCode) == 1

    override suspend fun unknownForRetry(
        localAttemptId: Long,
        generation: Int,
        staleBefore: Long,
        atMillis: Long,
        nextRetryAt: Long,
    ) = dao.markUnknownOutcomeForRetry(
        localAttemptId, generation, staleBefore, atMillis, nextRetryAt,
    ) == 1
}
