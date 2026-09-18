package com.journeycontinuity.app.sync

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
    fun offlineFailurePreservesLocalEvidenceAndDoesNotAdvanceCheckpoint() = runBlocking {
        val local = FakeLocalSyncStore(journey("one"), telemetry("one", 1..3))
        val remote = FakeCloudGateway(failNextTelemetryAfterAccept = false, alwaysOffline = true)

        val result = engine(local, remote).synchronize()

        assertEquals(SyncRunResult.Retry, result)
        assertEquals(0L, local.checkpoint("one"))
        assertEquals(listOf(1L, 2L, 3L), local.allTelemetry("one").map { it.sequence })
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

    private fun engine(local: FakeLocalSyncStore, remote: FakeCloudGateway) =
        ReliableSyncEngine(local, remote, SyncClock { 10_000L })

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
        )

        private val journeys = linkedMapOf<String, Journey>()
        private val observations = linkedMapOf<String, MutableList<TelemetryObservation>>()
        private val states = linkedMapOf<String, State>()

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

        fun requestAgain(journeyId: String) {
            states.getValue(journeyId).apply {
                version++
                requested = true
            }
        }

        fun allTelemetry(journeyId: String) = observations[journeyId].orEmpty()
        fun noWorkRequested(journeyId: String) = !states.getValue(journeyId).requested
    }

    private class FakeCloudGateway(
        private var failNextTelemetryAfterAccept: Boolean = false,
        private val alwaysOffline: Boolean = false,
        private val afterFirstJourneyUpsert: (() -> Unit)? = null,
        private val authFailure: CloudSyncException? = null,
    ) : CloudSyncGateway {
        val cloudJourneys = linkedMapOf<String, Journey>()
        val cloudTelemetry = linkedMapOf<Pair<String, Long>, TelemetryObservation>()
        val telemetryBatchSizes = mutableListOf<Int>()
        private var journeyUpserts = 0

        override suspend fun authenticatedOwnerId(): String = authFailure?.let { throw it } ?: "owner"

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
    }
}
