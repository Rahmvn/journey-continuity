package com.journeycontinuity.app.data.repository

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.domain.JourneyClock
import com.journeycontinuity.app.domain.probableend.*
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class LocalProbableEndRunStatus { IDLE, PROGRESSED, INELIGIBLE, UNAVAILABLE }
data class LocalProbableEndRun(val status: LocalProbableEndRunStatus, val processed: Int = 0, val hasMore: Boolean = false)

/** Secondary transactions: no remote guard, presentation, deadline or Journey terminal action. */
class RoomProbableEndStore(
    private val database: JourneyDatabase,
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
) {
    private val dao get() = database.probableEndDao()

    suspend fun resumeActive() {
        isolated {
            database.journeyDao().getActive()?.id?.let { resume(it) }
            LocalProbableEndRun(LocalProbableEndRunStatus.IDLE)
        }
    }

    suspend fun resume(journeyId: String) {
        do { val result = consumePending(journeyId) } while (result.hasMore)
    }

    suspend fun consumePending(journeyId: String): LocalProbableEndRun = isolated {
        val now = clock.nowMillis()
        require(now > 0)
        database.withTransaction {
            val journey = database.journeyDao().getById(journeyId)
                ?: return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.INELIGIBLE)
            val input = journey.probableEndInput()
            var row = try { dao.state(journeyId) }
            catch (_: NullPointerException) {
                dao.save(recoverAtTail(journey, null, now, "UNREADABLE_STATE"))
                return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.UNAVAILABLE)
            }
            val unclosed = dao.unclosed(journeyId)
            if (input.ineligibleReason != null || journey.activeSlot != 1) {
                if (row != null || unclosed.isNotEmpty()) dao.save(recoverAtTail(journey, row, now, "INELIGIBLE"))
                return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.INELIGIBLE)
            }
            val validState = row?.let {
                runCatching {
                    it.restore()
                    require(it.journeyStartedAtMillis == journey.startedAt)
                    // Telemetry is retained. An oversized/corrupt cursor must not silently skip
                    // every future observation while reporting IDLE forever.
                    val tail = database.telemetryDao().getLatest(journeyId)?.sequence ?: 0
                    require(it.consumedSequence <= tail)
                }.isSuccess
            } ?: (unclosed.isEmpty() && dao.highestGeneration(journeyId) < 0)
            val validEpisode = unclosed.size <= 1 && unclosed.all {
                runCatching {
                    it.evidence()
                    require(row != null && it.generation == row.generation &&
                        it.first.observedAtMillis >= journey.startedAt &&
                        it.final.telemetrySequence <= row.consumedSequence &&
                        it.final.observedAtMillis <= requireNotNull(row.lastObservation).observedAtMillis &&
                        it.evidenceFloorSequence == row.evidenceFloorSequence &&
                        it.evidenceFloorEventTimeMillis == row.evidenceFloorEventTimeMillis)
                }.isSuccess
            }
            if (!validState || !validEpisode || (row != null && now < row.lastEvaluationAtMillis)) {
                dao.save(recoverAtTail(journey, row, now, "CORRUPT_OR_AMBIGUOUS_STATE"))
                return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.UNAVAILABLE)
            }
            row = row ?: ProbableEndDetectorEntity(journeyId, journey.startedAt, lastEvaluationAtMillis = now)
            val pending = database.telemetryDao().getAfterSequence(journeyId, row.consumedSequence, BATCH_SIZE)
            if (pending.isEmpty()) return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.IDLE)
            var candidate = unclosed.singleOrNull()
            for (telemetry in pending) {
                var current = requireNotNull(row)
                if (telemetry.eventTime > 0 && telemetry.eventTime <= current.evidenceFloorEventTimeMillis) {
                    row = current.copy(consumedSequence = telemetry.sequence,
                        revision = Math.addExact(current.revision, 1), lastEvaluationAtMillis = now)
                    continue
                }
                val result = ProbableEndDetector.evaluate(input, telemetry.toDomain(), now, current.restore())
                current = current.withDetector(requireNotNull(result.state), now)
                when (result) {
                    is ProbableEndResult.QualifyingCandidate -> if (candidate == null && !current.requiresReliableExit) {
                        val evidence = result.evidence
                        val id = UUID.nameUUIDFromBytes("${current.generation}:${evidence.sequenceKey}"
                            .toByteArray(Charsets.UTF_8)).toString()
                        candidate = ProbableEndEpisodeEntity(id, journeyId, evidence.policyVersion,
                            createdAtMillis = now, generation = current.generation,
                            evidenceFloorSequence = current.evidenceFloorSequence,
                            evidenceFloorEventTimeMillis = current.evidenceFloorEventTimeMillis,
                            first = evidence.first, final = evidence.final, observationCount = evidence.observationCount,
                            finalDistanceMeters = evidence.finalDistanceMeters, finalAccuracyMeters = evidence.finalAccuracyMeters,
                            destinationSlot = evidence.destination.slot)
                        dao.insert(requireNotNull(candidate))
                        database.probableEndGuardDao().save(requireNotNull(candidate).continuity(
                            database.probableEndGuardDao().binding(journeyId)))
                    }
                    is ProbableEndResult.ReliableDeparture -> if (candidate != null || current.requiresReliableExit) {
                        dao.closeUnclosed(journeyId, LocalProbableEndLifecycle.INVALIDATED.name, now, "RELIABLE_DEPARTURE")
                        database.probableEndGuardDao().invalidate(journeyId)
                        database.probableEndPresentationDao().invalidate(journeyId, now, "RELIABLE_DEPARTURE")
                        current = rebase(current, current.consumedSequence, telemetry.eventTime, now, false)
                        candidate = null
                    }
                    else -> Unit
                }
                row = current
            }
            // Candidate creation/closure and cursor/streak commit together; telemetry committed earlier.
            dao.save(requireNotNull(row))
            LocalProbableEndRun(LocalProbableEndRunStatus.PROGRESSED, pending.size, pending.size == BATCH_SIZE)
        }
    }

    /** Persistence primitive only; no Traveller action or guard is wired to it in Slice 2. */
    internal suspend fun suppressCandidate(journeyId: String, episodeId: String, acceptedBoundaryMillis: Long) = isolated {
        val now = clock.nowMillis()
        require(now > 0 && acceptedBoundaryMillis > 0 && acceptedBoundaryMillis <= latestPermittedTime(now))
        database.withTransaction {
            val journey = database.journeyDao().getById(journeyId)
                ?: return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.INELIGIBLE)
            if (journey.status != com.journeycontinuity.app.domain.JourneyStatus.ACTIVE || journey.activeSlot != 1)
                return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.INELIGIBLE)
            val row = dao.state(journeyId) ?: return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.IDLE)
            row.restore()
            require(row.journeyStartedAtMillis == journey.startedAt && now >= row.lastEvaluationAtMillis)
            val live = dao.unclosed(journeyId).singleOrNull()
                ?: return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.IDLE)
            live.evidence()
            require(live.generation == row.generation && live.evidenceFloorSequence == row.evidenceFloorSequence &&
                live.evidenceFloorEventTimeMillis == row.evidenceFloorEventTimeMillis)
            if (live.id != episodeId) return@withTransaction LocalProbableEndRun(LocalProbableEndRunStatus.IDLE)
            val tail = database.telemetryDao().getLatest(journeyId)?.sequence ?: row.consumedSequence
            require(row.consumedSequence <= tail)
            dao.closeUnclosed(journeyId, LocalProbableEndLifecycle.SUPPRESSED.name, acceptedBoundaryMillis, "LOCAL_SUPPRESSION")
            database.probableEndGuardDao().invalidate(journeyId)
            database.probableEndPresentationDao().invalidate(journeyId, now, "LOCAL_SUPPRESSION")
            dao.save(rebase(row, maxOf(tail, row.consumedSequence), maxOf(acceptedBoundaryMillis,
                dao.maximumObservedTime(journeyId, latestPermittedTime(now))), now, true))
            LocalProbableEndRun(LocalProbableEndRunStatus.PROGRESSED)
        }
    }

    private suspend fun recoverAtTail(journey: JourneyEntity, row: ProbableEndDetectorEntity?, now: Long, reason: String): ProbableEndDetectorEntity {
        val tail = database.telemetryDao().getLatest(journey.id)?.sequence ?: 0
        val trusted = row?.takeIf {
            runCatching { it.restore(); require(it.journeyStartedAtMillis == journey.startedAt) }.isSuccess
        }
        dao.closeUnclosed(journey.id, LocalProbableEndLifecycle.INVALIDATED.name, now, reason)
        database.probableEndGuardDao().invalidate(journey.id)
        database.probableEndPresentationDao().invalidate(journey.id, now, reason)
        return ProbableEndDetectorEntity(journey.id, journey.startedAt,
            generation = Math.addExact(maxOf(row?.generation ?: -1, dao.highestGeneration(journey.id), 0), 1),
            // Quarantine the existing prefix, not future arrivals. Rejected far-future telemetry
            // must not poison the event floor. A valid prior floor survives a clock rollback.
            evidenceFloorSequence = tail, evidenceFloorEventTimeMillis = maxOf(now,
                dao.maximumObservedTime(journey.id, latestPermittedTime(now)), trusted?.evidenceFloorEventTimeMillis ?: 0),
            consumedSequence = tail, requiresReliableExit = true,
            lastEvaluationAtMillis = maxOf(now, trusted?.lastEvaluationAtMillis ?: 0))
    }

    private fun rebase(row: ProbableEndDetectorEntity, sequence: Long, observedBoundary: Long, now: Long, requireExit: Boolean) = row.copy(
        generation = Math.addExact(row.generation, 1), evidenceFloorSequence = maxOf(row.evidenceFloorSequence, sequence),
        evidenceFloorEventTimeMillis = maxOf(row.evidenceFloorEventTimeMillis, observedBoundary, row.lastObservation?.observedAtMillis ?: 0),
        consumedSequence = maxOf(row.consumedSequence, sequence), proximity = null, exit = null,
        requiresReliableExit = requireExit, revision = Math.addExact(row.revision, 1), lastEvaluationAtMillis = now,
    )

    private suspend fun isolated(block: suspend () -> LocalProbableEndRun): LocalProbableEndRun = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LocalProbableEndRun(LocalProbableEndRunStatus.UNAVAILABLE) }

    private fun latestPermittedTime(now: Long) = now.coerceAtMost(Long.MAX_VALUE -
        ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS) + ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS

    companion object { private const val BATCH_SIZE = 128 }
}
