package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.SyncPhase

@Entity(
    tableName = "journey_sync_states",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class JourneySyncStateEntity(
    @PrimaryKey val journeyId: String,
    val highestTelemetrySequenceSynced: Long = 0,
    val lastSuccessfulSyncAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val phase: SyncPhase = SyncPhase.PENDING,
    val lastError: String? = null,
    val permanentlyBlocked: Boolean = false,
    val changeVersion: Long = 1,
    val workRequested: Boolean = true,
)

fun JourneySyncStateEntity.toDomain() = JourneySyncState(
    journeyId = journeyId,
    highestTelemetrySequenceSynced = highestTelemetrySequenceSynced,
    lastSuccessfulSyncAt = lastSuccessfulSyncAt,
    lastAttemptAt = lastAttemptAt,
    phase = phase,
    lastError = lastError,
    permanentlyBlocked = permanentlyBlocked,
)
