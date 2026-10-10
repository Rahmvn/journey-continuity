package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.auth.*
import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import com.journeycontinuity.app.domain.JourneyStatus
import java.util.UUID

class RoomAccountStopReservations(private val db: JourneyDatabase,
    private val dispatch: JourneyTerminalHandoffGate,
    private val clock: () -> Long = System::currentTimeMillis,
) : AccountStopReservations {
    // Admission -> handoff -> Room, matching acknowledgement. Authentication never runs here.
    // A reservation cannot commit between heartbeat's final permission read and transport handoff.
    override suspend fun reserve(journeyId: String, ownerId: String): AccountStopOperation? = dispatch.withLock { db.withTransaction {
        val j = db.journeyDao().getById(journeyId) ?: return@withTransaction null
        if (j.status != JourneyStatus.ACTIVE || j.activeSlot != 1) return@withTransaction null
        db.incidentReconciliationDao().insert(JourneyTerminalReconciliationEntity(journeyId))
        val row = requireNotNull(db.incidentReconciliationDao().get(journeyId))
        if (row.claimState == "PENDING" || db.probableEndPresentationDao().pendingAutomaticClaims().any { it.journeyId == journeyId })
            return@withTransaction null
        if (row.explicitState in setOf("ACCOUNT_RESERVED", "ACCOUNT_UNCERTAIN") && row.explicitOwnerId == ownerId)
            return@withTransaction AccountStopOperation(requireNotNull(row.explicitActionId), journeyId, ownerId)
        if (row.explicitAction != null && row.explicitState != "REFUSED") return@withTransaction null
        val id = UUID.randomUUID().toString()
        check(db.incidentReconciliationDao().compareAndSet(row, row.copy(explicitActionId = id,
            explicitAction = "STOP", explicitAtMillis = clock(), explicitState = "ACCOUNT_RESERVED",
            explicitOwnerId = ownerId, explicitDispatch = "NEVER_DISPATCHED", retryAtMillis = null)))
        db.incidentReconciliationDao().requestSync(journeyId)
        AccountStopOperation(id, journeyId, ownerId)
    } }

    private fun matches(row: JourneyTerminalReconciliationEntity, op: AccountStopOperation) =
        row.journeyId == op.journeyId && row.explicitActionId == op.id && row.explicitOwnerId == op.ownerId &&
            row.explicitAction == "STOP" && row.explicitState in setOf("ACCOUNT_RESERVED", "ACCOUNT_UNCERTAIN")

    override suspend fun markDispatch(operation: AccountStopOperation): Boolean = db.withTransaction {
        val row = db.incidentReconciliationDao().get(operation.journeyId) ?: return@withTransaction false
        if (!matches(row, operation) || row.claimState == "PENDING" ||
            db.journeyDao().getById(operation.journeyId)?.status != JourneyStatus.ACTIVE) return@withTransaction false
        db.incidentReconciliationDao().compareAndSet(row, row.copy(explicitDispatch = "MAY_HAVE_DISPATCHED"))
    }
    override suspend fun uncertain(operation: AccountStopOperation) { db.withTransaction {
        val row = db.incidentReconciliationDao().get(operation.journeyId) ?: return@withTransaction
        if (matches(row, operation) && row.explicitDispatch == "MAY_HAVE_DISPATCHED")
            db.incidentReconciliationDao().compareAndSet(row, row.copy(explicitState = "ACCOUNT_UNCERTAIN"))
    } }
    override suspend fun releaseUndispatched(operation: AccountStopOperation) { db.withTransaction {
        val row = db.incidentReconciliationDao().get(operation.journeyId) ?: return@withTransaction
        if (matches(row, operation) && row.explicitDispatch == "NEVER_DISPATCHED")
            db.incidentReconciliationDao().compareAndSet(row, row.copy(explicitState = "REFUSED"))
    } }
    override suspend fun acknowledge(operation: AccountStopOperation, endedAt: Long): Boolean = dispatch.withLock {
        db.withTransaction {
            val row = db.incidentReconciliationDao().get(operation.journeyId) ?: return@withTransaction false
            if (row.explicitActionId == operation.id && row.explicitState == "ACKNOWLEDGED")
                return@withTransaction db.journeyDao().getById(operation.journeyId)?.let {
                    it.status == JourneyStatus.CANCELLED && it.endedAt == endedAt
                } == true
            if (!matches(row, operation) || row.claimState == "PENDING") return@withTransaction false
            if (!db.journeyDao().reconcileAuthoritativeTerminalInTransaction(operation.journeyId,
                    JourneyStatus.CANCELLED, null, endedAt, false)) return@withTransaction false
            check(db.incidentReconciliationDao().compareAndSet(row, row.copy(explicitState = "ACKNOWLEDGED",
                explicitDispatch = "SETTLED", lastOutcome = "TERMINAL", retryAtMillis = null, effectsPending = true)))
            true
        }
    }
}
