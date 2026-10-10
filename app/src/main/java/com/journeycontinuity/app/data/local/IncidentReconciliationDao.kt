package com.journeycontinuity.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface IncidentReconciliationDao {
    @Query("SELECT * FROM journey_terminal_reconciliations WHERE journeyId = :journeyId")
    suspend fun get(journeyId: String): JourneyTerminalReconciliationEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: JourneyTerminalReconciliationEntity)
    @Update
    suspend fun update(row: JourneyTerminalReconciliationEntity)

    @Query("""UPDATE journey_terminal_reconciliations SET explicitActionId=:actionId,
        explicitAction=:action, explicitAtMillis=:at, explicitState=:explicitState, explicitOwnerId=:owner,
        claimJson=:claimJson, claimState=:claimState, lastOutcome=:outcome, retryAtMillis=:retryAt,
        effectsPending=:effects, claimId=:claimId, claimDispatch=:claimDispatch,
        explicitDispatch=:explicitDispatch, revision=revision+1
        WHERE journeyId=:journeyId AND revision=:expectedRevision AND claimId IS :expectedClaimId
        AND claimState IS :expectedClaimState AND explicitActionId IS :expectedActionId
        AND explicitState IS :expectedExplicitState""")
    suspend fun writeExact(journeyId: String, expectedRevision: Long, expectedClaimId: String?,
        expectedClaimState: String?, expectedActionId: String?, expectedExplicitState: String?,
        actionId: String?, action: String?, at: Long?, explicitState: String?, owner: String?,
        claimJson: String?, claimState: String?, outcome: String?, retryAt: Long?, effects: Boolean,
        claimId: String?, claimDispatch: String?, explicitDispatch: String?): Int

    suspend fun compareAndSet(expected: JourneyTerminalReconciliationEntity, next: JourneyTerminalReconciliationEntity): Boolean {
        require(expected.journeyId == next.journeyId)
        return writeExact(expected.journeyId, expected.revision, expected.claimId, expected.claimState,
            expected.explicitActionId, expected.explicitState, next.explicitActionId, next.explicitAction,
            next.explicitAtMillis, next.explicitState, next.explicitOwnerId, next.claimJson, next.claimState,
            next.lastOutcome, next.retryAtMillis, next.effectsPending, next.claimId, next.claimDispatch,
            next.explicitDispatch) == 1
    }
    @Query("SELECT * FROM journey_incident_finalizations WHERE journeyId = :journeyId")
    suspend fun provenance(journeyId: String): JourneyIncidentFinalizationEntity?
    @Insert
    suspend fun insertProvenance(row: JourneyIncidentFinalizationEntity)
    @Query("SELECT * FROM journey_auto_end_finalizations WHERE journeyId = :journeyId")
    suspend fun autoEndProvenance(journeyId: String): JourneyAutoEndFinalizationEntity?
    @Insert
    suspend fun insertAutoEndProvenance(row: JourneyAutoEndFinalizationEntity)
    @Query("""SELECT * FROM journey_terminal_reconciliations WHERE effectsPending = 1
        OR explicitState IN ('PENDING','ACCOUNT_RESERVED','ACCOUNT_UNCERTAIN') OR claimState = 'PENDING'""")
    suspend fun pending(): List<JourneyTerminalReconciliationEntity>
    @Query("""UPDATE journey_sync_states SET changeVersion = changeVersion + 1,
        phase = CASE WHEN permanentlyBlocked = 1 THEN phase ELSE 'PENDING' END,
        workRequested = CASE WHEN permanentlyBlocked = 1 THEN workRequested ELSE 1 END
        WHERE journeyId = :journeyId""")
    suspend fun requestSync(journeyId: String)
}
