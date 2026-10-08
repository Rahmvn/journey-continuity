package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.LocalProbableEndLifecycle
import com.journeycontinuity.app.data.local.ProbableEndContinuityStatus
import com.journeycontinuity.app.data.local.ProbableEndGuardBindingStatus
import com.journeycontinuity.app.data.local.ProbableEndPresentationEntity
import com.journeycontinuity.app.data.local.ProbableEndPresentationStatus
import com.journeycontinuity.app.domain.JourneyClock
import com.journeycontinuity.app.domain.JourneyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A snapshot from the rendered episode; never resolves a response against a generic active slot. */
data class ProbableEndResponseRequest(
    val journeyId: String,
    val presentationEpisodeId: String,
    val candidateEpisodeId: String,
    val generation: Long,
    val presentationRevision: Long,
) {
    override fun toString() = "ProbableEndResponseRequest(redacted)"
}

fun ProbableEndPresentationEntity.responseRequest() = ProbableEndResponseRequest(
    journeyId, id, candidateEpisodeId, generation, revision)

enum class ProbableEndTravellerResponse { JOURNEY_COMPLETED, KEEP_MONITORING }
enum class ProbableEndResponseOutcome { ACCEPTED, ALREADY_ACCEPTED, UNAVAILABLE }

/** Local human response, independent of the cloud guard and the future no-response deadline. */
class RoomProbableEndResponseCoordinator(
    private val db: JourneyDatabase,
    private val localDetector: RoomProbableEndStore,
    private val onExplicitCompletion: suspend (String, Long) -> Unit,
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
) {
    private val completionEffectsMutex = Mutex()
    suspend fun respond(request: ProbableEndResponseRequest,
        response: ProbableEndTravellerResponse): ProbableEndResponseOutcome {
        val outcome = try {
            db.withTransaction {
                val p = db.probableEndPresentationDao().byId(request.presentationEpisodeId)
                    ?: return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                p.validate()
                if (p.journeyId != request.journeyId || p.candidateEpisodeId != request.candidateEpisodeId ||
                    p.generation != request.generation) return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                val journey = db.journeyDao().getById(request.journeyId)
                    ?: return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                val targetStatus = when (response) {
                    ProbableEndTravellerResponse.JOURNEY_COMPLETED -> ProbableEndPresentationStatus.RESPONDED_COMPLETED
                    ProbableEndTravellerResponse.KEEP_MONITORING -> ProbableEndPresentationStatus.RESPONDED_KEEP_MONITORING
                }
                if (p.status == targetStatus.name) return@withTransaction if (
                    response == ProbableEndTravellerResponse.JOURNEY_COMPLETED && journey.status == JourneyStatus.COMPLETED ||
                    response == ProbableEndTravellerResponse.KEEP_MONITORING && journey.status == JourneyStatus.ACTIVE
                ) ProbableEndResponseOutcome.ALREADY_ACCEPTED else ProbableEndResponseOutcome.UNAVAILABLE
                if (p.status != ProbableEndPresentationStatus.PRESENTED.name || p.liveSlot != 1 ||
                    p.revision != request.presentationRevision || journey.status != JourneyStatus.ACTIVE ||
                    journey.activeSlot != 1) return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE

                val candidate = db.probableEndDao().unclosed(request.journeyId).singleOrNull()
                    ?: return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                val detector = db.probableEndDao().state(request.journeyId)
                    ?: return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                val continuity = db.probableEndGuardDao().continuity(candidate.id)
                    ?: return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                val binding = db.probableEndGuardDao().binding(request.journeyId)
                    ?: return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                if (candidate.id != p.candidateEpisodeId || candidate.generation != p.generation ||
                    candidate.lifecycle != LocalProbableEndLifecycle.CANDIDATE.name || candidate.liveSlot != 1 ||
                    detector.generation != p.generation ||
                    continuity.status != ProbableEndContinuityStatus.CONTINUITY_PROVEN.name ||
                    continuity.generation != p.generation ||
                    binding.status != ProbableEndGuardBindingStatus.BOUND.name ||
                    binding.historyBaselineAtMillis != p.historyBaselineAtMillis ||
                    binding.guardVersion != p.guardVersion) return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE

                val now = clock.nowMillis()
                if (now <= 0 || now < requireNotNull(p.presentedAtMillis))
                    return@withTransaction ProbableEndResponseOutcome.UNAVAILABLE
                check(db.probableEndPresentationDao().acceptResponse(p.id, p.journeyId,
                    p.candidateEpisodeId, p.generation, p.revision, targetStatus.name, now) == 1)
                when (response) {
                    ProbableEndTravellerResponse.JOURNEY_COMPLETED -> {
                        // The existing explicit completion transaction marks sync pending and
                        // closes live prompts. This responded row already vacated its live slot.
                        check(db.journeyDao().completeActive(now)?.id == request.journeyId)
                    }
                    ProbableEndTravellerResponse.KEEP_MONITORING -> {
                        check(localDetector.suppressCandidateInTransaction(request.journeyId,
                            request.candidateEpisodeId, now, now).status == LocalProbableEndRunStatus.PROGRESSED)
                    }
                }
                ProbableEndResponseOutcome.ACCEPTED
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { ProbableEndResponseOutcome.UNAVAILABLE }
        if (response == ProbableEndTravellerResponse.JOURNEY_COMPLETED &&
            outcome != ProbableEndResponseOutcome.UNAVAILABLE) withContext(NonCancellable) {
                finishCompletionEffects(request.presentationEpisodeId)
            }
        return outcome
    }

    /** The Journey/response commit survives a crash before service, fallback or sync wake. */
    suspend fun resumeCompletionEffects() {
        val pending = try { db.probableEndPresentationDao().pendingCompletionEffects() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return }
        pending.forEach { finishCompletionEffects(it.id) }
    }

    private suspend fun finishCompletionEffects(episodeId: String) {
        completionEffectsMutex.withLock {
            try {
                val p = db.probableEndPresentationDao().byId(episodeId) ?: return@withLock
                p.validate()
                if (p.status != ProbableEndPresentationStatus.RESPONDED_COMPLETED.name ||
                    p.completionEffectsAppliedAtMillis != null ||
                    db.journeyDao().getById(p.journeyId)?.status != JourneyStatus.COMPLETED) return@withLock
                onExplicitCompletion(p.journeyId, requireNotNull(p.respondedAtMillis))
                db.probableEndPresentationDao().markCompletionEffectsApplied(episodeId,
                    maxOf(clock.nowMillis(), requireNotNull(p.respondedAtMillis)))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { /* Durable pending work is retried on process start. */ }
        }
    }

    suspend fun acknowledgeCompletedResult(episodeId: String): Boolean = try {
        db.withTransaction {
            val p = db.probableEndPresentationDao().byId(episodeId) ?: return@withTransaction false
            if (p.status != ProbableEndPresentationStatus.RESPONDED_COMPLETED.name ||
                db.journeyDao().getById(p.journeyId)?.status != JourneyStatus.COMPLETED) return@withTransaction false
            if (p.resultAcknowledgedAtMillis != null) return@withTransaction true
            db.probableEndPresentationDao().acknowledgeCompletedResult(episodeId,
                maxOf(clock.nowMillis(), requireNotNull(p.respondedAtMillis))) == 1
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }
}
