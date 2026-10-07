package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.domain.JourneyClock
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.probableend.*
import com.journeycontinuity.app.heartbeat.HeartbeatConfiguration

/** Engineering retry throttle, not an evidence or verification threshold. */
internal const val PROBABLE_END_GUARD_RETRY_SPACING_MILLIS = 5 * HeartbeatConfiguration.INTERVAL_MILLIS

@ConsistentCopyVisibility
data class ProbableEndGuardRequest internal constructor(
    val journeyId: String,
    val journeyStartedAtMillis: Long,
    val generation: Long,
    val detectorRevision: Long,
    val binding: ProbableEndGuardBindingEntity?,
    val continuity: ProbableEndCandidateContinuityEntity?,
    val requestedAtMillis: Long,
) { override fun toString() = "ProbableEndGuardRequest(redacted)" }

/** Network-free short transactions. No presentation/terminal permissions are produced. */
class RoomProbableEndGuardStore(
    private val db: JourneyDatabase,
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
) : ProbableEndGuardReconciliationStore<ProbableEndGuardRequest> {
    override suspend fun prepare(journeyId: String, trigger: GuardReconciliationTrigger): ProbableEndGuardRequest? = db.withTransaction {
        val journey = db.journeyDao().getById(journeyId) ?: return@withTransaction null
        if (journey.activeSlot != 1 || journey.probableEndInput().ineligibleReason != null) return@withTransaction null
        val row = db.probableEndDao().state(journeyId) ?: return@withTransaction null
        row.restore()
        val now = clock.nowMillis()
        if (now < row.lastEvaluationAtMillis || journey.startedAt != row.journeyStartedAtMillis) return@withTransaction null
        val candidate = db.probableEndDao().unclosed(journeyId).singleOrNull()
        var binding = db.probableEndGuardDao().binding(journeyId)
        if (candidate == null && (trigger == GuardReconciliationTrigger.NEW_CANDIDATE ||
                binding?.status !in setOf(ProbableEndGuardBindingStatus.BLOCKED.name,
                    ProbableEndGuardBindingStatus.NEEDS_BASELINE.name))) return@withTransaction null
        var attachment = candidate?.let {
            it.evidence()
            require(it.generation == row.generation)
            db.probableEndGuardDao().continuity(it.id) ?: it.continuity(binding)
        }
        attachment?.let {
            it.validate()
            require(it.journeyId == journeyId && it.generation == row.generation && it.requestRevision >= 0 &&
                it.status != ProbableEndContinuityStatus.INVALIDATED.name)
            if (trigger == GuardReconciliationTrigger.NEW_CANDIDATE && it.attemptedAtMillis != null) return@withTransaction null
        }
        // A genuinely new candidate gets its first check promptly. Existing candidates and
        // blocked baselines use heartbeat as a retry opportunity, never as a per-beat poll.
        val lastAttempt = attachment?.attemptedAtMillis ?: if (attachment == null) binding?.lastAttemptAtMillis else null
        if (lastAttempt != null && (now < lastAttempt || now - lastAttempt < PROBABLE_END_GUARD_RETRY_SPACING_MILLIS))
            return@withTransaction null
        binding = binding?.copy(revision = Math.addExact(binding.revision, 1), lastAttemptAtMillis = now)?.also {
            db.probableEndGuardDao().save(it)
        }
        attachment = attachment?.copy(requestRevision = Math.addExact(attachment.requestRevision, 1), attemptedAtMillis = now)?.also {
            db.probableEndGuardDao().save(it)
        }
        ProbableEndGuardRequest(journeyId, journey.startedAt, row.generation, row.revision, binding, attachment, now)
    }

    override suspend fun apply(request: ProbableEndGuardRequest, read: VerificationGuardRead): GuardReconciliationDecision? = db.withTransaction {
        val dao = db.probableEndDao()
        val guards = db.probableEndGuardDao()
        val journey = db.journeyDao().getById(request.journeyId) ?: return@withTransaction null
        val row = dao.state(request.journeyId) ?: return@withTransaction null
        row.restore()
        if (journey.startedAt != request.journeyStartedAtMillis || row.generation != request.generation ||
            row.revision < request.detectorRevision || guards.binding(request.journeyId) != request.binding) return@withTransaction null
        val candidate = dao.unclosed(request.journeyId).singleOrNull()
        if (candidate?.id != request.continuity?.episodeId ||
            (candidate != null && guards.continuity(candidate.id) != request.continuity)) return@withTransaction null
        val now = clock.nowMillis()
        if (now < request.requestedAtMillis || now < row.lastEvaluationAtMillis) return@withTransaction null
        if (journey.status != JourneyStatus.ACTIVE || journey.activeSlot != 1 || journey.probableEndInput().ineligibleReason != null) {
            dao.closeUnclosed(journey.id, LocalProbableEndLifecycle.INVALIDATED.name, now, "GUARD_JOURNEY_INELIGIBLE")
            guards.invalidate(journey.id)
            return@withTransaction GuardReconciliationDecision.BLOCKED
        }
        candidate?.let {
            it.evidence()
            require(it.generation == row.generation && it.evidenceFloorSequence == row.evidenceFloorSequence &&
                it.evidenceFloorEventTimeMillis == row.evidenceFloorEventTimeMillis)
        }
        val previous = request.binding?.let { runCatching { it.receipt() }.getOrNull() }
        if (request.binding != null && previous == null) return@withTransaction GuardReconciliationDecision.UNKNOWN
        val g = (read as? VerificationGuardRead.Available)?.guard
        val bound = previous != null && request.binding.status == ProbableEndGuardBindingStatus.BOUND.name &&
            row.generation >= request.binding.generation && row.evidenceFloorSequence >= request.binding.evidenceFloorSequence &&
            row.evidenceFloorEventTimeMillis >= request.binding.evidenceFloorEventTimeMillis
        val candidateBound = candidate != null && bound && request.continuity?.attachedGuardVersion == previous.version &&
            request.continuity.attachedHistoryBaselineAtMillis == previous.historyBaselineAtMillis &&
            candidate.first.telemetrySequence > requireNotNull(request.binding).evidenceFloorSequence &&
            candidate.first.observedAtMillis > request.binding.evidenceFloorEventTimeMillis &&
            (g == null || candidate.first.observedAtMillis > g.evidenceNotBeforeMillis)
        var decision = ProbableEndGuardReconciliation.decide(journey.id, previous, bound,
            candidate != null, candidateBound, read)
        // A long in-flight request cannot pretend its response retained the server's freshness window.
        if (decision != GuardReconciliationDecision.UNKNOWN && g?.decision == VerificationGuardDecision.CLEAR &&
            now - request.requestedAtMillis >= requireNotNull(g.freshUntilMillis) - g.evaluatedAtMillis)
            decision = GuardReconciliationDecision.UNKNOWN
        when (decision) {
            GuardReconciliationDecision.UNKNOWN -> request.continuity?.let {
                guards.save(it.copy(status = if (it.attachedGuardVersion == null) ProbableEndContinuityStatus.UNBOUND.name
                    else ProbableEndContinuityStatus.PENDING_GUARD.name,
                    lastUnavailableReason = (read as? VerificationGuardRead.Unknown)?.reason?.name
                        ?: VerificationGuardUnavailableReason.INVALID_RESPONSE.name))
            }
            GuardReconciliationDecision.BLOCKED, GuardReconciliationDecision.REBASE -> {
                val guard = requireNotNull(g)
                val latestPermitted = now.coerceAtMost(Long.MAX_VALUE - ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS) +
                    ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS
                val localFloor = maxOf(row.evidenceFloorEventTimeMillis, now,
                    dao.maximumObservedTime(journey.id, latestPermitted))
                val authoritativeFloor = maxOf(localFloor, guard.evaluatedAtMillis, guard.evidenceNotBeforeMillis)
                val ambiguous = authoritativeFloor > latestPermitted
                // A server clock too far ahead cannot safely be mapped onto current local acquisition times.
                if (ambiguous && previous == null && decision == GuardReconciliationDecision.REBASE) {
                    request.continuity?.let { guards.save(it.copy(status = if (it.attachedGuardVersion == null)
                        ProbableEndContinuityStatus.UNBOUND.name else ProbableEndContinuityStatus.PENDING_GUARD.name,
                        lastUnavailableReason = VerificationGuardUnavailableReason.INCONSISTENT_HISTORY.name)) }
                    return@withTransaction GuardReconciliationDecision.UNKNOWN
                }
                // A known BLOCKED or intervening version still permanently invalidates evidence.
                // Preserve its actual receipt/version, but never call an unmappable boundary BOUND.
                val floor = if (ambiguous) localFloor else authoritativeFloor
                val tail = db.telemetryDao().getLatest(journey.id)?.sequence ?: row.consumedSequence
                val rebased = row.copy(generation = Math.addExact(row.generation, 1),
                    evidenceFloorSequence = maxOf(row.consumedSequence, tail), consumedSequence = maxOf(row.consumedSequence, tail),
                    evidenceFloorEventTimeMillis = floor, proximity = null, exit = null,
                    // Existing suppression/corruption exit requirement is never cleared by a guard.
                    requiresReliableExit = row.requiresReliableExit, revision = Math.addExact(row.revision, 1), lastEvaluationAtMillis = now)
                dao.closeUnclosed(journey.id, LocalProbableEndLifecycle.INVALIDATED.name, now,
                    if (decision == GuardReconciliationDecision.BLOCKED) "GUARD_BLOCKED" else "GUARD_NEW_BASELINE")
                request.continuity?.let { guards.save(it.copy(status = ProbableEndContinuityStatus.INVALIDATED.name,
                    checkedHistoryBaselineAtMillis = guard.historyBaselineAtMillis, checkedGuardVersion = guard.version,
                    checkedServerAtMillis = guard.evaluatedAtMillis, lastUnavailableReason = null)) }
                guards.invalidate(journey.id)
                dao.save(rebased)
                guards.save(ProbableEndGuardBindingEntity(journey.id,
                    when { ambiguous -> ProbableEndGuardBindingStatus.NEEDS_BASELINE.name
                        decision == GuardReconciliationDecision.BLOCKED -> ProbableEndGuardBindingStatus.BLOCKED.name
                        else -> ProbableEndGuardBindingStatus.BOUND.name },
                    guard.historyBaselineAtMillis, guard.version, guard.evaluatedAtMillis,
                    rebased.evidenceFloorSequence, floor, rebased.generation,
                    Math.addExact(request.binding?.revision ?: 0, 1), guard.evaluatedAtMillis,
                    guard.evidenceNotBeforeMillis, guard.freshUntilMillis, guard.decision.name,
                    guard.verification.name, guard.reason.name, now))
            }
            GuardReconciliationDecision.REFRESH, GuardReconciliationDecision.PROVE_CONTINUITY -> {
                val guard = requireNotNull(g)
                guards.save(requireNotNull(request.binding).copy(lastServerEvaluatedAtMillis = guard.evaluatedAtMillis,
                    lastEvidenceNotBeforeMillis = guard.evidenceNotBeforeMillis, lastFreshUntilMillis = guard.freshUntilMillis,
                    revision = Math.addExact(request.binding.revision, 1)))
                request.continuity?.let { guards.save(it.copy(status = ProbableEndContinuityStatus.CONTINUITY_PROVEN.name,
                    checkedHistoryBaselineAtMillis = guard.historyBaselineAtMillis, checkedGuardVersion = guard.version,
                    checkedServerAtMillis = guard.evaluatedAtMillis, lastUnavailableReason = null)) }
            }
        }
        decision
    }
}
