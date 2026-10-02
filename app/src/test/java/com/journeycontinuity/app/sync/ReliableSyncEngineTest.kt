package com.journeycontinuity.app.sync

import com.journeycontinuity.app.auth.TravellerAuthBackend
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.auth.TravellerIdentityStore
import com.journeycontinuity.app.auth.TravellerSessionState
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.TelemetryObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliableSyncEngineTest {
    @Test
    fun pendingBackgroundSyncOnCleanInstallCannotBootstrapAnOwner() = runBlocking {
        var anonymousSignIns = 0
        var ownerWrites = 0
        val coordinator = TravellerIdentityCoordinator(
            backend = object : TravellerAuthBackend {
                override suspend fun awaitInitialization() = Unit
                override fun sessionState() = TravellerSessionState.NotAuthenticated
                override suspend fun signInAnonymously() { anonymousSignIns++ }
            },
            identityStore = object : TravellerIdentityStore {
                override fun expectedTravellerUserId(): String? = null
                override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean {
                    ownerWrites++
                    return true
                }
            },
        )
        val local = FakeLocalSyncStore(journey("unbound"), telemetry("unbound", 1..2))
        val remote = object : CloudSyncGateway {
            override suspend fun authenticatedOwnerId(): String = try {
                coordinator.requireAuthenticatedTraveller().userId
            } catch (error: Throwable) {
                throw error.toCloudSyncException(CloudStage.AUTH_INITIALIZATION)
            }
            override suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState? =
                error("Unbound Journey must not perform an owner lookup")
            override suspend fun upsertJourney(journey: Journey, ownerId: String) =
                error("Unbound Journey must not upload")
            override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) =
                error("Unbound evidence must not upload")
        }

        assertEquals(SyncRunResult.Retry, ReliableSyncEngine(local, remote).synchronize())
        assertEquals(0, anonymousSignIns)
        assertEquals(0, ownerWrites)
        assertEquals(0L, local.checkpoint("unbound"))
        assertEquals(2, local.allTelemetry("unbound").size)
    }

    @Test
    fun temporaryTravellerAuthenticationFailureUsesWorkerRetrySemantics() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..2))
        val remote = FakeCloudGateway(
            authFailure = CloudSyncException(
                SyncFailureKind.TRANSIENT,
                "Cloud authentication is temporarily unavailable while reconnecting.",
            ),
        )

        assertEquals(SyncRunResult.Retry, engine(local, remote).synchronize())
        assertFalse(local.noWorkRequested("one"))
        assertEquals(0L, local.checkpoint("one"))
        assertEquals(emptyMap<String, Journey>(), remote.cloudJourneys)
    }

    @Test
    fun onlyRetryableFailuresAreReportedToDegradationPolicy() = runBlocking {
        val observed = mutableListOf<String>()
        val transientAuthLocal = FakeLocalSyncStore(journey("transient-auth"))
        val transientAuthRemote = FakeCloudGateway(
            authFailure = CloudSyncException(SyncFailureKind.TRANSIENT, "Temporarily unavailable"),
        )
        val permanentLocal = FakeLocalSyncStore(journey("permanent"))
        val permanentRemote = FakeCloudGateway(
            authFailure = CloudSyncException(SyncFailureKind.AUTHENTICATION, "Sign-in required"),
        )
        val retryableCloudLocal = FakeLocalSyncStore(
            journey("retryable-cloud"),
            telemetry("retryable-cloud", 1..1),
        )
        val retryableCloudRemote = FakeCloudGateway(alwaysOffline = true)

        engine(transientAuthLocal, transientAuthRemote) { observed += it }.synchronize()
        engine(permanentLocal, permanentRemote) { observed += it }.synchronize()
        engine(retryableCloudLocal, retryableCloudRemote) { observed += it }.synchronize()

        assertEquals(listOf("retryable-cloud"), observed)
    }

    @Test
    fun offlineFailurePreservesLocalEvidenceAndDoesNotAdvanceCheckpoint() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..3))
        val remote = FakeCloudGateway(failNextTelemetryAfterAccept = false, alwaysOffline = true)

        val result = engine(local, remote).synchronize()

        assertEquals(SyncRunResult.Retry, result)
        assertEquals(0L, local.checkpoint("one"))
        assertEquals(listOf(1L, 2L, 3L), local.allTelemetry("one").map { it.sequence })
    }

    @Test
    fun nonOwnerAuthorizationFailurePreservesLocalEvidenceAndCorrectOwnerCanResume() = runBlocking {
        val originalJourney = journey("one")
        val local = FakeLocalSyncStore(originalJourney, telemetry("one", 1..3))
        val remote = FakeCloudGateway(
            authFailure = CloudSyncException(
                SyncFailureKind.AUTHORIZATION,
                "Authenticated user is not authorized for this Journey.",
            ),
        )

        assertEquals(SyncRunResult.Retry, engine(local, remote).synchronize())
        assertEquals(originalJourney, local.journey("one"))
        assertEquals(listOf(1L, 2L, 3L), local.allTelemetry("one").map { it.sequence })
        assertEquals(0L, local.checkpoint("one"))
        assertEquals(emptyMap<String, Journey>(), remote.cloudJourneys)

        remote.restoreOwnerSession()
        local.requestAgain("one")
        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())
        assertEquals(originalJourney, local.journey("one"))
        assertEquals(3L, local.checkpoint("one"))
        assertEquals(listOf(1L, 2L, 3L), remote.receivedSequences("one"))
    }

    @Test
    fun backlogStartsAfterCheckpointAndUsesAscendingBatchesOfFifty() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..125))
        local.seedCheckpoint("one", 20)
        val remote = FakeCloudGateway()

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())

        assertEquals(listOf(50, 50, 5), remote.telemetryBatchSizes)
        assertEquals((21L..125L).toList(), remote.receivedSequences("one"))
        assertEquals(125L, local.checkpoint("one"))
    }

    @Test
    fun acceptedBatchRetriedBeforeCheckpointUpdateRemainsIdempotent() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..20))
        val remote = FakeCloudGateway(failNextTelemetryAfterAccept = true)

        assertEquals(SyncRunResult.Retry, engine(local, remote).synchronize())
        assertEquals(0L, local.checkpoint("one"))

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())
        assertEquals(20, remote.cloudTelemetry.size)
        assertEquals((1L..20L).toList(), remote.receivedSequences("one"))
        assertEquals(20L, local.checkpoint("one"))
    }

    @Test
    fun duplicateWorkerPassAfterSuccessfulCheckpointIsHarmless() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..3))
        val remote = FakeCloudGateway()

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())
        local.requestAgain("one")
        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())

        assertEquals(3, remote.cloudTelemetry.size)
        assertEquals((1L..3L).toList(), remote.receivedSequences("one"))
        assertEquals(3L, local.checkpoint("one"))
    }

    @Test
    fun differentJourneysKeepIndependentCheckpoints() = runBlocking {
        val local = FakeLocalSyncStore(
            journey("one"),
            telemetry("one", 1..3),
            journey("two"),
            telemetry("two", 1..2),
        )
        val remote = FakeCloudGateway()

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())

        assertEquals(3L, local.checkpoint("one"))
        assertEquals(2L, local.checkpoint("two"))
        assertEquals(listOf(1L, 2L, 3L), remote.receivedSequences("one"))
        assertEquals(listOf(1L, 2L), remote.receivedSequences("two"))
    }

    @Test
    fun completedJourneySynchronizesOutstandingTelemetryAndFinalState() = runBlocking {
        val completed = journey("one").copy(status = JourneyStatus.COMPLETED, completedAt = 9_000L)
        val local = FakeLocalSyncStore(completed, telemetry("one", 1..4))
        val remote = FakeCloudGateway()

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())

        assertEquals(4L, local.checkpoint("one"))
        assertEquals(JourneyStatus.COMPLETED, remote.cloudJourneys.getValue("one").status)
        assertEquals(9_000L, remote.cloudJourneys.getValue("one").completedAt)
    }

    @Test
    fun localChangeDuringSyncIsDrainedBeforeWorkerFinishes() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..2))
        val remote = FakeCloudGateway(
            afterFirstJourneyUpsert = {
                local.addTelemetry(telemetry("one", 3..3).single())
            },
        )

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())

        assertEquals(3L, local.checkpoint("one"))
        assertEquals(listOf(1L, 2L, 3L), remote.receivedSequences("one"))
        assertTrue(local.noWorkRequested("one"))
    }

    @Test
    fun cancelledJourneySynchronizesDistinctEndWithoutCompletion() = runBlocking {
        val cancelled = journey("one").copy(status = JourneyStatus.CANCELLED, endedAt = 9_000L)
        val local = FakeLocalSyncStore(cancelled, telemetry("one", 1..2))
        val remote = FakeCloudGateway()

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())

        assertEquals(cancelled, remote.cloudJourneys.getValue("one"))
        assertEquals(null, remote.cloudJourneys.getValue("one").completedAt)
        assertEquals(9_000L, remote.cloudJourneys.getValue("one").endedAt)
    }

    @Test
    fun cancellationDuringInitialUpsertWinsAtFinalRead() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..2))
        val remote = FakeCloudGateway(afterFirstJourneyUpsert = {
            local.transitionJourney("one") {
                it.copy(status = JourneyStatus.CANCELLED, endedAt = 9_000L)
            }
        })

        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())
        assertEquals(JourneyStatus.CANCELLED, remote.cloudJourneys.getValue("one").status)
        assertEquals(9_000L, remote.cloudJourneys.getValue("one").endedAt)
        assertEquals(null, remote.cloudJourneys.getValue("one").completedAt)
    }

    @Test
    fun terminalConflictDuringInitialUpsertReconcilesWithoutClaimingConnectivityLoss() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..2))
        val cloudTerminal = journey("one").copy(
            status = JourneyStatus.CANCELLED,
            endedAt = 9_000L,
        )
        var uploaded: Journey? = null
        var lookups = 0
        val remote = object : CloudSyncGateway {
            override suspend fun authenticatedOwnerId() = "owner"
            override suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState {
                lookups++
                return if (lookups == 1) RemoteJourneyState(journeyId, ownerId, JourneyStatus.ACTIVE, null, null)
                else RemoteJourneyState(journeyId, ownerId, JourneyStatus.CANCELLED, 9_000L, null)
            }
            override suspend fun upsertJourney(journey: Journey, ownerId: String) {
                if (journey.status == JourneyStatus.ACTIVE) {
                    throw CloudSyncException(
                        SyncFailureKind.TERMINAL_CONFLICT,
                        "Journey upsert conflicts with a terminal Journey state.",
                    )
                }
                assertEquals(cloudTerminal.endedAt, journey.endedAt)
                uploaded = journey
            }
            override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) = Unit
        }
        val degradationObservations = mutableListOf<String>()
        val engine = ReliableSyncEngine(
            local = local,
            remote = remote,
            attemptObserver = { degradationObservations += it },
            reconcileCancelled = local::stopMonitoringActive,
        )

        assertEquals(SyncRunResult.Success, engine.synchronize())
        assertTrue(degradationObservations.isEmpty())
        assertEquals(cloudTerminal, local.journey("one"))
        assertEquals(JourneyStatus.CANCELLED, uploaded?.status)
        assertEquals(9_000L, uploaded?.endedAt)
        assertEquals(2L, local.checkpoint("one"))
    }

    @Test
    fun processRestartWithCleanLocalBacklogReconcilesExactRemoteStopTime() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..2))
        local.clearWorkRequest("one")
        val remote = FakeCloudGateway()
        remote.cloudJourneys["one"] = journey("one").copy(
            status = JourneyStatus.CANCELLED, endedAt = 9_123L,
        )

        assertEquals(SyncRunResult.Success, reconciliationEngine(local, remote).synchronize())
        assertEquals(JourneyStatus.CANCELLED, local.journey("one")?.status)
        assertEquals(9_123L, local.journey("one")?.endedAt)
        assertEquals(null, local.journey("one")?.completedAt)
        assertEquals(null, local.activeJourney())
        assertEquals(2, local.allTelemetry("one").size)
    }

    @Test
    fun sameOwnerReauthenticationConvergesOnNextSyncWake() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"))
        local.clearWorkRequest("one")
        val remote = FakeCloudGateway(authFailure = CloudSyncException(
            SyncFailureKind.AUTHENTICATION, "Sign-in required",
        ))
        remote.cloudJourneys["one"] = journey("one").copy(
            status = JourneyStatus.CANCELLED, endedAt = 9_000L,
        )

        assertEquals(SyncRunResult.Retry, reconciliationEngine(local, remote).synchronize())
        assertEquals(JourneyStatus.ACTIVE, local.activeJourney()?.status)
        remote.restoreOwnerSession()
        assertEquals(SyncRunResult.Success, reconciliationEngine(local, remote).synchronize())
        assertEquals(JourneyStatus.CANCELLED, local.journey("one")?.status)
    }

    @Test
    fun unavailableRemoteLookupKeepsMonitoringActive() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"))
        val remote = FakeCloudGateway(lookupFailure = CloudSyncException(
            SyncFailureKind.TRANSIENT, "Network unavailable",
        ))
        assertEquals(SyncRunResult.Retry, reconciliationEngine(local, remote).synchronize())
        assertEquals(JourneyStatus.ACTIVE, local.activeJourney()?.status)
    }

    @Test
    fun wrongOwnerProofCannotReconcileOrStopLocalJourney() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"))
        val remote = FakeCloudGateway(lookupOwner = "different-owner")
        remote.cloudJourneys["one"] = journey("one").copy(
            status = JourneyStatus.CANCELLED, endedAt = 9_000L,
        )
        assertEquals(SyncRunResult.Retry, reconciliationEngine(local, remote).synchronize())
        assertEquals(JourneyStatus.ACTIVE, local.activeJourney()?.status)
    }

    @Test
    fun remoteCompletionDoesNotInventLocalCompletionOrSafety() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"))
        val remote = FakeCloudGateway()
        remote.cloudJourneys["one"] = journey("one").copy(
            status = JourneyStatus.COMPLETED, completedAt = 9_000L,
        )
        assertEquals(SyncRunResult.Retry, reconciliationEngine(local, remote).synchronize())
        assertEquals(JourneyStatus.ACTIVE, local.activeJourney()?.status)
        assertEquals(null, local.journey("one")?.completedAt)
    }

    @Test
    fun provisioningRunsOnlyAfterActiveJourneyExistsInCloud() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"))
        val remote = FakeCloudGateway()
        var observedCloudJourney = false
        val engine = ReliableSyncEngine(
            local = local,
            remote = remote,
            provisioningObserver = { journeyId ->
                observedCloudJourney = remote.cloudJourneys.containsKey(journeyId)
            },
        )

        assertEquals(SyncRunResult.Success, engine.synchronize())
        assertTrue(observedCloudJourney)
    }

    @Test
    fun provisioningFailureDoesNotCorruptSuccessfulTelemetrySync() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..2))
        val remote = FakeCloudGateway()
        val engine = ReliableSyncEngine(
            local = local,
            remote = remote,
            provisioningObserver = { error("provisioning unavailable") },
        )

        assertEquals(SyncRunResult.Success, engine.synchronize())
        assertEquals(2L, local.checkpoint("one"))
        assertTrue(local.noWorkRequested("one"))
    }

    @Test
    fun verifiedOwnerReactivatesOnlyLegacyAuthorizationAndPreservesCheckpoint() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..6))
        local.seedCheckpoint("one", 2)
        local.markFailure("one", LEGACY_AUTHORIZATION_ERRORS.first(), true)
        val remote = FakeCloudGateway(verifiedOwner = true)
        assertEquals(SyncRunResult.Success, engine(local, remote).synchronize())
        assertEquals(2L, local.checkpointAtReactivation)
        assertEquals(6L, local.checkpoint("one"))
        assertEquals((3L..6L).toList(), remote.receivedSequences("one"))
        assertEquals(6, local.allTelemetry("one").size)
        assertEquals(LEGACY_AUTHORIZATION_ERRORS.first(), local.legacyDiagnostic)
    }

    @Test
    fun wrongOwnerCannotReactivateLegacyBlockOrWriteCloudEvidence() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..3))
        local.seedCheckpoint("one", 2)
        local.markFailure("one", LEGACY_AUTHORIZATION_ERRORS.first(), true)
        val remote = FakeCloudGateway(verifiedOwner = false)
        assertEquals(SyncRunResult.Retry, engine(local, remote).synchronize())
        assertEquals(2L, local.checkpoint("one"))
        assertTrue(local.noWorkRequested("one"))
        assertTrue(remote.cloudJourneys.isEmpty())
        assertEquals(null, local.legacyDiagnostic)
    }

    @Test
    fun noEligibleCandidateWithPermanentNonAuthorizationBacklogIsNotSuccess() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..3))
        local.markFailure("one", "Schema mismatch", true)
        val remote = FakeCloudGateway(verifiedOwner = true)
        assertEquals(SyncRunResult.PermanentFailure, engine(local, remote).synchronize())
        assertEquals(0L, local.checkpoint("one"))
        assertTrue(remote.cloudJourneys.isEmpty())
        assertEquals(0, remote.ownerVerificationCalls)
    }

    private fun engine(
        local: FakeLocalSyncStore,
        remote: FakeCloudGateway,
        observer: suspend (String) -> Unit = {},
    ) = ReliableSyncEngine(
        local = local,
        remote = remote,
        clock = SyncClock { 10_000L },
        attemptObserver = observer,
    )

    private fun reconciliationEngine(local: FakeLocalSyncStore, remote: FakeCloudGateway) =
        ReliableSyncEngine(local, remote, reconcileCancelled = local::stopMonitoringActive)

    private fun journey(id: String) = Journey(
        id = id,
        destination = "Destination $id",
        expectedArrivalAt = 20_000L,
        startedAt = 1_000L,
        status = JourneyStatus.ACTIVE,
        completedAt = null,
    )

    private fun telemetry(journeyId: String, sequences: IntRange) = sequences.map { sequence ->
        TelemetryObservation(
            id = sequence.toLong(),
            journeyId = journeyId,
            sequence = sequence.toLong(),
            eventTime = sequence * 1_000L,
            latitude = 9.0,
            longitude = 7.0,
            accuracyMeters = 10f,
            batteryPercent = 70,
            isCharging = false,
            connectivity = ConnectivityState.NONE,
        )
    }

    private class FakeLocalSyncStore(vararg seed: Any) : LocalSyncStore {
        private data class State(
            var checkpoint: Long = 0,
            var version: Long = 1,
            var requested: Boolean = true,
            var blocked: Boolean = false,
            var error: String? = null,
        )

        private val journeys = linkedMapOf<String, Journey>()
        private val observations = linkedMapOf<String, MutableList<TelemetryObservation>>()
        private val states = linkedMapOf<String, State>()
        var checkpointAtReactivation: Long? = null
        var legacyDiagnostic: String? = null

        override suspend fun legacyAuthorizationBlocks() = states.entries.filter {
            it.value.blocked && it.value.error in LEGACY_AUTHORIZATION_ERRORS
        }.map { LegacyAuthorizationBlock(it.key, it.value.version, requireNotNull(it.value.error)) }

        override suspend fun reactivateLegacyAuthorization(block: LegacyAuthorizationBlock, verifiedAt: Long): Boolean {
            val state = states.getValue(block.journeyId)
            if (!state.blocked || state.version != block.changeVersion || state.error != block.lastError) return false
            checkpointAtReactivation = state.checkpoint
            legacyDiagnostic = state.error
            state.blocked = false
            state.requested = true
            state.version++
            return true
        }

        override suspend fun hasOutstandingWork() = states.any { (id, state) ->
            state.blocked || state.requested ||
                state.checkpoint < (observations[id]?.maxOfOrNull { it.sequence } ?: 0)
        }

        init {
            seed.forEach { item ->
                when (item) {
                    is Journey -> {
                        journeys[item.id] = item
                        states[item.id] = State()
                    }
                    is List<*> -> item.filterIsInstance<TelemetryObservation>().forEach {
                        observations.getOrPut(it.journeyId, ::mutableListOf).add(it)
                    }
                }
            }
        }

        override suspend fun nextCandidate(): PendingSyncCandidate? = states.entries
            .firstOrNull { it.value.requested && !it.value.blocked }
            ?.let { PendingSyncCandidate(it.key, it.value.version) }

        override suspend fun journey(journeyId: String) = journeys[journeyId]
        override suspend fun activeJourney() = journeys.values.firstOrNull { it.status == JourneyStatus.ACTIVE }

        suspend fun stopMonitoringActive(journeyId: String, endedAt: Long): Boolean {
            if (activeJourney()?.id != journeyId) return false
            transitionJourney(journeyId) {
                it.copy(status = JourneyStatus.CANCELLED, endedAt = endedAt, completedAt = null)
            }
            return true
        }
        override suspend fun checkpoint(journeyId: String) = states.getValue(journeyId).checkpoint
        override suspend fun telemetryAfter(journeyId: String, afterSequence: Long, limit: Int) =
            observations[journeyId].orEmpty()
                .filter { it.sequence > afterSequence }
                .sortedBy { it.sequence }
                .take(limit)

        override suspend fun markSyncing(journeyId: String, attemptedAt: Long) = Unit

        override suspend fun advanceCheckpoint(journeyId: String, sequence: Long) {
            states.getValue(journeyId).checkpoint = sequence
        }

        override suspend fun finishIfUnchanged(
            journeyId: String,
            expectedChangeVersion: Long,
            successfulAt: Long,
        ): Boolean {
            val state = states.getValue(journeyId)
            return if (state.version == expectedChangeVersion) {
                state.requested = false
                true
            } else {
                true.also { state.requested = true }
                false
            }
        }

        override suspend fun markFailure(
            journeyId: String,
            message: String,
            permanentlyBlocked: Boolean,
        ) {
            states.getValue(journeyId).apply {
                blocked = permanentlyBlocked
                error = message
                requested = !permanentlyBlocked
            }
        }

        fun seedCheckpoint(journeyId: String, sequence: Long) {
            states.getValue(journeyId).checkpoint = sequence
        }

        fun addTelemetry(observation: TelemetryObservation) {
            observations.getOrPut(observation.journeyId, ::mutableListOf).add(observation)
            states.getValue(observation.journeyId).apply {
                version++
                requested = true
            }
        }

        fun transitionJourney(journeyId: String, transform: (Journey) -> Journey) {
            journeys[journeyId] = transform(journeys.getValue(journeyId))
            states.getValue(journeyId).apply { version++; requested = true }
        }

        fun requestAgain(journeyId: String) {
            states.getValue(journeyId).apply {
                version++
                requested = true
            }
        }

        fun allTelemetry(journeyId: String) = observations[journeyId].orEmpty()
        fun clearWorkRequest(journeyId: String) { states.getValue(journeyId).requested = false }
        fun noWorkRequested(journeyId: String) = !states.getValue(journeyId).requested
    }

    private class FakeCloudGateway(
        private var failNextTelemetryAfterAccept: Boolean = false,
        private val alwaysOffline: Boolean = false,
        private val afterFirstJourneyUpsert: (() -> Unit)? = null,
        private var authFailure: CloudSyncException? = null,
        private val verifiedOwner: Boolean = false,
        private val lookupFailure: CloudSyncException? = null,
        private val lookupOwner: String = "owner",
    ) : CloudSyncGateway {
        val cloudJourneys = linkedMapOf<String, Journey>()
        val cloudTelemetry = linkedMapOf<Pair<String, Long>, TelemetryObservation>()
        val telemetryBatchSizes = mutableListOf<Int>()
        private var journeyUpserts = 0
        var ownerVerificationCalls = 0

        override suspend fun verifyJourneyOwner(journeyId: String, ownerId: String): Boolean {
            ownerVerificationCalls++
            return verifiedOwner
        }

        override suspend fun authenticatedOwnerId(): String = authFailure?.let { throw it } ?: "owner"

        override suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState? {
            lookupFailure?.let { throw it }
            return cloudJourneys[journeyId]?.let {
                RemoteJourneyState(journeyId, lookupOwner, it.status, it.endedAt, it.completedAt)
            }
        }

        override suspend fun upsertJourney(journey: Journey, ownerId: String) {
            cloudJourneys[journey.id] = journey
            journeyUpserts++
            if (journeyUpserts == 1) afterFirstJourneyUpsert?.invoke()
        }

        override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) {
            if (alwaysOffline) throw CloudSyncException(
                SyncFailureKind.TRANSIENT,
                "Cloud synchronization is temporarily unavailable.",
            )
            telemetryBatchSizes += observations.size
            observations.forEach { cloudTelemetry[it.journeyId to it.sequence] = it }
            if (failNextTelemetryAfterAccept) {
                failNextTelemetryAfterAccept = false
                throw CloudSyncException(
                    SyncFailureKind.TRANSIENT,
                    "Cloud synchronization is temporarily unavailable.",
                )
            }
        }

        fun receivedSequences(journeyId: String) = cloudTelemetry.keys
            .filter { it.first == journeyId }
            .map { it.second }
            .sorted()

        fun restoreOwnerSession() {
            authFailure = null
        }
    }
}
