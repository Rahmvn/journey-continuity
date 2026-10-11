package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.LocalProbableEndLifecycle
import com.journeycontinuity.app.data.local.OperationalBoundaryEntity
import com.journeycontinuity.app.domain.BoundaryProof
import com.journeycontinuity.app.domain.BoundaryReceipt
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.OperationalBoundaryAssessment
import com.journeycontinuity.app.domain.OperationalBoundaryState
import com.journeycontinuity.app.domain.OperationalPauseReason
import com.journeycontinuity.app.domain.approximateOperationalCapReached
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Durable local privacy boundary; server authority remains separate. */
class RoomOperationalBoundaryCoordinator(
    private val db: JourneyDatabase,
    private val terminalHandoffGate: JourneyTerminalHandoffGate = JourneyTerminalHandoffGate(),
    private val afterPause: suspend (String) -> Unit = {},
    private val diagnosticWallClock: () -> Long = System::currentTimeMillis,
) {
    suspend fun evaluate(journeyId: String, now: BoundaryReceipt): OperationalBoundaryAssessment {
        val result = terminalHandoffGate.withLock {
            db.withTransaction {
                val journey = db.journeyDao().getById(journeyId)
                    ?: return@withTransaction OperationalBoundaryAssessment.NOT_ACTIVE
                if (journey.status != JourneyStatus.ACTIVE || journey.activeSlot != 1)
                    return@withTransaction OperationalBoundaryAssessment.NOT_ACTIVE
                val boundary = db.unresolvedBoundaryDao().get(journeyId)
                    ?: return@withTransaction OperationalBoundaryAssessment.NOT_TRACKED
                if (!boundary.admission().valid() ||
                    journey.startedAt != boundary.admittedAtMillis ||
                    journey.expectedArrivalAt != boundary.etaMillis)
                    return@withTransaction OperationalBoundaryAssessment.NOT_TRACKED
                // A v19 admission has valid ETA provenance but no v20 boot anchor. Record that
                // absence only when evaluating; the migration never invents clock history.
                val row = db.operationalBoundaryDao().get(journeyId)
                    ?: OperationalBoundaryEntity(journeyId, boundary.admissionId).also {
                        db.operationalBoundaryDao().insert(it)
                    }
                if (row.admissionId != boundary.admissionId)
                    return@withTransaction OperationalBoundaryAssessment.NOT_TRACKED
                val proven = boundary.observedOwnerId != null && boundary.serverAcceptedAtMillis != null &&
                    boundary.proof() == BoundaryProof.BOUNDARY_REACHED_WITH_PROOF
                if (row.state == OperationalBoundaryState.PENDING_AUTHORITY.name) {
                    if (proven) db.operationalBoundaryDao().recordAuthoritativeProof(
                        journeyId, row.admissionId, row.revision)
                    return@withTransaction OperationalBoundaryAssessment.ALREADY_PAUSED
                }
                if (row.state != OperationalBoundaryState.MONITORING.name)
                    return@withTransaction OperationalBoundaryAssessment.NOT_TRACKED
                val cap = row.anchor()?.let { approximateOperationalCapReached(boundary.admission(), it, now) }
                val reason = when {
                    proven -> OperationalPauseReason.AUTHENTICATED_BOUNDARY_PROOF
                    cap == null -> OperationalPauseReason.LOST_OR_INVALID_BOOT_ANCHOR
                    cap -> OperationalPauseReason.APPROXIMATE_MONOTONIC_CAP
                    else -> null
                }
                if (reason == null) {
                    db.operationalBoundaryDao().assessExact(journeyId, row.admissionId,
                        row.revision, OperationalBoundaryAssessment.INSUFFICIENT_AUTHORITY.name)
                    return@withTransaction OperationalBoundaryAssessment.BEFORE_APPROXIMATE_CAP
                }
                if (db.operationalBoundaryDao().pauseExact(journeyId, row.admissionId,
                    row.revision, reason.name,
                    if (proven) BoundaryProof.BOUNDARY_REACHED_WITH_PROOF.name
                    else BoundaryProof.INSUFFICIENT_PROOF.name,
                    now.elapsedRealtimeMillis.takeIf { it >= 0 }) != 1)
                    return@withTransaction OperationalBoundaryAssessment.NOT_TRACKED
                db.operationalBoundaryDao().deactivateDegradation(journeyId)
                closeProbableEndForOperationalPause(db, journeyId, diagnosticWallClock())
                OperationalBoundaryAssessment.PAUSED
            }
        }
        // The Room pause is durable before this point. Give its exact teardown a bounded chance
        // even when the evaluating worker is cancelled; the service's Room observer is the
        // independent backup if cancellation occurred while the transaction returned.
        if (result == OperationalBoundaryAssessment.PAUSED) {
            try { withContext(NonCancellable) { withTimeout(5_000L) { afterPause(journeyId) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Persisted pause and entry preflight remain authoritative. */ }
        }
        return result
    }
}

/** Called under the Room transaction and shared dispatch gate; never a clock authority. */
internal suspend fun closeProbableEndForOperationalPause(db: JourneyDatabase, journeyId: String, wallAt: Long) {
    val candidates = db.probableEndDao().unclosed(journeyId)
    val presentation = db.probableEndPresentationDao().live(journeyId)
    val recordedAt = maxOf(wallAt, 1L,
        candidates.maxOfOrNull { maxOf(it.createdAtMillis, it.final.observedAtMillis) } ?: 1L,
        presentation?.createdAtMillis ?: 1L, presentation?.presentedAtMillis ?: 1L,
        presentation?.deadlineExpiredAtMillis ?: 1L)
    if (presentation?.status != "AUTO_END_CLAIMED") {
        db.probableEndDao().closeUnclosed(journeyId,
            LocalProbableEndLifecycle.INVALIDATED.name, recordedAt, "OPERATIONAL_BOUNDARY")
        db.probableEndGuardDao().invalidate(journeyId)
    }
    db.operationalBoundaryDao().invalidateUnclaimedProbableEnd(journeyId, recordedAt)
}
