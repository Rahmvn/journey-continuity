package com.journeycontinuity.app.sync

import kotlinx.coroutines.CancellationException
import com.journeycontinuity.app.domain.JourneyStatus

object SyncConfiguration {
    const val TELEMETRY_BATCH_SIZE = 50
}

class ReliableSyncEngine(
    private val local: LocalSyncStore,
    private val remote: CloudSyncGateway,
    private val clock: SyncClock = SyncClock(System::currentTimeMillis),
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
    private val attemptObserver: suspend (journeyId: String) -> Unit = {},
    private val provisioningObserver: suspend (journeyId: String) -> Unit = {},
) {
    suspend fun synchronize(): SyncRunResult {
        var ownerId: String? = null
        while (true) {
            val candidate = local.nextCandidate() ?: run {
                logger.info("No pending cloud synchronization backlog")
                return SyncRunResult.Success
            }
            logger.info("Starting synchronization candidate")
            local.markSyncing(candidate.journeyId, clock.nowMillis())
            try {
                val authenticatedOwner = ownerId ?: remote.authenticatedOwnerId().also {
                    ownerId = it
                }
                synchronizeCandidate(candidate, authenticatedOwner)
            } catch (error: CloudSyncException) {
                val permanent = error.kind == SyncFailureKind.PERMANENT
                local.markFailure(candidate.journeyId, error.safeMessage, permanent)
                // A retryable attempt is degradation evidence only after traveller
                // authentication succeeded. Auth/configuration/programming failures
                // must not be reclassified as loss of mobile internet.
                if (error.kind == SyncFailureKind.TRANSIENT && ownerId != null) {
                    runCatching { attemptObserver(candidate.journeyId) }
                        .onFailure { logger.warning("Retryable sync observation could not be recorded") }
                }
                logger.warning(
                    "Synchronization candidate failed; retryable=${!permanent}; " +
                        error.diagnosticSummary,
                )
                return if (permanent) SyncRunResult.PermanentFailure else SyncRunResult.Retry
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                local.markFailure(
                    candidate.journeyId,
                    "Synchronization failed because of an unexpected application error.",
                    permanentlyBlocked = true,
                )
                logger.warning("Synchronization candidate failed: unexpected application error.")
                return SyncRunResult.PermanentFailure
            }
        }
    }

    private suspend fun synchronizeCandidate(
        candidate: PendingSyncCandidate,
        ownerId: String,
    ) {
        val initialJourney = local.journey(candidate.journeyId)
            ?: throw CloudSyncException(
                SyncFailureKind.PERMANENT,
                "Local synchronization state references a missing Journey.",
            )
        remote.upsertJourney(initialJourney, ownerId)
        if (initialJourney.status == JourneyStatus.ACTIVE) {
            try {
                provisioningObserver(initialJourney.id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warning("Fallback provisioning attempt could not complete; cloud sync will continue.")
            }
        }

        var checkpoint = local.checkpoint(candidate.journeyId)
        while (true) {
            val batch = local.telemetryAfter(
                journeyId = candidate.journeyId,
                afterSequence = checkpoint,
                limit = SyncConfiguration.TELEMETRY_BATCH_SIZE,
            )
            if (batch.isEmpty()) break
            if (batch.zipWithNext().any { (first, second) -> first.sequence >= second.sequence }) {
                throw CloudSyncException(
                    SyncFailureKind.PERMANENT,
                    "Local telemetry backlog is not in ascending sequence order.",
                )
            }
            remote.upsertTelemetry(batch)
            checkpoint = batch.last().sequence
            local.advanceCheckpoint(candidate.journeyId, checkpoint)
            logger.info("Local checkpoint advanced through sequence $checkpoint")
        }

        // Re-read and upsert after telemetry so an offline COMPLETED transition cannot
        // remain hidden behind an earlier ACTIVE payload.
        val latestJourney = local.journey(candidate.journeyId)
            ?: throw CloudSyncException(
                SyncFailureKind.PERMANENT,
                "Local Journey disappeared during synchronization.",
            )
        remote.upsertJourney(latestJourney, ownerId)

        val finished = local.finishIfUnchanged(
            journeyId = candidate.journeyId,
            expectedChangeVersion = candidate.changeVersion,
            successfulAt = clock.nowMillis(),
        )
        logger.info("Synchronization candidate completed; local state unchanged: $finished")
        // If local evidence changed, finishIfUnchanged deliberately leaves workRequested
        // set. The outer loop immediately reads the new change version and drains it.
    }
}
