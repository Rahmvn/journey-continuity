package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.degraded.FallbackBindingStatus

@Entity(
    tableName = "journey_fallback_bindings",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["journeyHandle"], unique = true)],
)
data class JourneyFallbackBindingEntity(
    @PrimaryKey val journeyId: String,
    val keyId: Long,
    val journeyHandle: ByteArray,
    val status: FallbackBindingStatus,
    val provisionedAt: Long,
    val revokedAt: Long?,
)
