package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Room's single writer orders explicit intent against an unresolved claim, without clock ordering. */
@Entity(tableName = "journey_terminal_reconciliations", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"],
    onDelete = ForeignKey.CASCADE,
)])
data class JourneyTerminalReconciliationEntity(
    @PrimaryKey val journeyId: String,
    val explicitActionId: String? = null,
    val explicitAction: String? = null,
    val explicitAtMillis: Long? = null,
    val explicitState: String? = null,
    val explicitOwnerId: String? = null,
    val claimJson: String? = null,
    val claimState: String? = null,
    val lastOutcome: String? = null,
    val retryAtMillis: Long? = null,
    val effectsPending: Boolean = false,
    val claimId: String? = null,
    val claimDispatch: String? = null,
    val explicitDispatch: String? = null,
    val revision: Long = 0,
) {
    override fun toString() = "JourneyTerminalReconciliationEntity(journeyId=$journeyId, outcome=$lastOutcome)"
}

/** Full immutable owner-RPC facts. No local timer or claim may insert terminal provenance. */
@Entity(tableName = "journey_incident_finalizations", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"],
    onDelete = ForeignKey.CASCADE,
)])
data class JourneyIncidentFinalizationEntity(
    @PrimaryKey val journeyId: String,
    val provenanceJson: String,
    val resultAcknowledgedAtMillis: Long? = null,
) {
    override fun toString() = "JourneyIncidentFinalizationEntity(journeyId=$journeyId)"
}

/** Exact Section 09 owner-read facts; does not create any local presentation history. */
@Entity(tableName = "journey_auto_end_finalizations", foreignKeys = [ForeignKey(
    entity = JourneyEntity::class, parentColumns = ["id"], childColumns = ["journeyId"],
    onDelete = ForeignKey.CASCADE,
)])
data class JourneyAutoEndFinalizationEntity(
    @PrimaryKey val journeyId: String,
    val provenanceJson: String,
) {
    override fun toString() = "JourneyAutoEndFinalizationEntity(journeyId=$journeyId)"
}
