package com.journeycontinuity.app.degraded

import androidx.room.withTransaction
import com.journeycontinuity.app.data.local.DegradedConnectivityDao
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.sync.NoOpSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncDiagnosticLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Provisional Redmi lab values. These are not the final production safety policy. */
object DegradedConnectivityLabConfiguration {
    const val DEGRADATION_AFTER_MILLIS = 180_000L
    const val MINIMUM_RETRYABLE_CLOUD_FAILURES = 2
    const val RECOVERY_GRACE_MILLIS = 60_000L
    const val EVALUATION_INTERVAL_MILLIS = 60_000L
    const val MINIMUM_FALLBACK_INTERVAL_MILLIS = 15 * 60_000L
    const val FALLBACK_RATE_WINDOW_MILLIS = 60 * 60_000L
    const val MAXIMUM_FALLBACK_ATTEMPTS_PER_WINDOW = 4

    fun policyConfig() = DegradedConnectivityPolicyConfig(
        degradationAfterMillis = DEGRADATION_AFTER_MILLIS,
        minimumRetryableCloudFailures = MINIMUM_RETRYABLE_CLOUD_FAILURES,
        recoveryGraceMillis = RECOVERY_GRACE_MILLIS,
        minimumFallbackIntervalMillis = MINIMUM_FALLBACK_INTERVAL_MILLIS,
        fallbackRateWindowMillis = FALLBACK_RATE_WINDOW_MILLIS,
        maximumFallbackAttemptsPerWindow = MAXIMUM_FALLBACK_ATTEMPTS_PER_WINDOW,
    )
}

fun interface DegradedConnectivityActionObserver {
    suspend fun handle(action: DegradedConnectivityAction)
}

val NoOpDegradedConnectivityActionObserver = DegradedConnectivityActionObserver { }

fun interface FallbackCapabilityReader {
    suspend fun isAvailable(journeyId: String): Boolean
}

data class RecoveryBacklogSnapshot(val latestSequence: Long, val synchronizedThrough: Long)

interface DegradedConnectivityStateStore {
    suspend fun updateAtomically(
        journeyId: String,
        transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?,
    ): DegradedConnectivityReduction?

    fun observe(journeyId: String): Flow<DegradedConnectivityState?>
}

fun interface LatestTelemetryReader {
    suspend fun latest(journeyId: String): TelemetryObservation?
}

class RoomDegradedConnectivityStateStore(
    private val database: JourneyDatabase,
    private val dao: DegradedConnectivityDao,
    private val fallbackAttemptDao: com.journeycontinuity.app.data.local.FallbackAttemptDao,
    private val allocator: DurableFallbackAttemptAllocator,
    private val policy: DegradedConnectivityPolicy,
) : DegradedConnectivityStateStore {
    override suspend fun updateAtomically(
        journeyId: String,
        transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?,
    ): DegradedConnectivityReduction? = database.withTransaction {
        val backlog = RecoveryBacklogSnapshot(
            latestSequence = database.telemetryDao().getLatest(journeyId)?.sequence ?: 0,
            synchronizedThrough = database.syncStateDao().get(journeyId)?.highestTelemetrySequenceSynced ?: 0,
        )
        val reduction = transform(dao.get(journeyId)?.toDomain(), backlog) ?: return@withTransaction null
        require(reduction.state.journeyId == journeyId)
        var state = reduction.state
        dao.upsert(state.toEntity())
        reduction.actions.forEach { action ->
            when (action) {
                is DegradedConnectivityAction.AllocateFallbackAttempt -> {
                    when (val allocation = allocator.allocate(action)) {
                        is FallbackAllocationResult.Available -> {
                            state = policy.reduce(
                                state,
                                DegradedConnectivityEvent.FallbackAttemptAllocated(
                                    envelopeSequence = allocation.attempt.envelopeSequence,
                                    coveredTelemetrySequence = allocation.attempt.telemetrySequence,
                                    atMillis = allocation.attempt.allocatedAt,
                                ),
                            ).state
                        }
                        FallbackAllocationResult.Unavailable -> {
                            state = state.copy(
                                fallbackBindingProvisioned = false,
                                fallbackDisposition = FallbackDisposition.UNAVAILABLE,
                            )
                        }
                    }
                    dao.upsert(state.toEntity())
                }
                is DegradedConnectivityAction.StopOrdinaryFallback ->
                    fallbackAttemptDao.supersedeUnsent(action.journeyId, action.stoppedAtMillis)
                is DegradedConnectivityAction.SupersedeUnsentFallback ->
                    fallbackAttemptDao.supersedeUnsent(action.journeyId, action.recoveredAtMillis)
            }
        }
        reduction.copy(state = state)
    }

    override fun observe(journeyId: String): Flow<DegradedConnectivityState?> =
        dao.observe(journeyId).map { it?.toDomain() }
}

