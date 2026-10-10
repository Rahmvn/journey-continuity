package com.journeycontinuity.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A transactional read projection of existing evidence, never a second status store. */
data class TravellerAuthorityRecord(
    @Embedded val journey: JourneyEntity,
    @Relation(parentColumn = "id", entityColumn = "journeyId")
    val operational: OperationalBoundaryEntity?,
    @Relation(parentColumn = "id", entityColumn = "journeyId")
    val reconciliation: JourneyTerminalReconciliationEntity?,
    @Relation(parentColumn = "id", entityColumn = "journeyId")
    val incident: JourneyIncidentFinalizationEntity?,
    @Relation(parentColumn = "id", entityColumn = "journeyId")
    val automatic: JourneyAutoEndFinalizationEntity?,
    @Relation(parentColumn = "id", entityColumn = "journeyId")
    val presentations: List<ProbableEndPresentationEntity>,
)

@Dao
interface TravellerAuthorityDao {
    @Transaction
    @Query("""SELECT * FROM journeys WHERE activeSlot = 1
        OR id IN (SELECT journeyId FROM journey_terminal_reconciliations
            WHERE explicitState IN ('PENDING','ACCOUNT_RESERVED','ACCOUNT_UNCERTAIN'))
        OR id IN (SELECT journeyId FROM journey_incident_finalizations
            WHERE resultAcknowledgedAtMillis IS NULL)
        OR id IN (SELECT journeyId FROM probable_end_presentations
            WHERE status = 'AUTO_ENDED' AND automaticResultAcknowledgedAtMillis IS NULL)
        ORDER BY startedAt DESC, id""")
    fun observe(): Flow<List<TravellerAuthorityRecord>>

    @Transaction
    @Query("SELECT * FROM journeys WHERE id = :journeyId")
    suspend fun get(journeyId: String): TravellerAuthorityRecord?

    /** Exact immutable receipt comparison; never acknowledge a local pause or change terminal facts. */
    @Query("""UPDATE journey_incident_finalizations SET resultAcknowledgedAtMillis = :at
        WHERE journeyId = :journeyId AND provenanceJson = :provenance
        AND resultAcknowledgedAtMillis IS NULL
        AND EXISTS (SELECT 1 FROM journeys j WHERE j.id = :journeyId
            AND j.status = 'UNRESOLVED_INCIDENT' AND j.endedAt = :endedAt
            AND j.completedAt IS NULL AND j.activeSlot IS NULL)""")
    suspend fun acknowledge(journeyId: String, provenance: String, endedAt: Long, at: Long): Int
}
