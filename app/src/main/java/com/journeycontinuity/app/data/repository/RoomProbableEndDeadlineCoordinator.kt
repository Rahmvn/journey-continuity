package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.ProbableEndDeadlineStatus
import com.journeycontinuity.app.data.local.ProbableEndPresentationEntity
import com.journeycontinuity.app.data.local.ProbableEndPresentationStatus
import com.journeycontinuity.app.data.local.LocalProbableEndLifecycle
import com.journeycontinuity.app.domain.JourneyClock
import com.journeycontinuity.app.domain.JourneyStatus
import kotlinx.coroutines.CancellationException

const val PROBABLE_END_WINDOW_MILLIS = 30 * 60 * 1000L

/** Exact persisted snapshot for a scheduling wake-up. It has no terminal authority. */
data class ProbableEndDeadlineIdentity(
    val journeyId: String,
    val episodeId: String,
    val candidateId: String,
    val generation: Long,
    val revision: Long,
    val deadlineAtMillis: Long,
) {
    override fun toString() = "ProbableEndDeadlineIdentity(redacted)"
}

fun ProbableEndPresentationEntity.deadlineIdentity() = ProbableEndDeadlineIdentity(
    journeyId, id, candidateEpisodeId, generation, revision, requireNotNull(confirmationDeadlineAtMillis))

fun interface ProbableEndDeadlineScheduler {
    fun schedule(identity: ProbableEndDeadlineIdentity, nowMillis: Long)
}

enum class ProbableEndExpiryOutcome { EXPIRED_PENDING_FINALIZATION, TOO_EARLY, OBSOLETE, UNAVAILABLE }

/** Local no-response window only. No Journey status write and no server call. */
class RoomProbableEndDeadlineCoordinator(
    private val db: JourneyDatabase,
    private val scheduler: ProbableEndDeadlineScheduler,
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
) {
    suspend fun expire(identity: ProbableEndDeadlineIdentity): ProbableEndExpiryOutcome = try {
        db.withTransaction {
            val p = db.probableEndPresentationDao().byId(identity.episodeId)
                ?: return@withTransaction ProbableEndExpiryOutcome.OBSOLETE
            p.validate()
            if (p.journeyId != identity.journeyId || p.candidateEpisodeId != identity.candidateId ||
                p.generation != identity.generation || p.revision != identity.revision ||
                p.status != ProbableEndPresentationStatus.PRESENTED.name || p.liveSlot != 1 ||
                p.deadlineStatus != ProbableEndDeadlineStatus.ACTIVE.name ||
                p.confirmationDeadlineAtMillis != identity.deadlineAtMillis ||
                db.journeyDao().getById(identity.journeyId)?.status != JourneyStatus.ACTIVE)
                return@withTransaction ProbableEndExpiryOutcome.OBSOLETE
            val candidate = db.probableEndDao().unclosed(identity.journeyId).singleOrNull()
                ?: return@withTransaction ProbableEndExpiryOutcome.OBSOLETE
            if (candidate.id != identity.candidateId || candidate.generation != identity.generation ||
                candidate.lifecycle != LocalProbableEndLifecycle.CANDIDATE.name ||
                db.probableEndDao().state(identity.journeyId)?.generation != identity.generation)
                return@withTransaction ProbableEndExpiryOutcome.OBSOLETE
            val now = clock.nowMillis()
            if (now < identity.deadlineAtMillis || now < requireNotNull(p.presentedAtMillis))
                return@withTransaction ProbableEndExpiryOutcome.TOO_EARLY
            if (db.probableEndPresentationDao().expireWindow(p.id, p.journeyId, p.candidateEpisodeId,
                    p.generation, p.revision, identity.deadlineAtMillis, now) == 1)
                ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION
            else ProbableEndExpiryOutcome.OBSOLETE
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { ProbableEndExpiryOutcome.UNAVAILABLE }

    /** Recover WorkManager loss without changing the absolute deadline. */
    suspend fun restore() {
        val active = try { db.probableEndPresentationDao().activeDeadlines() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return }
        active.forEach { p ->
            try { p.validate(); scheduler.schedule(p.deadlineIdentity(), clock.nowMillis()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A later restoration can retry. */ }
        }
    }
}
