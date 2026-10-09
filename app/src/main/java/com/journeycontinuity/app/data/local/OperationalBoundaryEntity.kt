package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.BoundaryReceipt
import com.journeycontinuity.app.domain.OperationalBoundaryState

/** No historical rows are synthesized. A pause retains the Journey ACTIVE slot. */
@Entity(tableName = "journey_operational_boundaries", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"],
    onDelete = ForeignKey.CASCADE,
)])
data class OperationalBoundaryEntity(
    @PrimaryKey val journeyId: String,
    val admissionId: String,
    val state: String = OperationalBoundaryState.MONITORING.name,
    val pauseReason: String? = null,
    val lastAssessment: String? = null,
    val anchorElapsedRealtimeMillis: Long? = null,
    val anchorBootCount: Int? = null,
    val anchorInstallationId: String? = null,
    val pausedAtElapsedRealtimeMillis: Long? = null,
    val revision: Long = 0,
) {
    fun anchor(): BoundaryReceipt? {
        val elapsed = anchorElapsedRealtimeMillis ?: return null
        val installation = anchorInstallationId ?: return null
        return BoundaryReceipt(elapsed, anchorBootCount, installation)
    }

    override fun toString() = "OperationalBoundaryEntity(journeyId=$journeyId, state=$state, revision=$revision)"
}
