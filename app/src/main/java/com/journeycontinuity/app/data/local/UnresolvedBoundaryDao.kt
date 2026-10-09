package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update

@Dao
interface UnresolvedBoundaryDao {
    @Query("SELECT * FROM unresolved_boundaries WHERE journeyId = :journeyId")
    suspend fun get(journeyId: String): UnresolvedBoundaryEntity?

    /** Only the observation store uses this, under an exact-state Room transaction. */
    @Update
    suspend fun updateObservation(row: UnresolvedBoundaryEntity)
}