class DegradedConnectivityCoordinator(
    private val store: DegradedConnectivityStateStore,
    private val latestTelemetryReader: LatestTelemetryReader,
    private val fallbackCapabilityReader: FallbackCapabilityReader = FallbackCapabilityReader { false },
    private val policy: DegradedConnectivityPolicy,
    private val actionObserver: DegradedConnectivityActionObserver = NoOpDegradedConnectivityActionObserver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
) {
    suspend fun activate(journeyId: String, validatedInternetAvailable: Boolean) {
        val now = clock()
        val latestTelemetry = latestTelemetryReader.latest(journeyId)
        val fallbackAvailable = fallbackCapabilityReader.isAvailable(journeyId)
        reduce(journeyId) { previous, backlog ->
            if (previous == null) {
                applyEvents(
                    DegradedConnectivityState(),
                    listOfNotNull(
                        DegradedConnectivityEvent.JourneyActivated(
                            journeyId = journeyId,
                            validatedInternetAvailable = validatedInternetAvailable,
                            fallbackBindingProvisioned = fallbackAvailable,
                            atMillis = now,
                            recoveryTargetTelemetrySequence = backlog.latestSequence,
                        ),
                        latestTelemetry?.let { DegradedConnectivityEvent.TelemetryObserved(it, now) },
                    ),
                )
            } else {
                val networkEvent = when {
                    validatedInternetAvailable && (!previous.validatedInternetAvailable ||
                        previous.connectivityPhase != ConnectivityPhase.HEALTHY) ->
                        DegradedConnectivityEvent.ValidatedInternetAvailable(now, backlog.latestSequence)
                    !validatedInternetAvailable && previous.validatedInternetAvailable ->
                        DegradedConnectivityEvent.ValidatedInternetLost(now)
                    else -> null
                }
                applyEvents(
                    previous,
                    listOfNotNull(
                        DegradedConnectivityEvent.TimeAdvanced(now),
                        DegradedConnectivityEvent.FallbackCapabilityChanged(
                            available = fallbackAvailable,
                            atMillis = now,
                        ),
                        networkEvent,
                        latestTelemetry
                            ?.takeIf { it.sequence > (previous.latestTelemetrySequence ?: -1) }
                            ?.let { DegradedConnectivityEvent.TelemetryObserved(it, now) },
                    ),
                )
            }
        }
    }

    suspend fun telemetryObserved(observation: TelemetryObservation) =
        dispatch(observation.journeyId, DegradedConnectivityEvent.TelemetryObserved(observation, clock()))

    suspend fun validatedInternetLost(journeyId: String) =
        dispatch(journeyId, DegradedConnectivityEvent.ValidatedInternetLost(clock()))

    suspend fun validatedInternetAvailable(journeyId: String) = reduce(journeyId) { previous, backlog ->
        previous?.let { policy.reduce(it, DegradedConnectivityEvent.ValidatedInternetAvailable(
            clock(), backlog.latestSequence,
        )) }
    }

    suspend fun retryableCloudFailure(journeyId: String) =
        dispatch(journeyId, DegradedConnectivityEvent.RetryableCloudFailure(clock()))

    suspend fun freshHeartbeatSucceeded(journeyId: String, heartbeatStartedAtMillis: Long? = null) = dispatch(
        journeyId,
        DegradedConnectivityEvent.AuthenticatedCloudSuccess(
            clock(), establishesFreshContact = true, heartbeatStartedAtMillis = heartbeatStartedAtMillis,
        ),
    )

    suspend fun timeAdvanced(journeyId: String) =
        dispatch(journeyId, DegradedConnectivityEvent.TimeAdvanced(clock()))

    suspend fun sparseFallbackTriggered(journeyId: String) =
        dispatch(journeyId, DegradedConnectivityEvent.SparseFallbackTriggered(clock()))

    suspend fun refreshFallbackCapability(journeyId: String) = dispatch(
        journeyId,
        DegradedConnectivityEvent.FallbackCapabilityChanged(
            available = fallbackCapabilityReader.isAvailable(journeyId),
            atMillis = clock(),
        ),
    )

    suspend fun journeyCompleted(journeyId: String, completedAt: Long) =
        dispatch(journeyId, DegradedConnectivityEvent.JourneyCompleted(completedAt))

    fun observe(journeyId: String): Flow<DegradedConnectivityState?> = store.observe(journeyId)

    private suspend fun dispatch(journeyId: String, event: DegradedConnectivityEvent) {
        reduce(journeyId) { previous, _ -> previous?.let { policy.reduce(it, event) } }
    }

    private suspend fun reduce(
        journeyId: String,
        transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?,
    ) {
        val reduction = store.updateAtomically(journeyId) { previous, backlog ->
            val result = transform(previous, backlog) ?: return@updateAtomically null
            if (result.state.connectivityPhase != ConnectivityPhase.RECOVERING) return@updateAtomically result
            val observed = policy.reduce(result.state, DegradedConnectivityEvent.RecoveryCheckpointObserved(
                backlog.synchronizedThrough, clock(),
            ))
            DegradedConnectivityReduction(observed.state, result.actions + observed.actions)
        } ?: return
        logState(reduction.state)
        reduction.actions.forEach { actionObserver.handle(it) }
    }

    private fun applyEvents(
        initial: DegradedConnectivityState,
        events: List<DegradedConnectivityEvent>,
    ): DegradedConnectivityReduction {
        var state = initial
        val nonAllocationActions = mutableListOf<DegradedConnectivityAction>()
        var finalAllocation: DegradedConnectivityAction.AllocateFallbackAttempt? = null
        events.forEach { event ->
            policy.reduce(state, event).also {
                state = it.state
                it.actions.forEach { action ->
                    if (action is DegradedConnectivityAction.AllocateFallbackAttempt) {
                        finalAllocation = action
                    } else if (action !in nonAllocationActions) {
                        nonAllocationActions += action
                    }
                }
            }
        }
        return DegradedConnectivityReduction(
            state,
            nonAllocationActions + listOfNotNull(
                finalAllocation?.takeIf {
                    state.fallbackDisposition == FallbackDisposition.ELIGIBLE &&
                        it.envelopeSequence == state.nextFallbackEnvelopeSequence &&
                        it.telemetrySequence == state.latestTelemetrySequence
                },
            ),
        )
    }

    private fun logState(state: DegradedConnectivityState) {
        val interruptionDuration = state.interruptionStartedAtMillis?.let {
            (clock() - it).coerceAtLeast(0)
        }
        logger.info(
            "Degraded connectivity state: phase=${state.connectivityPhase}, " +
                "fallback=${state.fallbackDisposition}, episode=${state.degradationEpisodeId}, " +
                "interruptionStartedAt=${state.interruptionStartedAtMillis}, " +
                "interruptionDurationMillis=$interruptionDuration, " +
                "lastAuthenticatedCloudSuccessAt=${state.lastAuthenticatedCloudSuccessAtMillis}, " +
                "recoveryTarget=${state.recoveryTargetTelemetrySequence}, " +
                "backlogSatisfiedAt=${state.recoveryBacklogSatisfiedAtMillis}, " +
                "retryableFailures=${state.consecutiveRetryableCloudFailures}",
        )
    }
}
