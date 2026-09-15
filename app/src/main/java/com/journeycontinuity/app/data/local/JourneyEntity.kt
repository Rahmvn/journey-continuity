package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus

@Entity(
    tableName = "journeys",
    indices = [Index(value = ["activeSlot"], unique = true)],
)
data class JourneyEntity(
    @PrimaryKey val id: String,
    val destination: String,
    val expectedArrivalAt: Long,
    val startedAt: Long,
    val status: JourneyStatus,
    val completedAt: Long?,
    // SQLite permits many NULLs in a unique index, but only one ACTIVE row can hold 1.
    val activeSlot: Int?,
)

fun JourneyEntity.toDomain() = Journey(
    id = id,
    destination = destination,
    expectedArrivalAt = expectedArrivalAt,
    startedAt = startedAt,
    status = status,
    completedAt = completedAt,
)

fun Journey.toEntity() = JourneyEntity(
    id = id,
    destination = destination,
    expectedArrivalAt = expectedArrivalAt,
    startedAt = startedAt,
    status = status,
    completedAt = completedAt,
    activeSlot = if (status == JourneyStatus.ACTIVE) 1 else null,
)
