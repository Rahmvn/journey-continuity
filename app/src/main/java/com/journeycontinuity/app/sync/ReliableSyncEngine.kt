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
    private val reconcileCancelled: suspend (journeyId: String, endedAt: Long) -> Boolean = { _, _ -> false },
) {
    suspend fun synchronize(): SyncRunResult {
        var ownerId: String? = null
        // Runs even without a sync candidate: the server may have committed the stop
        // immediately before this process died, leaving a locally clean ACTIVE row.
        try {
            val active = local.activeJourney()
            if (active != null) {
                val owner = remote.authenticatedOwnerId().also { ownerId = it }
                reconcileRemoteTerminal(active.id, owner)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.warning("Active Journey terminal reconciliation is unavailable; local monitoring remains active")
            return SyncRunResult.Retry
        }
        // Legacy blocks are not worker candidates. Reopen only the exact old auth failure,
        // following a server-authorized owner read, while preserving checkpoint and evidence.
        for (block in local.legacyAuthorizationBlocks()) {
            if (block.lastError !in LEGACY_AUTHORIZATION_ERRORS) continue
            try {
                val owner = ownerId ?: remote.authenticatedOwnerId().also { ownerId = it }
                if (!remote.verifyJourneyOwner(block.journeyId, owner)) return SyncRunResult.Retry
                if (!local.reactivateLegacyAuthorization(block, clock.nowMillis())) return SyncRunResult.Retry
                logger.info("Legacy authorization block reactivated after verified Journey ownership")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warning("Legacy authorization block retained; owner verification did not complete")
                return SyncRunResult.Retry
            }
        }
        while (true) {
            val candidate = local.nextCandidate() ?: run {
                if (local.hasOutstandingWork()) {
                    logger.warning("No eligible sync candidate; outstanding local work remains blocked")
                    return SyncRunResult.PermanentFailure
                }
                logger.info("No outstanding cloud synchronization backlog")
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
                if (error.kind == SyncFailureKind.TERMINAL_CONFLICT) {
                    val reconciled = try {
                        reconcileRemoteTerminal(candidate.journeyId, requireNotNull(ownerId))
                    } catch (lookupError: Throwable) {
                        if (lookupError is CancellationException) throw lookupError
                        logger.warning("Terminal conflict lookup is unavailable; local monitoring remains active")
                        false
                    }
                    if (reconciled) continue // Re-read the ID-bound Room transition and sync it.
                }
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

    private suspend fun reconcileRemoteTerminal(journeyId: String, ownerId: String): Boolean {
        val localJourney = local.journey(journeyId) ?: return false
        if (localJourney.status != JourneyStatus.ACTIVE) return false
        val remoteJourney = remote.ownerJourneyState(journeyId, ownerId) ?: return false
        if (remoteJourney.journeyId != journeyId || remoteJourney.ownerId != ownerId) {
            throw CloudSyncException(SyncFailureKind.AUTHORIZATION, "Journey owner proof did not match.")
        }
        return when (remoteJourney.status) {
            JourneyStatus.ACTIVE -> false
            JourneyStatus.CANCELLED -> {
                val endedAt = remoteJourney.endedAt
                if (endedAt == null || remoteJourney.completedAt != null) {
                    throw CloudSyncException(SyncFailureKind.TERMINAL_CONFLICT, "Remote stop timestamp is unavailable.")
                }
                reconcileCancelled(journeyId, endedAt)
            }
            // The existing completion operation has no Journey-ID guard. Do not
            // manufacture a completion or stop the wrong active Journey here.
            JourneyStatus.COMPLETED -> throw CloudSyncException(
                SyncFailureKind.TERMINAL_CONFLICT,
                "Remote completion needs an ID-bound local reconciliation path.",
            )
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

        // Re-read after telemetry so a local terminal transition cannot remain
        // hidden behind the earlier ACTIVE payload.
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
