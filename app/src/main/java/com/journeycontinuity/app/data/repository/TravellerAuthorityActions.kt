package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.unresolved.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.sync.*
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Foreground, user-requested operations only. There is deliberately no finalizer/advance dependency. */
class TravellerAuthorityActions(
    private val db: JourneyDatabase,
    private val cloud: CloudSyncGateway,
    private val authority: IncidentAuthorityGateway,
    private val reconcileTerminal: suspend (RemoteJourneyState) -> Boolean,
    private val requestStop: suspend (String, String) -> Boolean,
    private val localOwner: suspend () -> String?,
    private val afterComplete: suspend (String) -> Unit,
    private val requestSync: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryReconciliation: (suspend (String) -> IncidentReconciliationOutcome)? = null,
) {
    val records = db.travellerAuthorityDao().observe()

    /** A manual retry is available only for a durable failed attempt, after its existing backoff. */
    suspend fun retryFailed(journeyId: String): String {
        val row = db.travellerAuthorityDao().get(journeyId) ?: return "OBSOLETE"
        val workflow = row.reconciliation ?: return "OBSOLETE"
        if (!row.isPendingAuthority() || workflow.lastOutcome != "UNAVAILABLE" ||
            workflow.retryAtMillis?.let { clock() < it } == true) return "OBSOLETE"
        // advance re-reads exact Room state, serializes attempts, reconciles explicit intent first,
        // and requires server eligibility before it can initiate an incident finalizer.
        return retryReconciliation?.invoke(journeyId)?.name ?: "OBSOLETE"
    }

    suspend fun acknowledge(result: UnresolvedJourneyResult): Boolean = db.withTransaction {
        val record = db.travellerAuthorityDao().get(result.journeyId) ?: return@withTransaction false
        if (record.unresolvedResult() != result) return@withTransaction false
        db.travellerAuthorityDao().acknowledge(result.journeyId, result.provenanceJson,
            Instant.parse(result.finalizedAt).toEpochMilli(),
            maxOf(clock(), Instant.parse(result.finalizedAt).toEpochMilli())) == 1
    }

    suspend fun complete(journeyId: String): Boolean {
        val accepted = db.withTransaction {
            val record = db.travellerAuthorityDao().get(journeyId) ?: return@withTransaction false
            if (!record.canRecordExplicitAction() || db.journeyDao().getActive()?.id != journeyId)
                return@withTransaction false
            db.journeyDao().completeActive(clock(), retainActiveSlot = true)?.id == journeyId
        }
        if (accepted) {
            // The local transaction is authoritative for intent even if teardown/scheduling needs recovery.
            try { afterComplete(journeyId) } catch (e: Exception) { if (e is CancellationException) throw e }
            requestSync()
        }
        return accepted
    }

    suspend fun stop(journeyId: String): Boolean {
        val owner = localOwner() ?: return false
        val record = db.travellerAuthorityDao().get(journeyId) ?: return false
        if (!record.canRecordExplicitAction()) return false
        return requestStop(journeyId, owner)
    }

    suspend fun check(journeyId: String): String = try {
        val owner = cloud.authenticatedOwnerId()
        val server = cloud.ownerJourneyState(journeyId, owner)
        if (server != null && server.status != JourneyStatus.ACTIVE) {
            if (reconcileTerminal(server)) "TERMINAL" else "INCONSISTENT"
        } else {
            val record = db.travellerAuthorityDao().get(journeyId)
            if (record?.reconciliation?.explicitState in setOf("PENDING", "ACCOUNT_RESERVED", "ACCOUNT_UNCERTAIN"))
                return "EXPLICIT_PENDING"
            if (record?.reconciliation?.claimState == "PENDING" ||
                record?.presentations?.any { it.status == "AUTO_END_CLAIMED" } == true)
                return "CLAIM_PENDING"
            when (val read = authority.eligibility(journeyId)) {
                is AuthorityRead.Failed -> read.outcome.name
                is AuthorityRead.Value -> when (read.value.reason) {
                    UnresolvedReason.BOUNDARY_NOT_REACHED -> "TOO_EARLY"
                    UnresolvedReason.NOT_VERIFYING -> "NO_CURRENT_VERIFICATION"
                    UnresolvedReason.SUPERSEDED_EPISODE -> "SUPERSEDED_EPISODE"
                    UnresolvedReason.INCONSISTENT_STATE -> "INCONSISTENT"
                    else -> if (read.value.eligibility == UnresolvedEligibility.ELIGIBLE) "ELIGIBLE" else "INELIGIBLE"
                }
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        when ((e as? CloudSyncException)?.kind) {
            SyncFailureKind.AUTHENTICATION, SyncFailureKind.AUTHORIZATION -> "AUTHENTICATION_REQUIRED"
            SyncFailureKind.PERMANENT, SyncFailureKind.TERMINAL_CONFLICT -> "INCONSISTENT"
            else -> "UNAVAILABLE"
        }
    }
}
