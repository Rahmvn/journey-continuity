package com.journeycontinuity.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.journeycontinuity.app.degraded.DegradedConnectivityAction
import com.journeycontinuity.app.degraded.AesGcmEnvelopeProtector
import com.journeycontinuity.app.degraded.DegradedConnectivityCoordinator
import com.journeycontinuity.app.degraded.DegradedConnectivityPolicy
import com.journeycontinuity.app.degraded.DegradedConnectivityPolicyConfig
import com.journeycontinuity.app.degraded.DurableFallbackAttemptAllocator
import com.journeycontinuity.app.degraded.FallbackAllocationResult
import com.journeycontinuity.app.degraded.FallbackBindingStatus
import com.journeycontinuity.app.degraded.FallbackCapabilityReader
import com.journeycontinuity.app.degraded.FallbackDisposition
import com.journeycontinuity.app.degraded.FallbackEnvelopeV1
import com.journeycontinuity.app.degraded.FallbackKeyMaterialStore
import com.journeycontinuity.app.degraded.FallbackNonceSource
import com.journeycontinuity.app.degraded.FallbackTransportState
import com.journeycontinuity.app.degraded.FallbackTransportOutcome
import com.journeycontinuity.app.degraded.RoomDegradedConnectivityStateStore
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.TelemetrySample
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FallbackAttemptDatabaseTest {
    private lateinit var database: JourneyDatabase
    private lateinit var keyStore: FakeKeyMaterialStore
    private lateinit var nonceSource: CountingNonceSource
    private var now = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK)
            .build()
        keyStore = FakeKeyMaterialStore()
        nonceSource = CountingNonceSource()
        database.journeyDao().insertIfNoActive(journey().toEntity())
        database.telemetryDao().insertForActiveJourney(sample(sequenceTime = 5_000))
        database.fallbackAttemptDao().insertBinding(
            JourneyFallbackBindingEntity(
                journeyId = JOURNEY_ID,
                keyId = KEY_ID,
                journeyHandle = ByteArray(12) { 4 },
                status = FallbackBindingStatus.PROVISIONED,
                provisionedAt = 1,
                revokedAt = null,
            ),
        )
        keyStore.provision(KEY_ID, ByteArray(32) { (it + 1).toByte() })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun firstAllocationIsAtomicIdempotentAndRestorationReusesExactPayload() = runBlocking {
        val coordinator = coordinator()
        coordinator.activate(JOURNEY_ID, validatedInternetAvailable = false)
        now = 1_000
        coordinator.timeAdvanced(JOURNEY_ID)

        val first = database.fallbackAttemptDao().allForJourney(JOURNEY_ID).single()
        val state = database.degradedConnectivityDao().get(JOURNEY_ID)!!.toDomain()
        assertEquals(FallbackTransportState.ALLOCATED, first.transportState)
        assertEquals(FallbackDisposition.ALLOCATED, state.fallbackDisposition)
        assertEquals(1L, first.envelopeSequence)
        assertEquals(1L, first.telemetrySequence)
        assertEquals(5_000L, first.observationEventTime)
        assertEquals(2L, state.nextFallbackEnvelopeSequence)
        assertEquals(1, nonceSource.calls)

        try {
            database.fallbackAttemptDao().insert(first.copy(localAttemptId = 0))
            fail("Expected duplicate logical/envelope attempt to violate unique constraints")
        } catch (_: SQLiteConstraintException) {
            // The database, not only allocator logic, rejects duplicates.
        }

        val replay = allocator().allocate(
            DegradedConnectivityAction.AllocateFallbackAttempt(JOURNEY_ID, 1, 1, 1),
        ) as FallbackAllocationResult.Available
        assertFalse(replay.newlyAllocated)
        assertEquals(first.localAttemptId, replay.attempt.localAttemptId)
        assertEquals(first.protectedPayloadText, replay.attempt.protectedPayloadText)
        assertArrayEquals(first.nonce, replay.attempt.nonce)
        assertArrayEquals(first.payloadSha256, replay.attempt.payloadSha256)
        assertArrayEquals(
            first.payloadSha256,
            AesGcmEnvelopeProtector.sha256(first.protectedPayloadText.toByteArray()),
        )
        assertEquals(1, nonceSource.calls)

        now = 1_100
        coordinator().activate(JOURNEY_ID, validatedInternetAvailable = false)
        val restored = database.fallbackAttemptDao().allForJourney(JOURNEY_ID).single()
        assertEquals(first.protectedPayloadText, restored.protectedPayloadText)
        assertEquals(2L, database.degradedConnectivityDao().get(JOURNEY_ID)!!.nextFallbackEnvelopeSequence)
    }

    @Test
    fun separateAllocationsUseDifferentNoncesAndPreserveCoveredTelemetry() = runBlocking {
        val coordinator = coordinator()
        coordinator.activate(JOURNEY_ID, false)
        now = 1_000
        coordinator.timeAdvanced(JOURNEY_ID)
        database.telemetryDao().insertForActiveJourney(sample(sequenceTime = 6_000))!!.also {
            coordinator.telemetryObserved(it.toDomain())
        }
        now = 1_200
        coordinator.timeAdvanced(JOURNEY_ID)

        val attempts = database.fallbackAttemptDao().allForJourney(JOURNEY_ID)
        assertEquals(listOf(1L, 2L), attempts.map { it.envelopeSequence })
        assertEquals(listOf(1L, 2L), attempts.map { it.telemetrySequence })
        assertNotEquals(attempts[0].nonce.toList(), attempts[1].nonce.toList())
        assertEquals(3L, database.degradedConnectivityDao().get(JOURNEY_ID)!!.nextFallbackEnvelopeSequence)
    }

    @Test
    fun recoverySupersedesOnlyUnsentHistory() = runBlocking {
        val coordinator = coordinator()
        coordinator.activate(JOURNEY_ID, false)
        now = 1_000
        coordinator.timeAdvanced(JOURNEY_ID)
        val first = database.fallbackAttemptDao().allForJourney(JOURNEY_ID).single()
        assertEquals(1, database.fallbackAttemptDao().claimForHandoff(first.localAttemptId, 1_010))
        assertEquals(1, database.fallbackAttemptDao().markHandedOff(first.localAttemptId, 1, 1_020, -1))

        database.telemetryDao().insertForActiveJourney(sample(sequenceTime = 6_000))!!.also {
            coordinator.telemetryObserved(it.toDomain())
        }
        now = 1_200
        coordinator.timeAdvanced(JOURNEY_ID)
        coordinator.freshHeartbeatSucceeded(JOURNEY_ID)

        val attempts = database.fallbackAttemptDao().allForJourney(JOURNEY_ID)
        assertEquals(FallbackTransportState.HANDED_OFF, attempts[0].transportState)
        assertEquals(FallbackTransportState.SUPERSEDED, attempts[1].transportState)
        assertEquals(1_020L, attempts[0].terminalAt)
        assertEquals(1_200L, attempts[1].terminalAt)
    }

    @Test
    fun retryTransitionsReusePersistedBytesAndTerminalWorkCannotBeReclaimed() = runBlocking {
        val coordinator = coordinator()
        coordinator.activate(JOURNEY_ID, false)
        now = 1_000
        coordinator.timeAdvanced(JOURNEY_ID)
        val original = database.fallbackAttemptDao().allForJourney(JOURNEY_ID).single()

        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE fallback_attempts SET protectedPayloadText = 'JC1.changed' WHERE localAttemptId = ${original.localAttemptId}",
            )
            fail("Expected immutable fallback payload trigger to reject the update")
        } catch (_: SQLiteConstraintException) {
            // Immutable payload columns are protected independently of DAO usage.
        }

        assertEquals(1, database.fallbackAttemptDao().claimForHandoff(original.localAttemptId, 1_010))
        assertEquals(1, database.fallbackAttemptDao().markRetryPending(
            original.localAttemptId, 1, 1_015, FallbackTransportOutcome.RETRYABLE_FAILURE, 1,
        ))
        assertEquals(1, database.fallbackAttemptDao().claimForHandoff(original.localAttemptId, 1_020))
        assertEquals(1, database.fallbackAttemptDao().markPermanentFailure(
            original.localAttemptId, 2, 1_030, 1,
        ))
        assertEquals(0, database.fallbackAttemptDao().claimForHandoff(original.localAttemptId, 1_040))

        val terminal = database.fallbackAttemptDao().allForJourney(JOURNEY_ID).single()
        assertEquals(2, terminal.transportAttemptCount)
        assertEquals(FallbackTransportState.PERMANENT_FAILURE, terminal.transportState)
        assertEquals(original.protectedPayloadText, terminal.protectedPayloadText)
        assertArrayEquals(original.nonce, terminal.nonce)
        assertArrayEquals(original.payloadSha256, terminal.payloadSha256)
    }

    @Test
    fun completionPreventsOrdinaryAllocationAndSupersedesUnsentAttempt() = runBlocking {
        val coordinator = coordinator()
        coordinator.activate(JOURNEY_ID, false)
        now = 1_000
        coordinator.timeAdvanced(JOURNEY_ID)
        database.journeyDao().completeActive(1_100)
        coordinator.journeyCompleted(JOURNEY_ID, 1_100)
        now = 2_000
        coordinator.timeAdvanced(JOURNEY_ID)

        val attempts = database.fallbackAttemptDao().allForJourney(JOURNEY_ID)
        assertEquals(1, attempts.size)
        assertEquals(FallbackTransportState.SUPERSEDED, attempts.single().transportState)
        assertFalse(database.degradedConnectivityDao().get(JOURNEY_ID)!!.journeyActive)
    }

    @Test
    fun missingKeyKeepsFallbackUnavailableAndRoomContainsNoSecretColumn() = runBlocking {
        keyStore.remove(KEY_ID)
        val coordinator = coordinator()
        coordinator.activate(JOURNEY_ID, false)
        now = 1_000
        coordinator.timeAdvanced(JOURNEY_ID)

        assertTrue(database.fallbackAttemptDao().allForJourney(JOURNEY_ID).isEmpty())
        assertEquals(
            FallbackDisposition.UNAVAILABLE,
            database.degradedConnectivityDao().get(JOURNEY_ID)!!.fallbackDisposition,
        )
        listOf("journey_fallback_bindings", "fallback_attempts").forEach { table ->
            database.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
                val names = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertFalse(names.any { it.contains("secret", ignoreCase = true) })
                assertFalse(names.any { it.contains("masterKey", ignoreCase = true) })
                assertFalse(names.any { it.contains("keyMaterial", ignoreCase = true) })
            }
        }
    }

    @Test
    fun bindingAndMutableAttemptConstraintsRejectInvalidRawWrites() = runBlocking {
        val coordinator = coordinator()
        coordinator.activate(JOURNEY_ID, false)
        now = 1_000
        coordinator.timeAdvanced(JOURNEY_ID)

        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE journey_fallback_bindings SET journeyHandle = X'00' WHERE journeyId = '$JOURNEY_ID'",
            )
            fail("Expected invalid Journey handle to be rejected")
        } catch (_: SQLiteConstraintException) {
            // Binding validation trigger is active on a fresh Room database.
        }
        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE fallback_attempts SET transportState = 'INVALID' WHERE journeyId = '$JOURNEY_ID'",
            )
            fail("Expected invalid transport state to be rejected")
        } catch (_: SQLiteConstraintException) {
            // Mutable attempt validation trigger is active on a fresh Room database.
        }
    }

    private fun coordinator(): DegradedConnectivityCoordinator {
        val policy = policy()
        return DegradedConnectivityCoordinator(
            store = RoomDegradedConnectivityStateStore(
                database,
                database.degradedConnectivityDao(),
                database.fallbackAttemptDao(),
                allocator(),
                policy,
            ),
            latestTelemetryReader = { journeyId -> database.telemetryDao().getLatest(journeyId)?.toDomain() },
            fallbackCapabilityReader = FallbackCapabilityReader { journeyId ->
                database.fallbackAttemptDao().getBinding(journeyId)?.let {
                    it.status == FallbackBindingStatus.PROVISIONED && keyStore.hasKey(it.keyId)
                } == true
            },
            policy = policy,
            clock = { now },
        )
    }

    private fun allocator() = DurableFallbackAttemptAllocator(
        database.journeyDao(),
        database.telemetryDao(),
        database.degradedConnectivityDao(),
        database.fallbackAttemptDao(),
        keyStore,
        nonceSource,
        clock = { now },
    )

    private fun policy() = DegradedConnectivityPolicy(
        DegradedConnectivityPolicyConfig(
            degradationAfterMillis = 1_000,
            minimumRetryableCloudFailures = 2,
            recoveryGraceMillis = 500,
            minimumFallbackIntervalMillis = 100,
            fallbackRateWindowMillis = 1_000,
            maximumFallbackAttemptsPerWindow = 4,
        ),
    )

    private fun journey() = Journey(
        id = JOURNEY_ID,
        destination = "Abuja",
        expectedArrivalAt = 10_000,
        startedAt = 0,
        status = JourneyStatus.ACTIVE,
        completedAt = null,
    )

    private fun sample(sequenceTime: Long) = TelemetrySample(
        journeyId = JOURNEY_ID,
        eventTime = sequenceTime,
        latitude = 9.0765,
        longitude = 7.3986,
        accuracyMeters = 12.5f,
        batteryPercent = 64,
        isCharging = false,
        connectivity = ConnectivityState.NONE,
    )

    private class CountingNonceSource : FallbackNonceSource {
        var calls = 0
        override fun nextNonce(): ByteArray = ByteArray(FallbackEnvelopeV1.NONCE_BYTES) {
            (calls + 1).toByte()
        }.also { calls++ }
    }

    private class FakeKeyMaterialStore : FallbackKeyMaterialStore {
        private val keys = mutableMapOf<Long, ByteArray>()
        override fun hasKey(keyId: Long) = keys.containsKey(keyId)
        override fun <T> useKey(keyId: Long, block: (ByteArray) -> T): T? =
            keys[keyId]?.copyOf()?.let { key ->
                try {
                    block(key)
                } finally {
                    key.fill(0)
                }
            }

        override fun provision(keyId: Long, installationFallbackKey: ByteArray) {
            keys[keyId] = installationFallbackKey.copyOf()
        }

        fun remove(keyId: Long) {
            keys.remove(keyId)?.fill(0)
        }
    }

    private companion object {
        const val JOURNEY_ID = "fallback-journey"
        const val KEY_ID = 7L
    }
}
