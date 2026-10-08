package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.probableend.ProbableEndPolicy

enum class ProbableEndPresentationStatus {
    READY_TO_PRESENT, PRESENTED, INVALIDATED, RESPONDED_COMPLETED, RESPONDED_KEEP_MONITORING,
}

/** A Traveller opportunity, distinct from the system-inference evidence candidate. */
@Entity(
    tableName = "probable_end_presentations",
    foreignKeys = [
        ForeignKey(entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = ProbableEndEpisodeEntity::class, parentColumns = ["id"], childColumns = ["candidateEpisodeId"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [Index("candidateEpisodeId", unique = true), Index(value = ["journeyId", "liveSlot"], unique = true)],
)
data class ProbableEndPresentationEntity(
    @PrimaryKey val id: String,
    val journeyId: String,
    val candidateEpisodeId: String,
    val generation: Long,
    val policyVersion: String,
    val historyBaselineAtMillis: Long,
    val guardVersion: Long,
    val status: String = ProbableEndPresentationStatus.READY_TO_PRESENT.name,
    val liveSlot: Int? = 1,
    val createdAtMillis: Long,
    val presentedAtMillis: Long? = null,
    val closedAtMillis: Long? = null,
    val closureReason: String? = null,
    val revision: Long = 0,
    val respondedAtMillis: Long? = null,
    val resultAcknowledgedAtMillis: Long? = null,
    val completionEffectsAppliedAtMillis: Long? = null,
) {
    fun validate() {
        require(id.isNotBlank() && journeyId.isNotBlank() && candidateEpisodeId.isNotBlank() &&
            generation >= 0 && policyVersion == ProbableEndPolicy.VERSION && historyBaselineAtMillis > 0 &&
            guardVersion >= 0 && createdAtMillis > 0 && revision >= 0)
        when (ProbableEndPresentationStatus.valueOf(status)) {
            ProbableEndPresentationStatus.READY_TO_PRESENT -> require(liveSlot == 1 && presentedAtMillis == null && closedAtMillis == null && closureReason == null && respondedAtMillis == null && resultAcknowledgedAtMillis == null && completionEffectsAppliedAtMillis == null)
            ProbableEndPresentationStatus.PRESENTED -> require(liveSlot == 1 && presentedAtMillis != null && presentedAtMillis >= createdAtMillis && closedAtMillis == null && closureReason == null && respondedAtMillis == null && resultAcknowledgedAtMillis == null && completionEffectsAppliedAtMillis == null)
            ProbableEndPresentationStatus.INVALIDATED -> require(liveSlot == null && closedAtMillis != null && closedAtMillis >= createdAtMillis && !closureReason.isNullOrBlank() && respondedAtMillis == null && resultAcknowledgedAtMillis == null && completionEffectsAppliedAtMillis == null)
            ProbableEndPresentationStatus.RESPONDED_COMPLETED -> require(liveSlot == null && presentedAtMillis != null && respondedAtMillis != null && respondedAtMillis >= presentedAtMillis && closedAtMillis == respondedAtMillis && closureReason == "TRAVELLER_RESPONSE" && (resultAcknowledgedAtMillis == null || resultAcknowledgedAtMillis >= respondedAtMillis) && (completionEffectsAppliedAtMillis == null || completionEffectsAppliedAtMillis >= respondedAtMillis))
            ProbableEndPresentationStatus.RESPONDED_KEEP_MONITORING -> require(liveSlot == null && presentedAtMillis != null && respondedAtMillis != null && respondedAtMillis >= presentedAtMillis && closedAtMillis == respondedAtMillis && closureReason == "TRAVELLER_RESPONSE" && resultAcknowledgedAtMillis == null && completionEffectsAppliedAtMillis == null)
        }
    }
    override fun toString() = "ProbableEndPresentationEntity(redacted)"
}
