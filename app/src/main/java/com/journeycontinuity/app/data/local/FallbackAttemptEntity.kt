package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.degraded.FallbackEnvelopeV1
import com.journeycontinuity.app.degraded.FallbackTransportState
import com.journeycontinuity.app.degraded.FallbackTransportOutcome

@Entity(
    tableName = "fallback_attempts",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["journeyId", "envelopeSequence"], unique = true),
        Index(
            value = ["journeyId", "degradationEpisodeId", "telemetrySequence"],
            unique = true,
        ),
        Index(value = ["journeyId", "transportState"]),
        Index(value = ["transportState", "nextRetryAt"]),
        Index(value = ["journeyId", "nonce"], unique = true),
    ],
)
data class FallbackAttemptEntity(
    @PrimaryKey(autoGenerate = true) val localAttemptId: Long = 0,
    val journeyId: String,
    val degradationEpisodeId: Long,
    val envelopeSequence: Long,
    val telemetrySequence: Long,
    val observationEventTime: Long,
    val eventType: FallbackEnvelopeV1.EventType,
    val protectedPayloadText: String,
    val nonce: ByteArray,
    val payloadSha256: ByteArray,
    val allocatedAt: Long,
    val transportState: FallbackTransportState = FallbackTransportState.ALLOCATED,
    val transportAttemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val terminalAt: Long? = null,
    val handoffGeneration: Int = 0,
    val handoffStartedAt: Long? = null,
    val nextRetryAt: Long? = null,
    val lastTransportOutcome: FallbackTransportOutcome? = null,
    val lastTransportResultCode: Int? = null,
    val uncertainSince: Long? = null,
)
