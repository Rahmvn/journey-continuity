package com.journeycontinuity.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.journeycontinuity.app.data.local.JourneyDao
import com.journeycontinuity.app.data.local.TelemetryDao
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.domain.TelemetrySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomJourneyRepository(
    private val journeyDao: JourneyDao,
    private val telemetryDao: TelemetryDao,
) : JourneyRepository {
    override val activeJourney: Flow<Journey?> =
        journeyDao.observeActive().map { it?.toDomain() }

    override suspend fun createIfNoActive(journey: Journey): Boolean = try {
        journeyDao.insertIfNoActive(journey.toEntity())
    } catch (_: SQLiteConstraintException) {
        // The unique active slot closes the race between concurrent transactions.
        false
    }

    override suspend fun completeActive(completedAt: Long): Journey? =
        journeyDao.completeActive(completedAt)?.toDomain()

    override fun observeTelemetry(journeyId: String): Flow<TelemetrySummary> =
        combine(
            telemetryDao.observeCount(journeyId),
            telemetryDao.observeLatest(journeyId),
        ) { count, latest ->
            TelemetrySummary(count = count, latest = latest?.toDomain())
        }

    override suspend fun recordTelemetry(sample: TelemetrySample): TelemetryObservation? =
        telemetryDao.insertForActiveJourney(sample)?.toDomain()
}
