package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DegradedConnectivityDao {
    @Query("SELECT * FROM journey_degradation_states WHERE journeyId = :journeyId LIMIT 1")
    suspend fun get(journeyId: String): JourneyDegradationStateEntity?

    @Query("SELECT * FROM journey_degradation_states WHERE journeyId = :journeyId LIMIT 1")
    fun observe(journeyId: String): Flow<JourneyDegradationStateEntity?>

    @Upsert
    suspend fun upsert(state: JourneyDegradationStateEntity)
}
