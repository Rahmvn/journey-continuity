package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.LocalProbableEndLifecycle
import com.journeycontinuity.app.data.local.ProbableEndContinuityStatus
import com.journeycontinuity.app.data.local.ProbableEndDeadlineStatus
import com.journeycontinuity.app.data.local.ProbableEndGuardBindingStatus
import com.journeycontinuity.app.data.local.ProbableEndPresentationEntity
import com.journeycontinuity.app.data.local.ProbableEndPresentationStatus
import com.journeycontinuity.app.domain.probableend.ProbableEndPolicy
import com.journeycontinuity.app.data.probableend.ProbableEndAutoEndGateway
import com.journeycontinuity.app.data.probableend.ProbableEndFinalization
import com.journeycontinuity.app.data.probableend.ProbableEndRegistration
import com.journeycontinuity.app.data.probableend.ProbableEndServerWindowRequest
import com.journeycontinuity.app.domain.JourneyClock
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface ProbableEndAutomaticWakeScheduler { fun schedule(episodeId: String, atMillis: Long) }
enum class ProbableEndAutomaticOutcome { ENDED, BLOCKED, TOO_EARLY, PENDING, OBSOLETE }

/** Network calls are outside Room transactions. Claim and response acceptance share Room's write lock. */
class RoomProbableEndAutoEndCoordinator(
    private val db: JourneyDatabase,
    private val gateway: ProbableEndAutoEndGateway,
    private val scheduler: ProbableEndAutomaticWakeScheduler,
    private val onTerminalEffects: suspend (String) -> Unit,
    private val onGuardBlocked: suspend (String) -> Unit = {},
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
    private val terminalHandoffGate: JourneyTerminalHandoffGate = JourneyTerminalHandoffGate(),
) {
    private val effectsMutex = Mutex()

    suspend fun register(episodeId: String): ProbableEndAutomaticOutcome {
        val snapshot = try { db.withTransaction {
            val p = db.probableEndPresentationDao().byId(episodeId) ?: return@withTransaction null
            if (!eligiblePresented(p)) return@withTransaction null
            p
        } } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return ProbableEndAutomaticOutcome.PENDING }
        if (snapshot == null) return ProbableEndAutomaticOutcome.OBSOLETE
        if (snapshot.serverWindowRegisteredAtMillis != null) {
            schedule(snapshot.id, maxOf(requireNotNull(snapshot.confirmationDeadlineAtMillis),
                requireNotNull(snapshot.serverWindowNotBeforeAtMillis)))
            return ProbableEndAutomaticOutcome.PENDING
        }
        val result = try { gateway.register(ProbableEndServerWindowRequest(snapshot.journeyId, snapshot.id,
            snapshot.candidateEpisodeId, snapshot.generation, snapshot.policyVersion,
            snapshot.historyBaselineAtMillis, snapshot.guardVersion)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ProbableEndRegistration.Unknown }
        return when (result) {
            is ProbableEndRegistration.Registered -> {
                val saved = try { db.withTransaction {
                    val current = db.probableEndPresentationDao().byId(episodeId)
                        ?: return@withTransaction false
                    if (!samePresented(current, snapshot)) return@withTransaction false
                    db.probableEndPresentationDao().saveServerWindow(episodeId,
                        snapshot.journeyId, snapshot.candidateEpisodeId, snapshot.generation,
                        snapshot.revision, result.registeredAtMillis, result.notBeforeAtMillis) == 1
                } } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { false }
                if (saved) schedule(episodeId,
                    maxOf(requireNotNull(snapshot.confirmationDeadlineAtMillis), result.notBeforeAtMillis))
                if (saved) ProbableEndAutomaticOutcome.PENDING else ProbableEndAutomaticOutcome.OBSOLETE
            }
            ProbableEndRegistration.Blocked -> {
                try { db.withTransaction {
                    val current = db.probableEndPresentationDao().byId(episodeId)
                    if (current != null && samePresented(current, snapshot)) {
                        val now = clock.nowMillis()
                        check(db.probableEndPresentationDao().invalidateExactPresented(episodeId,
                            snapshot.journeyId, snapshot.revision, now, "SERVER_GUARD_BLOCKED") == 1)
                        rebaseBlockedCandidate(current, now)
                    }
                } } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { return ProbableEndAutomaticOutcome.PENDING }
                try { onGuardBlocked(snapshot.journeyId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Guard rebase retries on existing lifecycle opportunities. */ }
                ProbableEndAutomaticOutcome.BLOCKED
            }
            ProbableEndRegistration.Unknown -> {
                schedule(episodeId, clock.nowMillis() + RETRY_MILLIS)
                ProbableEndAutomaticOutcome.PENDING
            }
        }
    }

    suspend fun advance(episodeId: String): ProbableEndAutomaticOutcome {
        val p = try { db.probableEndPresentationDao().byId(episodeId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return ProbableEndAutomaticOutcome.PENDING }
            ?: return ProbableEndAutomaticOutcome.OBSOLETE
        if (p.status == ProbableEndPresentationStatus.AUTO_ENDED.name) {
            finishEffects(episodeId)
            return ProbableEndAutomaticOutcome.ENDED
        }
        if (p.status == ProbableEndPresentationStatus.AUTO_END_CLAIMED.name)
            return finalizeClaim(p)
        if (p.status != ProbableEndPresentationStatus.PRESENTED.name) return ProbableEndAutomaticOutcome.OBSOLETE
        if (p.serverWindowRegisteredAtMillis == null) {
            val registration = register(episodeId)
            if (registration != ProbableEndAutomaticOutcome.PENDING) return registration
            val refreshed = try { db.probableEndPresentationDao().byId(episodeId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return ProbableEndAutomaticOutcome.PENDING }
            if (refreshed?.serverWindowRegisteredAtMillis == null)
                return ProbableEndAutomaticOutcome.PENDING
        }
        val claim = try { db.withTransaction {
            val current = db.probableEndPresentationDao().byId(episodeId)
                ?: return@withTransaction null
            if (!eligiblePresented(current) || current.serverWindowNotBeforeAtMillis == null ||
                current.deadlineStatus != ProbableEndDeadlineStatus.EXPIRED_PENDING_FINALIZATION.name)
                return@withTransaction null
            val now = clock.nowMillis()
            if (now < requireNotNull(current.confirmationDeadlineAtMillis) ||
                now < current.serverWindowNotBeforeAtMillis) return@withTransaction current
            if (db.probableEndPresentationDao().claimAutomaticEnd(current.id, current.journeyId,
                    current.candidateEpisodeId, current.generation, current.revision, now) != 1)
                return@withTransaction null
            db.probableEndPresentationDao().byId(episodeId)
        } } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return ProbableEndAutomaticOutcome.PENDING }
        if (claim == null) return ProbableEndAutomaticOutcome.OBSOLETE
        if (claim.status != ProbableEndPresentationStatus.AUTO_END_CLAIMED.name) {
            schedule(episodeId, maxOf(requireNotNull(claim.confirmationDeadlineAtMillis),
                requireNotNull(claim.serverWindowNotBeforeAtMillis)))
            return ProbableEndAutomaticOutcome.TOO_EARLY
        }
        return finalizeClaim(claim)
    }

    private suspend fun finalizeClaim(claim: ProbableEndPresentationEntity,
        knownResult: ProbableEndFinalization? = null): ProbableEndAutomaticOutcome {
        val result = knownResult ?: try { gateway.finalize(claim.journeyId, claim.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ProbableEndFinalization.Unknown }
        return when (result) {
            is ProbableEndFinalization.Ended -> {
                val committed = try { terminalHandoffGate.withLock { db.withTransaction {
                    val p = db.probableEndPresentationDao().byId(claim.id) ?: return@withTransaction false
                    if (p.status != ProbableEndPresentationStatus.AUTO_END_CLAIMED.name ||
                        p.journeyId != claim.journeyId || p.candidateEpisodeId != claim.candidateEpisodeId ||
                        p.generation != claim.generation || p.finalizationClaimedAtMillis != claim.finalizationClaimedAtMillis ||
                        db.journeyDao().getById(p.journeyId)?.status != JourneyStatus.ACTIVE)
                        return@withTransaction false
                    check(db.journeyDao().autoEndExactInTransaction(p.journeyId, result.endedAtMillis))
                    check(db.probableEndPresentationDao().recordAutomaticEnd(p.id, result.endedAtMillis) == 1)
                    true
                } } } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { false }
                if (committed) { withContext(NonCancellable) { finishEffects(claim.id) }; ProbableEndAutomaticOutcome.ENDED }
                else ProbableEndAutomaticOutcome.PENDING
            }
            is ProbableEndFinalization.TooEarly -> {
                try { db.withTransaction {
                    val p = db.probableEndPresentationDao().byId(claim.id)
                    if (p?.status == ProbableEndPresentationStatus.AUTO_END_CLAIMED.name &&
                        p.finalizationClaimedAtMillis == claim.finalizationClaimedAtMillis &&
                        p.serverWindowNotBeforeAtMillis == result.notBeforeAtMillis)
                        db.probableEndPresentationDao().releaseEarlyClaim(claim.id,
                            requireNotNull(claim.finalizationClaimedAtMillis))
                } } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { return ProbableEndAutomaticOutcome.PENDING }
                schedule(claim.id, result.notBeforeAtMillis)
                ProbableEndAutomaticOutcome.TOO_EARLY
            }
            ProbableEndFinalization.Blocked -> {
                try { db.withTransaction {
                    val p = db.probableEndPresentationDao().byId(claim.id)
                    if (p?.status == ProbableEndPresentationStatus.AUTO_END_CLAIMED.name &&
                        p.finalizationClaimedAtMillis == claim.finalizationClaimedAtMillis) {
                        val now = clock.nowMillis()
                        check(db.probableEndPresentationDao().invalidateAutomaticClaim(claim.id,
                            now, "AUTHORITATIVE_FINAL_GUARD_BLOCKED") == 1)
                        rebaseBlockedCandidate(p, now)
                    }
                } } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { return ProbableEndAutomaticOutcome.PENDING }
                try { onGuardBlocked(claim.journeyId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Optional inference retry remains bounded. */ }
                ProbableEndAutomaticOutcome.BLOCKED
            }
            ProbableEndFinalization.Unknown -> {
                schedule(claim.id, clock.nowMillis() + RETRY_MILLIS)
                ProbableEndAutomaticOutcome.PENDING
            }
        }
    }

    suspend fun restore() {
        try {
            db.probableEndPresentationDao().windowsNeedingRegistration().forEach { p ->
                if (p.serverWindowRegisteredAtMillis == null) schedule(p.id, clock.nowMillis())
                else schedule(p.id, maxOf(requireNotNull(p.confirmationDeadlineAtMillis),
                    requireNotNull(p.serverWindowNotBeforeAtMillis)))
            }
            db.probableEndPresentationDao().pendingAutomaticClaims().forEach { p ->
                schedule(p.id, clock.nowMillis())
            }
            db.probableEndPresentationDao().pendingAutomaticEffects().forEach { p ->
                finishEffects(p.id)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* Secondary work retries at next restoration. */ }
    }

    /** A terminal sync conflict is reconciled only through this exact claimed window. */
    suspend fun reconcileRemoteAutoEnded(journeyId: String, endedAtMillis: Long): Boolean {
        val claim = db.probableEndPresentationDao().pendingAutomaticClaims()
            .singleOrNull { it.journeyId == journeyId } ?: return false
        return gateway.finalize(journeyId, claim.id).let { result ->
            if (result !is ProbableEndFinalization.Ended || result.endedAtMillis != endedAtMillis)
                false else finalizeClaim(claim, result) == ProbableEndAutomaticOutcome.ENDED
        }
    }

    suspend fun acknowledgeResult(episodeId: String): Boolean = try { db.withTransaction {
        val p = db.probableEndPresentationDao().byId(episodeId) ?: return@withTransaction false
        if (p.status != ProbableEndPresentationStatus.AUTO_ENDED.name ||
            db.journeyDao().getById(p.journeyId)?.status != JourneyStatus.AUTO_ENDED) return@withTransaction false
        p.automaticResultAcknowledgedAtMillis != null ||
            db.probableEndPresentationDao().acknowledgeAutomaticResult(episodeId,
                maxOf(clock.nowMillis(), requireNotNull(p.automaticEndedAtMillis))) == 1
    } } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    private suspend fun finishEffects(episodeId: String) = effectsMutex.withLock {
        try {
            val p = db.probableEndPresentationDao().byId(episodeId) ?: return@withLock
            if (p.status != ProbableEndPresentationStatus.AUTO_ENDED.name ||
                p.automaticEffectsAppliedAtMillis != null ||
                db.journeyDao().getById(p.journeyId)?.status != JourneyStatus.AUTO_ENDED) return@withLock
            onTerminalEffects(p.journeyId)
            db.probableEndPresentationDao().markAutomaticEffectsApplied(episodeId,
                maxOf(clock.nowMillis(), requireNotNull(p.automaticEndedAtMillis)))
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* Durable effect marker remains pending for restoration. */ }
    }

    private suspend fun eligiblePresented(p: ProbableEndPresentationEntity): Boolean {
        p.validate()
        if (p.status != ProbableEndPresentationStatus.PRESENTED.name || p.liveSlot != 1 ||
            p.respondedAtMillis != null || p.confirmationDeadlineAtMillis == null ||
            p.deadlineStatus !in setOf(ProbableEndDeadlineStatus.ACTIVE.name,
                ProbableEndDeadlineStatus.EXPIRED_PENDING_FINALIZATION.name)) return false
        val j = db.journeyDao().getById(p.journeyId) ?: return false
        val candidate = db.probableEndDao().unclosed(p.journeyId).singleOrNull() ?: return false
        val state = db.probableEndDao().state(p.journeyId) ?: return false
        val continuity = db.probableEndGuardDao().continuity(p.candidateEpisodeId) ?: return false
        val binding = db.probableEndGuardDao().binding(p.journeyId) ?: return false
        return j.status == JourneyStatus.ACTIVE && j.activeSlot == 1 &&
            candidate.id == p.candidateEpisodeId && candidate.generation == p.generation &&
            candidate.lifecycle == LocalProbableEndLifecycle.CANDIDATE.name &&
            state.generation == p.generation &&
            continuity.status == ProbableEndContinuityStatus.CONTINUITY_PROVEN.name &&
            binding.status == ProbableEndGuardBindingStatus.BOUND.name &&
            binding.historyBaselineAtMillis == p.historyBaselineAtMillis &&
            binding.guardVersion == p.guardVersion
    }

    private suspend fun samePresented(current: ProbableEndPresentationEntity, snapshot: ProbableEndPresentationEntity) =
        current.id == snapshot.id && current.journeyId == snapshot.journeyId &&
            current.candidateEpisodeId == snapshot.candidateEpisodeId &&
            current.generation == snapshot.generation && current.revision == snapshot.revision &&
            eligiblePresented(current)

    /** A trustworthy server BLOCKED result kills the exact evidence even if its version did not advance. */
    private suspend fun rebaseBlockedCandidate(p: ProbableEndPresentationEntity, now: Long) {
        val dao = db.probableEndDao()
        val row = dao.state(p.journeyId) ?: return
        row.restore()
        val candidate = dao.unclosed(p.journeyId).singleOrNull() ?: return
        if (candidate.id != p.candidateEpisodeId || candidate.generation != p.generation ||
            row.generation != p.generation ||
            db.journeyDao().getById(p.journeyId)?.status != JourneyStatus.ACTIVE) return
        val tail = maxOf(row.consumedSequence,
            db.telemetryDao().getLatest(p.journeyId)?.sequence ?: row.consumedSequence)
        val evaluationAt = maxOf(now, row.lastEvaluationAtMillis)
        val latestPermitted = evaluationAt.coerceAtMost(Long.MAX_VALUE -
            ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS) + ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS
        val floorTime = maxOf(row.evidenceFloorEventTimeMillis, evaluationAt,
            dao.maximumObservedTime(p.journeyId, latestPermitted))
        dao.closeUnclosed(p.journeyId, LocalProbableEndLifecycle.INVALIDATED.name,
            evaluationAt, "AUTHORITATIVE_AUTO_END_BLOCKED")
        db.probableEndGuardDao().invalidate(p.journeyId)
        dao.save(row.copy(generation = Math.addExact(row.generation, 1),
            evidenceFloorSequence = tail, consumedSequence = tail,
            evidenceFloorEventTimeMillis = floorTime, proximity = null, exit = null,
            revision = Math.addExact(row.revision, 1), lastEvaluationAtMillis = evaluationAt))
        db.probableEndGuardDao().binding(p.journeyId)?.let { binding ->
            db.probableEndGuardDao().save(binding.copy(
                status = ProbableEndGuardBindingStatus.NEEDS_BASELINE.name,
                evidenceFloorSequence = tail, evidenceFloorEventTimeMillis = floorTime,
                generation = Math.addExact(row.generation, 1),
                revision = Math.addExact(binding.revision, 1)))
        }
    }

    private fun schedule(episodeId: String, at: Long) {
        try { scheduler.schedule(episodeId, at) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Room recovery retries on the next lifecycle opportunity. */ }
    }

    companion object { private const val RETRY_MILLIS = 5 * 60 * 1000L }
}
