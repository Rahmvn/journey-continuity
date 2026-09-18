package com.journeycontinuity.app.domain

enum class CloudMonitoringPhase {
    EVIDENCE_FRESH,
    VERIFYING,
    CLOSED,
}

data class CloudMonitoringState(
    val journeyId: String,
    val lastAllocatedHeartbeatSequence: Long,
    val latestCloudHeartbeatSequence: Long,
    val lastHeartbeatAttemptAt: Long?,
    val lastSuccessfulHeartbeatAt: Long?,
    val phase: CloudMonitoringPhase?,
    val lastCloudContactAt: Long?,
    val lastError: String?,
)

data class DeviceHeartbeat(
    val journeyId: String,
    val sequence: Long,
    val clientSentAt: Long,
    val batteryPercent: Int?,
    val isCharging: Boolean?,
    val connectivity: ConnectivityState,
    val latestTelemetrySequence: Long,
)
