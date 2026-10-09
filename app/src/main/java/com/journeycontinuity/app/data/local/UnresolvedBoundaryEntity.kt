package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.BoundaryAdmission
import com.journeycontinuity.app.domain.BoundaryProof
import com.journeycontinuity.app.domain.BoundaryReceipt
import com.journeycontinuity.app.domain.boundaryProof

@Entity(tableName = "unresolved_boundaries", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["admissionId"], unique = true)])
data class UnresolvedBoundaryEntity(
    @PrimaryKey val journeyId: String,
    val admissionId: String, val admittedAtMillis: Long, val etaMillis: Long,
    val boundaryMillis: Long, val policyVersion: String,
    val observedOwnerId: String? = null, val serverAcceptedAtMillis: Long? = null,
    val serverObservedAtMillis: Long? = null, val eligibility: String? = null, val reason: String? = null,
    val verificationCaseId: String? = null, val openingEventId: Long? = null,
    val verifyingStartedAtMillis: Long? = null, val guardVersion: Long? = null,
    val historyBaselineAtMillis: Long? = null,
    val firstBoundaryProofAtMillis: Long? = null,
    val receiptElapsedRealtimeMillis: Long? = null, val receiptBootCount: Int? = null,
    val receiptInstallationId: String? = null, val revision: Long = 0,
    /** Sampling duration only. Not a bound on UTC clock error or a time proof. */
    val receiptElapsedSpanMillis: Long? = null,
    /** Unknown until a separately justified UTC error model exists. */
    val utcUncertaintyMillis: Long? = null,
) {
    fun admission() = BoundaryAdmission(journeyId, admissionId, admittedAtMillis, etaMillis, boundaryMillis, policyVersion)
    fun proof(): BoundaryProof = boundaryProof(admission(), firstBoundaryProofAtMillis)
    fun hasSameBootReceipt(current: BoundaryReceipt): Boolean {
        val elapsed = receiptElapsedRealtimeMillis ?: return false
        val installation = receiptInstallationId ?: return false
        return BoundaryReceipt(elapsed, receiptBootCount, installation).sameBoot(current)
    }
    override fun toString() = "UnresolvedBoundaryEntity(journeyId=$journeyId, revision=$revision)"
}
