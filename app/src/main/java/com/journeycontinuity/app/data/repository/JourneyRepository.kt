package com.journeycontinuity.app.data.repository

import com.journeycontinuity.app.domain.Journey
import kotlinx.coroutines.flow.Flow

interface JourneyRepository {
    val activeJourney: Flow<Journey?>
    suspend fun createIfNoActive(journey: Journey): Boolean
    suspend fun completeActive(completedAt: Long): Journey?
}
