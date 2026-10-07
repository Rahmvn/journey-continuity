package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.probableend.ProbableEndPolicy

enum class ProbableEndPresentationStatus { READY_TO_PRESENT, PRESENTED, INVALIDATED }

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
) {
    fun validate() {
        require(id.isNotBlank() && journeyId.isNotBlank() && candidateEpisodeId.isNotBlank() &&
            generation >= 0 && policyVersion == ProbableEndPolicy.VERSION && historyBaselineAtMillis > 0 &&
            guardVersion >= 0 && createdAtMillis > 0 && revision >= 0)
        when (ProbableEndPresentationStatus.valueOf(status)) {
            ProbableEndPresentationStatus.READY_TO_PRESENT -> require(liveSlot == 1 && presentedAtMillis == null && closedAtMillis == null && closureReason == null)
            ProbableEndPresentationStatus.PRESENTED -> require(liveSlot == 1 && presentedAtMillis != null && presentedAtMillis >= createdAtMillis && closedAtMillis == null && closureReason == null)
            ProbableEndPresentationStatus.INVALIDATED -> require(liveSlot == null && closedAtMillis != null && closedAtMillis >= createdAtMillis && !closureReason.isNullOrBlank())
        }
    }
    override fun toString() = "ProbableEndPresentationEntity(redacted)"
}
