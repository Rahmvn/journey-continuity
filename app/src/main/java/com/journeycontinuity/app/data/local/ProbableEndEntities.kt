package com.journeycontinuity.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.probableend.*

data class ProbableEndStreakColumns(
    val firstSequence: Long, val firstTime: Long, val firstFingerprint: String,
    val latestSequence: Long, val latestTime: Long, val latestFingerprint: String, val count: Int,
) {
    fun restore() = ProbableEndStreak(ObservationEvidenceReference(firstSequence, firstTime, firstFingerprint),
        ObservationEvidenceReference(latestSequence, latestTime, latestFingerprint), count)
    override fun toString() = "ProbableEndStreakColumns(redacted)"
}

internal fun ProbableEndStreak.columns() = ProbableEndStreakColumns(first.telemetrySequence,
    first.observedAtMillis, first.fingerprint, latest.telemetrySequence, latest.observedAtMillis, latest.fingerprint, count)

@Entity(tableName = "probable_end_detector_states", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"],
    onDelete = ForeignKey.NO_ACTION,
)])
data class ProbableEndDetectorEntity(
    @PrimaryKey val journeyId: String,
    val journeyStartedAtMillis: Long,
    val policyVersion: String = ProbableEndPolicy.VERSION,
    val generation: Long = 0,
    val evidenceFloorSequence: Long = 0,
    val evidenceFloorEventTimeMillis: Long = 0,
    val consumedSequence: Long = 0,
    @Embedded(prefix = "last_") val lastObservation: ObservationEvidenceReference? = null,
    @Embedded(prefix = "proximity_") val proximity: ProbableEndStreakColumns? = null,
    @Embedded(prefix = "exit_") val exit: ProbableEndStreakColumns? = null,
    val requiresReliableExit: Boolean = false,
    val revision: Long = 0,
    val lastEvaluationAtMillis: Long,
) {
    fun restore(): ProbableEndDetectorState {
        require(journeyId.isNotBlank() && journeyStartedAtMillis > 0 && policyVersion == ProbableEndPolicy.VERSION)
        require(generation >= 0 && revision >= 0 && evidenceFloorSequence >= 0 && evidenceFloorEventTimeMillis >= 0 &&
            consumedSequence >= evidenceFloorSequence && lastEvaluationAtMillis > 0)
        val latestPermittedTime = lastEvaluationAtMillis.coerceAtMost(Long.MAX_VALUE -
            ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS) + ProbableEndPolicy.MAX_FUTURE_SKEW_MILLIS
        require(evidenceFloorEventTimeMillis <= latestPermittedTime)
        lastObservation?.validateReference()
        require(lastObservation == null || (lastObservation.telemetrySequence <= consumedSequence &&
            lastObservation.observedAtMillis >= journeyStartedAtMillis &&
            lastObservation.observedAtMillis <= latestPermittedTime))
        require(proximity == null || exit == null)
        fun streak(columns: ProbableEndStreakColumns?): ProbableEndStreak? = columns?.restore()?.also {
            it.first.validateReference(); it.latest.validateReference()
            require(it.count > 0 && it.first.telemetrySequence > evidenceFloorSequence &&
                it.first.observedAtMillis > evidenceFloorEventTimeMillis &&
                it.first.observedAtMillis >= journeyStartedAtMillis &&
                it.first.telemetrySequence <= it.latest.telemetrySequence &&
                it.count.toLong() <= it.latest.telemetrySequence - it.first.telemetrySequence + 1 &&
                it.latest == lastObservation && it.observedSpanMillis >= 0 &&
                it.observedSpanMillis <= (it.count.toLong() - 1) * ProbableEndPolicy.MAX_OBSERVATION_GAP_MILLIS &&
                (it.count == 1 && it.first == it.latest || it.count > 1 && it.observedSpanMillis > 0))
        }
        return ProbableEndDetectorState(journeyId, journeyStartedAtMillis, DestinationEvidenceReference(journeyId),
            policyVersion, consumedSequence, lastObservation, streak(proximity), streak(exit))
    }

    internal fun withDetector(state: ProbableEndDetectorState, now: Long) = copy(
        consumedSequence = state.highestTelemetrySequence, lastObservation = state.latestAdvancingObservation,
        proximity = state.proximity?.columns(), exit = state.exit?.columns(),
        revision = Math.addExact(revision, 1), lastEvaluationAtMillis = now,
    )
    override fun toString() = "ProbableEndDetectorEntity(redacted)"
}

