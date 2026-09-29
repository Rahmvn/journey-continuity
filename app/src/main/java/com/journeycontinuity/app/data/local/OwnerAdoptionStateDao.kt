package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Query

/** One SQLite statement sees all owner-scoped Room tables in the same read snapshot. */
@Dao
interface OwnerAdoptionStateDao {
    @Query(
        """SELECT EXISTS (
            SELECT 1 FROM journeys
            UNION ALL SELECT 1 FROM telemetry_observations
            UNION ALL SELECT 1 FROM journey_sync_states
            UNION ALL SELECT 1 FROM journey_heartbeat_states
            UNION ALL SELECT 1 FROM journey_degradation_states
            UNION ALL SELECT 1 FROM journey_fallback_bindings
            UNION ALL SELECT 1 FROM fallback_attempts
            LIMIT 1
        )""",
    )
    suspend fun hasAnyOwnerScopedRows(): Boolean
}
