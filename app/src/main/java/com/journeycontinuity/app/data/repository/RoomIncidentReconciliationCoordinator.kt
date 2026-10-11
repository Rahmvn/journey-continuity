package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.auth.ActiveJourneyStopRemote
import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.data.unresolved.*
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.sync.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** The single authority reconciliation path for exact pending Journeys. */
class RoomIncidentReconciliationCoordinator(
    private val db: JourneyDatabase,
    private val authority: IncidentAuthorityGateway,
    private val cloud: CloudSyncGateway,
    private val stop: ActiveJourneyStopRemote,
    private val terminalHandoffGate: JourneyTerminalHandoffGate = JourneyTerminalHandoffGate(),
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
    private val onStoppedEffects: suspend (String) -> Unit = {},
    private val requestRetry: suspend (String, Long?) -> Unit = { _, _ -> },
    private val localOwner: suspend () -> String?,
) {
    private val runMutex = Mutex()
    private val effectsMutex = Mutex()

    fun observePendingAuthority(): Flow<List<PendingAuthorityJourney>> =
        db.incidentReconciliationDao().observePendingAuthority()

    suspend fun advancePending(journeyId: String? = null): Int {
        var attempts = 0
        db.incidentReconciliationDao().pendingAuthority().forEach { pending ->
            if ((journeyId == null || pending.journeyId == journeyId) &&
                retryDue(pending.retryAtMillis, clock.nowMillis())) {
                attempts++
                advance(pending.journeyId)
            }
        }
        return attempts
    }

    suspend fun requestStop(journeyId: String, expectedOwnerId: String): Boolean {
        uuid(expectedOwnerId)
        val accepted = terminalHandoffGate.withLock { db.withTransaction {
            val j = db.journeyDao().getById(journeyId) ?: return@withTransaction false
            val row = workflow(journeyId)
            if (row.explicitAction == "STOP" && row.explicitState == "PENDING")
                return@withTransaction row.explicitOwnerId == expectedOwnerId
            if (j.status != JourneyStatus.ACTIVE || j.activeSlot != 1 || row.claimState == "PENDING" ||
                row.blocksExplicitOrClaim() || hasAutomaticClaim(journeyId)) return@withTransaction false
            if (!cas(row, row.copy(explicitActionId = UUID.randomUUID().toString(), explicitAction = "STOP",
                    explicitAtMillis = clock.nowMillis(), explicitState = "PENDING", explicitOwnerId = expectedOwnerId,
                    explicitDispatch = "NEVER_DISPATCHED", retryAtMillis = null,
                    lastOutcome = IncidentReconciliationOutcome.EXPLICIT_PENDING.name, effectsPending = true)))
                return@withTransaction false
            val op = db.operationalBoundaryDao().get(journeyId)
            if (op != null && op.state == "MONITORING") db.operationalBoundaryDao().pauseExact(
                journeyId, op.admissionId, op.revision, "EXPLICIT_STOP_INTENT", "INSUFFICIENT_PROOF", null)
            db.operationalBoundaryDao().deactivateDegradation(journeyId)
            closeProbableEndForOperationalPause(db, journeyId, clock.nowMillis())
            db.incidentReconciliationDao().requestSync(journeyId)
            true
        } }
        // No admission lock may be acquired while holding the dispatch gate.
        if (accepted) { finishEffects(journeyId); requestRetry(journeyId, null) }
        return accepted
    }

    suspend fun advance(journeyId: String): IncidentReconciliationOutcome = runMutex.withLock {
        try { attempt(journeyId) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            // Persist or rethrow: the worker must never report success for a lost attempt.
            failure(journeyId, db.incidentReconciliationDao().get(journeyId), e)
        }
    }

    private suspend fun attempt(journeyId: String): IncidentReconciliationOutcome {
        var prior = db.incidentReconciliationDao().get(journeyId)
        if (prior != null && !retryDue(prior.retryAtMillis, clock.nowMillis())) return prior.outcome()
        // The Room observer can wake as soon as an explicit intent commits. Complete its local
        // stop effect before an automatic wake may dispatch the corresponding remote operation.
        if (prior?.effectsPending == true) {
            finishEffects(journeyId)
            prior = db.incidentReconciliationDao().get(journeyId)
            if (prior?.effectsPending == true)
                return record(journeyId, prior, IncidentReconciliationOutcome.EXPLICIT_PENDING)
        }
        val owner = try { cloud.authenticatedOwnerId() } catch (e: Exception) { return failure(journeyId, prior, e) }
        val boundOwner = try { localOwner() } catch (e: Exception) { return failure(journeyId, prior, e) }
        if (boundOwner != owner)
            return record(journeyId, prior, IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED)
        if (prior?.explicitOwnerId != null && prior.explicitOwnerId != owner)
            return record(journeyId, prior, IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED)
        val server = try { cloud.ownerJourneyState(journeyId, owner) }
            catch (e: Exception) { return failure(journeyId, prior, e) }
        if (server != null && server.status != JourneyStatus.ACTIVE) {
            return if (reconcileServerTerminal(server)) IncidentReconciliationOutcome.TERMINAL
                else {
                    // A failed exact terminal comparison may have inserted only an empty
                    // workflow row. Record the conflict on that row, but never overwrite a
                    // concurrent operation or a typed transient provenance-read failure.
                    val current = db.incidentReconciliationDao().get(journeyId)
                    val expected = if (prior == null && current?.lastOutcome == null &&
                        current?.explicitAction == null && current?.claimId == null) current else prior
                    record(journeyId, expected, if (server.status == JourneyStatus.AUTO_ENDED)
                        IncidentReconciliationOutcome.COMPETING_TERMINAL else IncidentReconciliationOutcome.INCONSISTENT)
                }
        }
        if (prior?.blocksExplicitOrClaim() == true) {
            if (prior.explicitState in ACCOUNT_STATES) {
                // Account owns this exact operation. An ACTIVE server read is expected before
                // its Stop dispatch and cannot prove that a reservation was abandoned.
                return IncidentReconciliationOutcome.EXPLICIT_PENDING
            }
            return reconcileExplicit(prior, owner, server == null)
        }
        val j = db.journeyDao().getById(journeyId) ?: return IncidentReconciliationOutcome.OBSOLETE
        if (j.status != JourneyStatus.ACTIVE || j.activeSlot != 1 ||
            db.operationalBoundaryDao().get(journeyId)?.state != "PENDING_AUTHORITY")
            return IncidentReconciliationOutcome.OBSOLETE

        var operation = prior?.takeIf { it.claimState == "PENDING" }
        if (operation == null) {
            val read = authority.eligibility(journeyId)
            if (read is AuthorityRead.Failed) return record(journeyId, prior, read.outcome)
            val value = (read as AuthorityRead.Value).value
            if (value.eligibility != UnresolvedEligibility.ELIGIBLE || value.claim == null)
                return record(journeyId, prior, when (value.reason) {
                    UnresolvedReason.BOUNDARY_NOT_REACHED -> IncidentReconciliationOutcome.TOO_EARLY
                    UnresolvedReason.NOT_VERIFYING -> IncidentReconciliationOutcome.NO_CURRENT_VERIFICATION
                    UnresolvedReason.SUPERSEDED_EPISODE -> IncidentReconciliationOutcome.SUPERSEDED_EPISODE
                    UnresolvedReason.INCONSISTENT_STATE -> IncidentReconciliationOutcome.INCONSISTENT
                    else -> IncidentReconciliationOutcome.INELIGIBLE
                })
            val claim = value.claim
            claim.validate()
            if (claim.ownerId != owner) return record(journeyId, prior, IncidentReconciliationOutcome.INCONSISTENT)
            operation = db.withTransaction {
                val current = db.journeyDao().getById(journeyId) ?: return@withTransaction null
                val row = workflow(journeyId)
                val boundary = db.unresolvedBoundaryDao().get(journeyId) ?: return@withTransaction null
                if (current.status != JourneyStatus.ACTIVE || current.activeSlot != 1 ||
                    row.blocksExplicitOrClaim() || hasAutomaticClaim(journeyId) ||
                    db.operationalBoundaryDao().get(journeyId)?.state != "PENDING_AUTHORITY" ||
                    boundary.admission() != claim.admission()) return@withTransaction null
                if (row.claimState == "PENDING") return@withTransaction row
                check(cas(row, row.copy(claimId = UUID.randomUUID().toString(), claimJson = Json.encodeToString(claim),
                    claimState = "PENDING", claimDispatch = "NEVER_DISPATCHED",
                    lastOutcome = IncidentReconciliationOutcome.CLAIM_PENDING.name, retryAtMillis = null)))
                db.incidentReconciliationDao().get(journeyId)
            } ?: return IncidentReconciliationOutcome.EXPLICIT_PENDING
        }
        val claim = try { Json.decodeFromString<IncidentClaim>(requireNotNull(operation.claimJson)).also(IncidentClaim::validate) }
            catch (e: Exception) { return record(journeyId, operation, IncidentReconciliationOutcome.INCONSISTENT) }
        if (operation.claimId == null) return record(journeyId, operation, IncidentReconciliationOutcome.INCONSISTENT)

        // No poison-state early return. A MAY_HAVE_DISPATCHED request is retried with its exact original identity.
        // The server either returns its committed outcome or atomically refuses the old episode.
        var dispatched = operation
        val response = authority.finalize(claim) {
            val marked = markDispatch(operation, claim = true) ?: return@finalize false
            dispatched = marked
            true
        }
        if (response is AuthorityRead.Failed) return record(journeyId, dispatched, response.outcome)
        val result = (response as AuthorityRead.Value).value
        if (result.decision == "UNRESOLVED_INCIDENT" || result.status != JourneyStatus.ACTIVE) {
            val refreshed = try { cloud.ownerJourneyState(journeyId, owner) }
                catch (e: Exception) { return failure(journeyId, dispatched, e) }
            if (refreshed == null || refreshed.status == JourneyStatus.ACTIVE)
                return record(journeyId, dispatched, IncidentReconciliationOutcome.INCONSISTENT)
            return if (reconcileServerTerminal(refreshed)) IncidentReconciliationOutcome.TERMINAL
                else record(journeyId, dispatched, IncidentReconciliationOutcome.INCONSISTENT)
        }
        return record(journeyId, dispatched, if (result.decision == "TOO_EARLY") IncidentReconciliationOutcome.TOO_EARLY
            else IncidentReconciliationOutcome.FINAL_GUARD_BLOCKED, releaseClaim = true)
    }

    private suspend fun markDispatch(expected: JourneyTerminalReconciliationEntity, claim: Boolean):
        JourneyTerminalReconciliationEntity? = db.withTransaction {
        val current = db.incidentReconciliationDao().get(expected.journeyId) ?: return@withTransaction null
        if (!sameOperation(expected, current) || current.lastOutcome == "TERMINAL" ||
            !retryDue(current.retryAtMillis, clock.nowMillis())) return@withTransaction null
        if (claim && (current.claimState != "PENDING" || current.blocksExplicitOrClaim() ||
                db.journeyDao().getById(current.journeyId)?.status != JourneyStatus.ACTIVE)) return@withTransaction null
        if (!claim && current.explicitState != "PENDING") return@withTransaction null
        check(cas(current, current.copy(
            claimDispatch = if (claim) "MAY_HAVE_DISPATCHED" else current.claimDispatch,
            explicitDispatch = if (!claim) "MAY_HAVE_DISPATCHED" else current.explicitDispatch,
            retryAtMillis = retryAt())))
        db.incidentReconciliationDao().get(current.journeyId)
    }

    suspend fun reconcilePendingExplicit(journeyId: String): Boolean = runMutex.withLock {
        val row = db.incidentReconciliationDao().get(journeyId) ?: return@withLock true
        if (!row.blocksExplicitOrClaim()) return@withLock true
        attempt(journeyId) == IncidentReconciliationOutcome.TERMINAL ||
            db.incidentReconciliationDao().get(journeyId)?.explicitState == "REFUSED"
    }

    private suspend fun reconcileExplicit(row: JourneyTerminalReconciliationEntity, owner: String, missingServer: Boolean):
        IncidentReconciliationOutcome {
        val j = db.journeyDao().getById(row.journeyId) ?: return IncidentReconciliationOutcome.OBSOLETE
        val dispatched = markDispatch(row, claim = false) ?: return currentOutcome(row.journeyId)
        try {
            when (row.explicitAction) {
                "COMPLETE" -> {
                    if (j.status != JourneyStatus.COMPLETED || j.completedAt != row.explicitAtMillis)
                        return record(j.id, dispatched, IncidentReconciliationOutcome.INCONSISTENT)
                    cloud.upsertJourney(j.toDomain(), owner)
                }
                "STOP" -> {
                    // A valid offline admission may not have reached the server yet. Preserve its original ACTIVE facts.
                    if (missingServer) cloud.upsertJourney(j.toDomain(), owner)
                    val ack = stop.stop(j.id, owner)
                    if (ack.status != JourneyStatus.CANCELLED || ack.endedAt == null)
                        return record(j.id, dispatched, IncidentReconciliationOutcome.INCONSISTENT)
                }
                else -> return record(j.id, dispatched, IncidentReconciliationOutcome.INCONSISTENT)
            }
            val server = cloud.ownerJourneyState(j.id, owner)
                ?: return record(j.id, dispatched, IncidentReconciliationOutcome.EXPLICIT_PENDING)
            return if (server.status != JourneyStatus.ACTIVE && reconcileServerTerminal(server))
                IncidentReconciliationOutcome.TERMINAL else record(j.id, dispatched, IncidentReconciliationOutcome.EXPLICIT_PENDING)
        } catch (e: Exception) { return failure(j.id, dispatched, e) }
    }

    suspend fun reconcileServerTerminal(server: RemoteJourneyState): Boolean {
        val expected = db.incidentReconciliationDao().get(server.journeyId)
        return try { reconcileServerTerminalExact(server) }
        catch (e: Exception) { failure(server.journeyId, expected, e); false }
    }

    private suspend fun reconcileServerTerminalExact(server: RemoteJourneyState): Boolean {
        if (server.status == JourneyStatus.ACTIVE) return false
        val snapshot = db.incidentReconciliationDao().get(server.journeyId)
        val owner = cloud.authenticatedOwnerId()
        if (owner != server.ownerId || cloud.ownerJourneyState(server.journeyId, owner) != server) return false
        val proof = if (server.status == JourneyStatus.UNRESOLVED_INCIDENT) {
            when (val read = authority.provenance(server.journeyId)) {
                is AuthorityRead.Value -> read.value
                is AuthorityRead.Failed -> { record(server.journeyId, snapshot, read.outcome); return false }
            }
        } else null
        if (proof != null) {
            proof.validate()
            if (proof.claim.ownerId != owner || proof.claim.journeyId != server.journeyId ||
                instant(proof.finalizedAt).toEpochMilli() != server.endedAt || server.completedAt != null) return false
        }
        val autoProof = if (server.status == JourneyStatus.AUTO_ENDED) {
            when (val read = authority.autoEndProvenance(server.journeyId)) {
                is AuthorityRead.Value -> read.value.also {
                    it.validate()
                    require(it.journeyId == server.journeyId && it.ownerId == owner &&
                        instant(it.endedAt).toEpochMilli() == server.endedAt && server.completedAt == null)
                }
                is AuthorityRead.Failed -> { record(server.journeyId, snapshot, read.outcome); return false }
            }
        } else null
        // An owner session/status change while a provenance read is in flight cannot authorize this response.
        if (cloud.authenticatedOwnerId() != owner || cloud.ownerJourneyState(server.journeyId, owner) != server) return false
        val committed = terminalHandoffGate.withLock { db.withTransaction {
            val j = db.journeyDao().getById(server.journeyId) ?: return@withTransaction false
            val row = workflow(j.id)
            if (row.explicitOwnerId != null && row.explicitOwnerId != owner) return@withTransaction false
            if (proof != null) {
                val b = db.unresolvedBoundaryDao().get(j.id) ?: return@withTransaction false
                if (!b.admission().valid() || b.admissionId != proof.claim.admissionId ||
                    b.policyVersion != proof.claim.policyVersion || b.etaMillis != instant(proof.claim.originalEta).toEpochMilli() ||
                    b.boundaryMillis != instant(proof.claim.boundaryAt).toEpochMilli() ||
                    j.startedAt != b.admittedAtMillis || j.expectedArrivalAt != b.etaMillis) return@withTransaction false
                val existing = db.incidentReconciliationDao().provenance(j.id)
                if (existing != null && Json.decodeFromString<IncidentProvenance>(existing.provenanceJson) != proof)
                    return@withTransaction false
                if (existing == null) db.incidentReconciliationDao().insertProvenance(
                    JourneyIncidentFinalizationEntity(j.id, Json.encodeToString(proof)))
            }
            if (autoProof != null) {
                val existing = db.incidentReconciliationDao().autoEndProvenance(j.id)
                if (existing != null && !Json.decodeFromString<AutoEndProvenance>(existing.provenanceJson).sameFacts(autoProof))
                    return@withTransaction false
                if (existing == null) db.incidentReconciliationDao().insertAutoEndProvenance(
                    JourneyAutoEndFinalizationEntity(j.id, Json.encodeToString(autoProof)))
                // Retain a real local claimed presentation only if it is this exact server window.
                // On a second device there is no such row, and none is invented.
                db.probableEndPresentationDao().pendingAutomaticClaims().filter { it.journeyId == j.id }.forEach { p ->
                    p.validate()
                    if (p.id == autoProof.presentationEpisodeId && p.candidateEpisodeId == autoProof.candidateEpisodeId &&
                        p.generation == autoProof.detectorGeneration && p.policyVersion == autoProof.policyVersion &&
                        p.historyBaselineAtMillis == instant(autoProof.historyBaselineAt).toEpochMilli() &&
                        p.guardVersion == autoProof.guardVersion &&
                        p.serverWindowRegisteredAtMillis == instant(autoProof.registeredAt).toEpochMilli() &&
                        p.serverWindowNotBeforeAtMillis == instant(autoProof.notBeforeAt).toEpochMilli()) {
                        check(db.probableEndPresentationDao().recordAutomaticEnd(p.id, requireNotNull(server.endedAt)) == 1)
                    } else {
                        val at = maxOf(requireNotNull(server.endedAt), p.createdAtMillis,
                            p.presentedAtMillis ?: 0, p.deadlineExpiredAtMillis ?: 0)
                        check(db.probableEndPresentationDao().invalidateAutomaticClaim(p.id, at, "DIFFERENT_AUTHORITATIVE_WINDOW") == 1)
                    }
                }
            }
            val pendingCompletion = row.explicitAction == "COMPLETE" && row.explicitState == "PENDING"
            if (!db.journeyDao().reconcileAuthoritativeTerminalInTransaction(j.id, server.status,
                    server.completedAt, server.endedAt, pendingCompletion)) error("Conflicting local terminal outcome")
            val expected = when (row.explicitAction) { "COMPLETE" -> JourneyStatus.COMPLETED; "STOP" -> JourneyStatus.CANCELLED; else -> null }
            check(cas(row, row.copy(
                explicitState = row.explicitState?.let {
                    if (server.status == expected && (expected != JourneyStatus.COMPLETED || server.completedAt == row.explicitAtMillis))
                        "ACKNOWLEDGED" else "SUPERSEDED" },
                explicitDispatch = row.explicitDispatch?.let { "SETTLED" },
                claimState = row.claimState?.let {
                    if (proof != null && row.claimJson != null && runCatching {
                        Json.decodeFromString<IncidentClaim>(row.claimJson).sameEpisode(proof.claim)
                    }.getOrDefault(false)) "COMMITTED" else "SUPERSEDED" },
                claimDispatch = row.claimDispatch?.let { "SETTLED" },
                lastOutcome = "TERMINAL", retryAtMillis = null,
                effectsPending = if (row.lastOutcome == "TERMINAL") row.effectsPending else true)))
            true
        } }
        if (committed) finishEffects(server.journeyId)
        return committed
    }

    suspend fun restore() { db.incidentReconciliationDao().pending().forEach { advance(it.journeyId) }; restoreEffects() }
    suspend fun restoreEffects() { db.incidentReconciliationDao().pending().filter { it.effectsPending }.forEach { finishEffects(it.journeyId) } }
    private suspend fun finishEffects(journeyId: String) = effectsMutex.withLock {
        val snapshot = db.incidentReconciliationDao().get(journeyId) ?: return@withLock
        if (!snapshot.effectsPending) return@withLock
        try {
            onStoppedEffects(journeyId)
            db.withTransaction {
                val row = db.incidentReconciliationDao().get(journeyId) ?: return@withTransaction
                if (sameOperation(snapshot, row)) cas(row, row.copy(effectsPending = false))
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
    }
    private suspend fun workflow(journeyId: String): JourneyTerminalReconciliationEntity {
        db.incidentReconciliationDao().insert(JourneyTerminalReconciliationEntity(journeyId))
        return requireNotNull(db.incidentReconciliationDao().get(journeyId))
    }
    private suspend fun hasAutomaticClaim(journeyId: String) =
        db.probableEndPresentationDao().pendingAutomaticClaims().any { it.journeyId == journeyId }
    private suspend fun cas(old: JourneyTerminalReconciliationEntity, next: JourneyTerminalReconciliationEntity) =
        db.incidentReconciliationDao().compareAndSet(old, next)

    private fun sameOperation(a: JourneyTerminalReconciliationEntity, b: JourneyTerminalReconciliationEntity) =
        a.journeyId == b.journeyId && a.revision == b.revision && a.claimId == b.claimId && a.claimState == b.claimState &&
            a.explicitActionId == b.explicitActionId && a.explicitState == b.explicitState

    private suspend fun record(journeyId: String, expected: JourneyTerminalReconciliationEntity?,
        outcome: IncidentReconciliationOutcome, releaseClaim: Boolean = false):
        IncidentReconciliationOutcome {
        val at = retryAt()
        val applied = db.withTransaction {
            val j = db.journeyDao().getById(journeyId) ?: return@withTransaction false
            val found = db.incidentReconciliationDao().get(journeyId)
            if (expected == null && found != null || expected != null && (found == null || !sameOperation(expected, found)))
                return@withTransaction false
            val row = found ?: workflow(journeyId)
            if (row.lastOutcome == "TERMINAL" || j.status in setOf(JourneyStatus.CANCELLED, JourneyStatus.AUTO_ENDED,
                    JourneyStatus.UNRESOLVED_INCIDENT)) return@withTransaction false
            val neverDispatched = row.claimState == "PENDING" && row.claimDispatch == "NEVER_DISPATCHED" &&
                outcome in setOf(IncidentReconciliationOutcome.UNAVAILABLE, IncidentReconciliationOutcome.INCONSISTENT,
                    IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED)
            check(cas(row, row.copy(lastOutcome = outcome.name, retryAtMillis = at,
                claimState = if ((releaseClaim || neverDispatched) && row.claimState == "PENDING") "BLOCKED" else row.claimState,
                claimDispatch = if ((releaseClaim || neverDispatched) && row.claimState == "PENDING") "REFUSED" else row.claimDispatch)))
            true
        }
        // Definitive ineligibility waits for a new foreground/auth/connectivity event. A durable
        // deferred wake is needed only while a transport or authority result can still converge.
        if (applied && shouldScheduleRetry(outcome.name)) {
            try { requestRetry(journeyId, at) }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
        return if (applied) outcome else currentOutcome(journeyId)
    }
    private suspend fun failure(journeyId: String, expected: JourneyTerminalReconciliationEntity?, error: Exception):
        IncidentReconciliationOutcome {
        if (error is CancellationException) throw error
        return record(journeyId, expected, when ((error as? CloudSyncException)?.kind) {
            SyncFailureKind.AUTHENTICATION, SyncFailureKind.AUTHORIZATION -> IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED
            SyncFailureKind.PERMANENT, SyncFailureKind.TERMINAL_CONFLICT -> IncidentReconciliationOutcome.INCONSISTENT
            else -> IncidentReconciliationOutcome.UNAVAILABLE
        })
    }
    private suspend fun currentOutcome(journeyId: String) =
        db.incidentReconciliationDao().get(journeyId)?.outcome() ?: IncidentReconciliationOutcome.OBSOLETE
    private fun JourneyTerminalReconciliationEntity.outcome() = lastOutcome?.let {
        runCatching { IncidentReconciliationOutcome.valueOf(it) }.getOrNull()
    } ?: if (blocksExplicitOrClaim()) IncidentReconciliationOutcome.EXPLICIT_PENDING else IncidentReconciliationOutcome.CLAIM_PENDING
    private fun JourneyTerminalReconciliationEntity.blocksExplicitOrClaim() = explicitState in BLOCKING_EXPLICIT
    private fun retryAt() = Math.addExact(clock.nowMillis().coerceAtMost(Long.MAX_VALUE - RETRY_MILLIS), RETRY_MILLIS)
    companion object {
        const val RETRY_MILLIS = 5 * 60 * 1000L
        private val ACCOUNT_STATES = setOf("ACCOUNT_RESERVED", "ACCOUNT_UNCERTAIN")
        private val BLOCKING_EXPLICIT = setOf("PENDING", "ACCOUNT_RESERVED", "ACCOUNT_UNCERTAIN")
        private val RETRYABLE_OUTCOMES = setOf(IncidentReconciliationOutcome.UNAVAILABLE,
            IncidentReconciliationOutcome.CLAIM_PENDING, IncidentReconciliationOutcome.EXPLICIT_PENDING,
            IncidentReconciliationOutcome.TOO_EARLY,
            IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED)
        fun shouldScheduleRetry(outcome: String?): Boolean =
            RETRYABLE_OUTCOMES.any { it.name == outcome }

        /** A deadline more than one retry interval ahead signals a backward wall-clock jump.
         * Allow one event-driven attempt; any failed attempt persists a fresh bounded deadline. */
        fun retryDue(retryAtMillis: Long?, nowMillis: Long): Boolean =
            retryAtMillis == null || nowMillis >= retryAtMillis ||
                runCatching { Math.subtractExact(retryAtMillis, nowMillis) }
                    .getOrDefault(Long.MAX_VALUE) > RETRY_MILLIS
    }
}
