package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.ProbableEndContinuityStatus
import com.journeycontinuity.app.data.local.ProbableEndGuardBindingStatus
import com.journeycontinuity.app.data.local.ProbableEndPresentationStatus
import com.journeycontinuity.app.data.local.ProbableEndDeadlineStatus
import com.journeycontinuity.app.domain.JourneyClock
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.probableend.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground-only request. The Room episode, rather than this returned ID, is the UI source of truth. */
class RoomProbableEndPresentationCoordinator(
    private val db: JourneyDatabase,
    private val guardStore: RoomProbableEndGuardStore,
    private val gateway: ProbableEndVerificationGuardGateway,
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
    private val deadlineScheduler: ProbableEndDeadlineScheduler = ProbableEndDeadlineScheduler { _, _ -> },
) {
    private val inFlight = Mutex()

    suspend fun recheckAndPromote(journeyId: String, candidateId: String): String? = inFlight.withLock {
        try {
            val request = guardStore.prepare(journeyId, GuardReconciliationTrigger.FOREGROUND_PRESENTATION)
                ?: return@withLock null
            if (request.continuity?.episodeId != candidateId) return@withLock null
            val read = try { gateway.read(journeyId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE) }
            val decision = guardStore.apply(request, read)
            if (decision != GuardReconciliationDecision.PROVE_CONTINUITY) return@withLock null
            db.probableEndPresentationDao().live(journeyId)?.takeIf {
                it.candidateEpisodeId == candidateId && it.generation == request.generation &&
                    it.historyBaselineAtMillis == request.binding?.historyBaselineAtMillis &&
                    it.guardVersion == request.binding?.guardVersion
            }?.id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null /* Optional inference never disrupts monitoring. */ }
    }

    /** Called only after the exact sheet has laid out in a resumed, focused Activity. */
    suspend fun recordForegroundOpportunity(journeyId: String, episodeId: String): Boolean = try {
        val recorded = db.withTransaction {
            val p = db.probableEndPresentationDao().byId(episodeId) ?: return@withTransaction false
            p.validate()
            if (p.journeyId != journeyId || p.liveSlot != 1) return@withTransaction false
            val journey = db.journeyDao().getById(journeyId) ?: return@withTransaction false
            val candidate = db.probableEndDao().unclosed(journeyId).singleOrNull() ?: return@withTransaction false
            val state = db.probableEndDao().state(journeyId) ?: return@withTransaction false
            val continuity = db.probableEndGuardDao().continuity(candidate.id) ?: return@withTransaction false
            val binding = db.probableEndGuardDao().binding(journeyId) ?: return@withTransaction false
            if (journey.status != JourneyStatus.ACTIVE || journey.activeSlot != 1 ||
                candidate.id != p.candidateEpisodeId || candidate.generation != p.generation ||
                state.generation != p.generation || continuity.status != ProbableEndContinuityStatus.CONTINUITY_PROVEN.name ||
                binding.status != ProbableEndGuardBindingStatus.BOUND.name ||
                binding.historyBaselineAtMillis != p.historyBaselineAtMillis || binding.guardVersion != p.guardVersion)
                return@withTransaction false
            if (p.status == ProbableEndPresentationStatus.PRESENTED.name) return@withTransaction true
            val now = clock.nowMillis()
            if (now < p.createdAtMillis) return@withTransaction false
            val deadline = Math.addExact(now, PROBABLE_END_WINDOW_MILLIS)
            db.probableEndPresentationDao().markPresented(episodeId, now, deadline) == 1
        }
        if (recorded) {
            // Room committed first. A lost scheduling call is recovered from Room on restart.
            try {
                db.probableEndPresentationDao().byId(episodeId)?.takeIf {
                    it.journeyId == journeyId && it.deadlineStatus == ProbableEndDeadlineStatus.ACTIVE.name
                }?.let { deadlineScheduler.schedule(it.deadlineIdentity(), clock.nowMillis()) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { /* Scheduling is secondary; Room remains authoritative. */ }
        }
        recorded
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }
}
