package com.journeycontinuity.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.journeycontinuity.app.data.local.JourneyDao
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.domain.Journey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomJourneyRepository(
    private val dao: JourneyDao,
) : JourneyRepository {
    override val activeJourney: Flow<Journey?> =
        dao.observeActive().map { it?.toDomain() }

    override suspend fun createIfNoActive(journey: Journey): Boolean = try {
        dao.insertIfNoActive(journey.toEntity())
    } catch (_: SQLiteConstraintException) {
        // The unique active slot closes the race between concurrent transactions.
        false
    }

    override suspend fun completeActive(completedAt: Long): Journey? =
        dao.completeActive(completedAt)?.toDomain()
}
