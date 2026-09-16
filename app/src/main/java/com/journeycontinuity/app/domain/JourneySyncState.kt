package com.journeycontinuity.app.domain

data class JourneySyncState(
    val journeyId: String,
    val highestTelemetrySequenceSynced: Long,
    val lastSuccessfulSyncAt: Long?,
    val lastAttemptAt: Long?,
    val phase: SyncPhase,
    val lastError: String?,
    val permanentlyBlocked: Boolean,
)

enum class SyncPhase {
    PENDING,
    SYNCING,
    IDLE,
    ERROR,
}
