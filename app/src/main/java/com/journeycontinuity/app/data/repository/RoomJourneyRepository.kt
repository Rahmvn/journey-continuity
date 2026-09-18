package com.journeycontinuity.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.journeycontinuity.app.data.local.JourneyDao
import com.journeycontinuity.app.data.local.HeartbeatDao
import com.journeycontinuity.app.data.local.TelemetryDao
import com.journeycontinuity.app.data.local.SyncStateDao
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.domain.TelemetrySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.journeycontinuity.app.sync.SyncScheduler
import com.journeycontinuity.app.sync.SyncRequestUrgency

class RoomJourneyRepository(
    private val journeyDao: JourneyDao,
    private val telemetryDao: TelemetryDao,
    private val syncStateDao: SyncStateDao,
    private val heartbeatDao: HeartbeatDao,
    private val syncScheduler: SyncScheduler,
) : JourneyRepository {
    override val activeJourney: Flow<Journey?> =
        journeyDao.observeActive().map { it?.toDomain() }

    override suspend fun createIfNoActive(journey: Journey): Boolean = try {
        journeyDao.insertIfNoActive(journey.toEntity()).also { created ->
            if (created) scheduleSyncWithoutAffectingLocalWrite(SyncRequestUrgency.URGENT)
        }
    } catch (_: SQLiteConstraintException) {
        // The unique active slot closes the race between concurrent transactions.
        false
    }

    override suspend fun completeActive(completedAt: Long): Journey? =
        journeyDao.completeActive(completedAt)?.toDomain()?.also {
            scheduleSyncWithoutAffectingLocalWrite(SyncRequestUrgency.URGENT)
        }

    override fun observeTelemetry(journeyId: String): Flow<TelemetrySummary> =
        combine(
            telemetryDao.observeCount(journeyId),
            telemetryDao.observeLatest(journeyId),
        ) { count, latest ->
            TelemetrySummary(count = count, latest = latest?.toDomain())
        }

    override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? =
        telemetryDao.insertForActiveJourney(sample)?.toDomain()?.also {
            scheduleSyncWithoutAffectingLocalWrite(SyncRequestUrgency.ROUTINE)
        }

    override fun observeSyncState(journeyId: String): Flow<JourneySyncState?> =
        syncStateDao.observe(journeyId).map { it?.toDomain() }

    override fun observeMonitoringState(journeyId: String): Flow<CloudMonitoringState?> =
        heartbeatDao.observe(journeyId).map { it?.toDomain() }

    private fun scheduleSyncWithoutAffectingLocalWrite(urgency: SyncRequestUrgency) {
        runCatching { syncScheduler.schedule(urgency) }
    }
}
