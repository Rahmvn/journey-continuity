package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProbableEndGuardDao {
    @Query("SELECT * FROM probable_end_guard_bindings WHERE journeyId = :journeyId")
    suspend fun binding(journeyId: String): ProbableEndGuardBindingEntity?
    @Upsert suspend fun save(binding: ProbableEndGuardBindingEntity)
    @Query("SELECT * FROM probable_end_candidate_continuity WHERE episodeId = :episodeId")
    suspend fun continuity(episodeId: String): ProbableEndCandidateContinuityEntity?
    @Upsert suspend fun save(continuity: ProbableEndCandidateContinuityEntity)
    @Query("UPDATE probable_end_candidate_continuity SET status = 'INVALIDATED' WHERE journeyId = :journeyId AND status <> 'INVALIDATED'")
    suspend fun invalidate(journeyId: String)
}
