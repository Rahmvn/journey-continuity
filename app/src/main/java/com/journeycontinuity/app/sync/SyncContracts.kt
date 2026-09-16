package com.journeycontinuity.app.sync

import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.TelemetryObservation

data class PendingSyncCandidate(
    val journeyId: String,
    val changeVersion: Long,
)

interface LocalSyncStore {
    suspend fun nextCandidate(): PendingSyncCandidate?
    suspend fun journey(journeyId: String): Journey?
    suspend fun checkpoint(journeyId: String): Long
    suspend fun telemetryAfter(
        journeyId: String,
        afterSequence: Long,
        limit: Int,
    ): List<TelemetryObservation>
    suspend fun markSyncing(journeyId: String, attemptedAt: Long)
    suspend fun advanceCheckpoint(journeyId: String, sequence: Long)
    suspend fun finishIfUnchanged(
        journeyId: String,
        expectedChangeVersion: Long,
        successfulAt: Long,
    ): Boolean
    suspend fun markFailure(journeyId: String, message: String, permanentlyBlocked: Boolean)
}

interface CloudSyncGateway {
    suspend fun authenticatedOwnerId(): String
    suspend fun upsertJourney(journey: Journey, ownerId: String)
    suspend fun upsertTelemetry(observations: List<TelemetryObservation>)
}

enum class SyncFailureKind {
    TRANSIENT,
    AUTHENTICATION,
    PERMANENT,
}

class CloudSyncException(
    val kind: SyncFailureKind,
    val safeMessage: String,
    val diagnosticSummary: String = safeMessage,
    cause: Throwable? = null,
) : Exception(safeMessage, cause)

sealed interface SyncRunResult {
    data object Success : SyncRunResult
    data object Retry : SyncRunResult
    data object PermanentFailure : SyncRunResult
}

fun interface SyncClock {
    fun nowMillis(): Long
}
