package com.journeycontinuity.app.sync

import com.journeycontinuity.app.data.local.JourneyDao
import com.journeycontinuity.app.data.local.SyncStateDao
import com.journeycontinuity.app.data.local.TelemetryDao
import com.journeycontinuity.app.data.local.toDomain

class RoomLocalSyncStore(
    private val journeyDao: JourneyDao,
    private val telemetryDao: TelemetryDao,
    private val syncStateDao: SyncStateDao,
) : LocalSyncStore {
    override suspend fun nextCandidate(): PendingSyncCandidate? =
        syncStateDao.getNextCandidate()?.let {
            PendingSyncCandidate(it.journeyId, it.changeVersion)
        }

    override suspend fun journey(journeyId: String) = journeyDao.getById(journeyId)?.toDomain()

    override suspend fun checkpoint(journeyId: String): Long =
        syncStateDao.get(journeyId)?.highestTelemetrySequenceSynced ?: 0

    override suspend fun telemetryAfter(journeyId: String, afterSequence: Long, limit: Int) =
        telemetryDao.getAfterSequence(journeyId, afterSequence, limit).map { it.toDomain() }

    override suspend fun markSyncing(journeyId: String, attemptedAt: Long) {
        syncStateDao.markSyncing(journeyId, attemptedAt)
    }

    override suspend fun advanceCheckpoint(journeyId: String, sequence: Long) {
        syncStateDao.advanceCheckpoint(journeyId, sequence)
    }

    override suspend fun finishIfUnchanged(
        journeyId: String,
        expectedChangeVersion: Long,
        successfulAt: Long,
    ): Boolean = syncStateDao.finishIfUnchanged(
        journeyId,
        expectedChangeVersion,
        successfulAt,
    ) == 1

    override suspend fun markFailure(
        journeyId: String,
        message: String,
        permanentlyBlocked: Boolean,
    ) {
        syncStateDao.markFailure(journeyId, message, permanentlyBlocked)
    }
}