internal fun ObservationEvidenceReference.validateReference() {
    require(telemetrySequence > 0 && observedAtMillis > 0 && fingerprint.matches(Regex("[0-9a-f]{64}")))
}

enum class LocalProbableEndLifecycle { CANDIDATE, INVALIDATED, SUPPRESSED }

@Entity(tableName = "probable_end_episodes", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.NO_ACTION,
)], indices = [Index(value = ["journeyId", "generation"], unique = true),
    Index(value = ["journeyId", "liveSlot"], unique = true)])
data class ProbableEndEpisodeEntity(
    @PrimaryKey val id: String,
    val journeyId: String,
    val policyVersion: String,
    val evidenceClass: String = "SYSTEM_INFERENCE",
    val createdAtMillis: Long,
    val generation: Long,
    val evidenceFloorSequence: Long,
    val evidenceFloorEventTimeMillis: Long,
    val lifecycle: String = LocalProbableEndLifecycle.CANDIDATE.name,
    val liveSlot: Int? = 1,
    @Embedded(prefix = "first_") val first: ObservationEvidenceReference,
    @Embedded(prefix = "final_") val final: ObservationEvidenceReference,
    val observationCount: Int,
    val finalDistanceMeters: Double,
    val finalAccuracyMeters: Double,
    val destinationSlot: String,
    val closedAtMillis: Long? = null,
    val closureReason: String? = null,
) {
    fun evidence(): ProbableEndEvidenceSummary {
        first.validateReference(); final.validateReference()
        require(id.isNotBlank() && journeyId.isNotBlank() && policyVersion == ProbableEndPolicy.VERSION &&
            evidenceClass == "SYSTEM_INFERENCE" && createdAtMillis > 0 && generation >= 0 &&
            evidenceFloorSequence >= 0 && evidenceFloorEventTimeMillis >= 0 &&
            first.telemetrySequence > evidenceFloorSequence && first.observedAtMillis > evidenceFloorEventTimeMillis &&
            final.telemetrySequence >= first.telemetrySequence &&
            observationCount >= ProbableEndPolicy.MIN_PROXIMITY_OBSERVATIONS &&
            observationCount.toLong() <= final.telemetrySequence - first.telemetrySequence + 1 &&
            final.observedAtMillis - first.observedAtMillis >= ProbableEndPolicy.MIN_PROXIMITY_SPAN_MILLIS &&
            ProbableEndPolicy.isWithinProximity(finalDistanceMeters, finalAccuracyMeters) &&
            destinationSlot == DestinationEvidenceReference(journeyId).slot)
        require(LocalProbableEndLifecycle.entries.any { it.name == lifecycle })
        val status = LocalProbableEndLifecycle.valueOf(lifecycle)
        require(if (status == LocalProbableEndLifecycle.CANDIDATE) liveSlot == 1 && closedAtMillis == null && closureReason == null
            else liveSlot == null && closedAtMillis != null && closedAtMillis > 0 && closureReason != null)
        return ProbableEndEvidenceSummary(journeyId, policyVersion, DestinationEvidenceReference(journeyId, destinationSlot),
            ProbableEndEvidenceKind.PROXIMITY, first, final, observationCount,
            final.observedAtMillis - first.observedAtMillis, finalDistanceMeters, finalAccuracyMeters)
    }
    override fun toString() = "ProbableEndEpisodeEntity(redacted)"
}
