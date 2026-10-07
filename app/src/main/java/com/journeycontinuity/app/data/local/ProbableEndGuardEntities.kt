package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.probableend.*

enum class ProbableEndGuardBindingStatus { BOUND, BLOCKED, NEEDS_BASELINE }
enum class ProbableEndContinuityStatus { UNBOUND, PENDING_GUARD, CONTINUITY_PROVEN, INVALIDATED }

private inline fun <reified T : Enum<T>> boundedEnum(value: String): T =
    requireNotNull(enumValues<T>().firstOrNull { it.name == value })

@Entity(tableName = "probable_end_guard_bindings", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.NO_ACTION)])
data class ProbableEndGuardBindingEntity(
    @PrimaryKey val journeyId: String,
    val status: String,
    val historyBaselineAtMillis: Long,
    val guardVersion: Long,
    val establishedServerAtMillis: Long,
    val evidenceFloorSequence: Long,
    val evidenceFloorEventTimeMillis: Long,
    val generation: Long,
    val revision: Long,
    val lastServerEvaluatedAtMillis: Long,
    val lastEvidenceNotBeforeMillis: Long,
    val lastFreshUntilMillis: Long?,
    val lastDecision: String,
    val lastVerification: String,
    val lastReason: String,
    val lastAttemptAtMillis: Long? = null,
) {
    fun receipt(): AuthoritativeVerificationGuard {
        val bindingStatus = boundedEnum<ProbableEndGuardBindingStatus>(status)
        require(journeyId.isNotBlank() && guardVersion >= 0 && revision >= 0 && generation >= 0 &&
            evidenceFloorSequence >= 0 && evidenceFloorEventTimeMillis >= 0 &&
            establishedServerAtMillis > 0 && establishedServerAtMillis <= lastServerEvaluatedAtMillis)
        if (bindingStatus != ProbableEndGuardBindingStatus.NEEDS_BASELINE)
            require(evidenceFloorEventTimeMillis >= establishedServerAtMillis)
        val g = AuthoritativeVerificationGuard(journeyId, lastReason != VerificationGuardReason.JOURNEY_NOT_ACTIVE.name,
            boundedEnum<VerificationGuardDecision>(lastDecision), boundedEnum<VerificationKnowledge>(lastVerification),
            boundedEnum<VerificationGuardReason>(lastReason), guardVersion, historyBaselineAtMillis,
            lastEvidenceNotBeforeMillis, lastServerEvaluatedAtMillis, lastFreshUntilMillis)
        require(ProbableEndGuardReconciliation.decide(journeyId, null, false, false, false,
            VerificationGuardRead.Available(g)) != GuardReconciliationDecision.UNKNOWN)
        require(bindingStatus == ProbableEndGuardBindingStatus.NEEDS_BASELINE || bindingStatus ==
            if (g.decision == VerificationGuardDecision.CLEAR) ProbableEndGuardBindingStatus.BOUND else ProbableEndGuardBindingStatus.BLOCKED)
        return g
    }
    override fun toString() = "ProbableEndGuardBindingEntity(redacted)"
}

@Entity(tableName = "probable_end_candidate_continuity", foreignKeys = [
    ForeignKey(entity = ProbableEndEpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION),
    ForeignKey(entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.NO_ACTION),
], indices = [Index("journeyId")])
data class ProbableEndCandidateContinuityEntity(
    @PrimaryKey val episodeId: String,
    val journeyId: String,
    val generation: Long,
    val status: String,
    val attachedHistoryBaselineAtMillis: Long?,
    val attachedGuardVersion: Long?,
    val requestRevision: Long = 0,
    val attemptedAtMillis: Long? = null,
    val checkedHistoryBaselineAtMillis: Long? = null,
    val checkedGuardVersion: Long? = null,
    val checkedServerAtMillis: Long? = null,
    val lastUnavailableReason: String? = null,
) {
    fun validate() {
        val state = boundedEnum<ProbableEndContinuityStatus>(status)
        require(episodeId.isNotBlank() && journeyId.isNotBlank() && generation >= 0 && requestRevision >= 0)
        require((attachedHistoryBaselineAtMillis == null) == (attachedGuardVersion == null))
        require(attachedGuardVersion == null || (attachedGuardVersion >= 0 && requireNotNull(attachedHistoryBaselineAtMillis) > 0))
        require(if (requestRevision == 0L) attemptedAtMillis == null else attemptedAtMillis != null && attemptedAtMillis > 0)
        require(listOf(checkedHistoryBaselineAtMillis, checkedGuardVersion, checkedServerAtMillis).all { it == null } ||
            checkedHistoryBaselineAtMillis != null && checkedHistoryBaselineAtMillis > 0 &&
                checkedGuardVersion != null && checkedGuardVersion >= 0 && checkedServerAtMillis != null && checkedServerAtMillis > 0)
        if (state == ProbableEndContinuityStatus.UNBOUND) require(attachedGuardVersion == null)
        if (state in setOf(ProbableEndContinuityStatus.PENDING_GUARD, ProbableEndContinuityStatus.CONTINUITY_PROVEN))
            require(attachedGuardVersion != null)
        if (state == ProbableEndContinuityStatus.CONTINUITY_PROVEN) require(checkedGuardVersion == attachedGuardVersion &&
            checkedHistoryBaselineAtMillis == attachedHistoryBaselineAtMillis && checkedServerAtMillis != null)
        if (lastUnavailableReason != null) boundedEnum<VerificationGuardUnavailableReason>(lastUnavailableReason)
    }
    override fun toString() = "ProbableEndCandidateContinuityEntity(redacted)"
}

internal fun ProbableEndEpisodeEntity.continuity(binding: ProbableEndGuardBindingEntity?): ProbableEndCandidateContinuityEntity {
    val usable = binding?.takeIf { runCatching { it.receipt() }.isSuccess &&
        it.journeyId == journeyId && it.status == ProbableEndGuardBindingStatus.BOUND.name &&
        generation >= it.generation && first.telemetrySequence > it.evidenceFloorSequence &&
        first.observedAtMillis > it.evidenceFloorEventTimeMillis }
    return ProbableEndCandidateContinuityEntity(id, journeyId, generation,
        if (usable == null) ProbableEndContinuityStatus.UNBOUND.name else ProbableEndContinuityStatus.PENDING_GUARD.name,
        usable?.historyBaselineAtMillis, usable?.guardVersion)
}
