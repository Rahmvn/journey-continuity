package com.journeycontinuity.app.domain

data class TelemetryObservation(
    val id: Long,
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

enum class ConnectivityState {
    NONE,
    CELLULAR,
    WIFI,
    OTHER,
    UNKNOWN,
}

data class TelemetrySample(
    val journeyId: String,
    val eventTime: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val batteryPercent: Int?,
    val isCharging: Boolean?,
    val connectivity: ConnectivityState,
)

data class TelemetrySummary(
    val count: Long = 0,
    val latest: TelemetryObservation? = null,
)
