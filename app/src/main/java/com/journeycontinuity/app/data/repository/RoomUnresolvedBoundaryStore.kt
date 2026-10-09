package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.unresolved.UnresolvedBoundaryGateway
import com.journeycontinuity.app.domain.*

/** Explicit read foundation only: no scheduler, monitoring effect, UI or terminal operation. */
class RoomUnresolvedBoundaryStore(
    private val database: JourneyDatabase,
    private val gateway: UnresolvedBoundaryGateway,
    private val receipt: () -> BoundaryReceipt,
) {
    suspend fun refresh(journeyId: String): Boolean {
        val requested = database.unresolvedBoundaryDao().get(journeyId) ?: return false
        val sending = receipt()
        val observation = gateway.read(journeyId) ?: return false // outside Room transaction
        val received = receipt()
        val elapsedSpan = if (sending.sameBoot(received))
            Math.subtractExact(received.elapsedRealtimeMillis, sending.elapsedRealtimeMillis) else null
        return database.withTransaction {
            val current = database.unresolvedBoundaryDao().get(journeyId) ?: return@withTransaction false
            val journey = database.journeyDao().getById(journeyId) ?: return@withTransaction false
            if (current.revision != requested.revision ||
                current.admission() != requested.admission() ||
                journey.startedAt != current.admittedAtMillis || journey.expectedArrivalAt != current.etaMillis ||
                !observation.matches(current.admission(), observation.ownerId) ||
                !observation.coherentEligibility() ||
                (current.observedOwnerId != null && current.observedOwnerId != observation.ownerId) ||
                (current.serverObservedAtMillis != null && observation.evaluatedAtMillis < current.serverObservedAtMillis) ||
                received.elapsedRealtimeMillis < 0 || received.installationId.isBlank()) return@withTransaction false
            val firstProof = current.firstBoundaryProofAtMillis ?: observation.evaluatedAtMillis.takeIf {
                boundaryProof(current.admission(), it) == BoundaryProof.BOUNDARY_REACHED_WITH_PROOF
            }
            database.unresolvedBoundaryDao().updateObservation(current.copy(
                observedOwnerId = observation.ownerId, serverAcceptedAtMillis = observation.acceptedAtMillis,
                serverObservedAtMillis = observation.evaluatedAtMillis,
                eligibility = observation.eligibility.name, reason = observation.reason.name,
                verificationCaseId = observation.verificationCaseId, openingEventId = observation.openingEventId,
                verifyingStartedAtMillis = observation.verifyingStartedAtMillis,
                guardVersion = observation.guardVersion, historyBaselineAtMillis = observation.historyBaselineAtMillis,
                firstBoundaryProofAtMillis = firstProof, receiptElapsedRealtimeMillis = received.elapsedRealtimeMillis,
                receiptBootCount = received.bootCount, receiptInstallationId = received.installationId,
                receiptElapsedSpanMillis = elapsedSpan, utcUncertaintyMillis = null,
                revision = Math.addExact(current.revision, 1),
            ))
            true
        }
    }
}
