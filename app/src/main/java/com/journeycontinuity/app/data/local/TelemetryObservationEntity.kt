package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.domain.TelemetrySample

@Entity(
    tableName = "telemetry_observations",
    foreignKeys = [
        ForeignKey(
            entity = JourneyEntity::class,
            parentColumns = ["id"],
            childColumns = ["journeyId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["journeyId"]),
        Index(value = ["journeyId", "sequence"], unique = true),
    ],
)
data class TelemetryObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val journeyId: String,
    val sequence: Long,
    val eventTime: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val batteryPercent: Int?,
    val isCharging: Boolean?,
    val connectivity: ConnectivityState,
)

fun TelemetryObservationEntity.toDomain() = TelemetryObservation(
    id = id,
    journeyId = journeyId,
    sequence = sequence,
    eventTime = eventTime,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    batteryPercent = batteryPercent,
    isCharging = isCharging,
    connectivity = connectivity,
)

fun TelemetrySample.toEntity(sequence: Long) = TelemetryObservationEntity(
    journeyId = journeyId,
    sequence = sequence,
    eventTime = eventTime,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    batteryPercent = batteryPercent,
    isCharging = isCharging,
    connectivity = connectivity,
)
