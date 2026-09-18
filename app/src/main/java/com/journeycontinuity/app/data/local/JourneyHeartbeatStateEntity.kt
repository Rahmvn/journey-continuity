package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.CloudMonitoringState

@Entity(
    tableName = "journey_heartbeat_states",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class JourneyHeartbeatStateEntity(
    @PrimaryKey val journeyId: String,
    val lastAllocatedHeartbeatSequence: Long = 0,
    val latestCloudHeartbeatSequence: Long = 0,
    val lastHeartbeatAttemptAt: Long? = null,
    val lastSuccessfulHeartbeatAt: Long? = null,
    val monitoringPhase: CloudMonitoringPhase? = null,
    val lastCloudContactAt: Long? = null,
    val lastError: String? = null,
)

fun JourneyHeartbeatStateEntity.toDomain() = CloudMonitoringState(
    journeyId = journeyId,
    lastAllocatedHeartbeatSequence = lastAllocatedHeartbeatSequence,
    latestCloudHeartbeatSequence = latestCloudHeartbeatSequence,
    lastHeartbeatAttemptAt = lastHeartbeatAttemptAt,
    lastSuccessfulHeartbeatAt = lastSuccessfulHeartbeatAt,
    phase = monitoringPhase,
    lastCloudContactAt = lastCloudContactAt,
    lastError = lastError,
)
