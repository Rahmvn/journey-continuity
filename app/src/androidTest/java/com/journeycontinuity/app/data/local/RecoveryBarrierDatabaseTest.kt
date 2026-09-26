package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.degraded.*
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.sync.LEGACY_AUTHORIZATION_ERRORS
import com.journeycontinuity.app.sync.RoomLocalSyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only isolated test databases; never opens the installed Journey database. */
@RunWith(AndroidJUnit4::class)
class RecoveryBarrierDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), JourneyDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationRetainsLegacyBlockAndCapturesOutstandingTarget() {
        val name = "recovery-barrier-migration-test.db"
        try {
            migrations.createDatabase(name, 7).apply {
                execSQL("INSERT INTO journeys VALUES ('test', 'Synthetic', 5000, 1000, 'ACTIVE', NULL, 1)")
                execSQL("""INSERT INTO telemetry_observations
                    (journeyId, sequence, eventTime, latitude, longitude, accuracyMeters, batteryPercent, isCharging, connectivity)
                    VALUES ('test', 9, 2000, 0, 0, 1, NULL, NULL, 'NONE')""")
                execSQL("""INSERT INTO journey_sync_states
                    VALUES ('test', 2, 1000, 1100, 'ERROR', ?, 1, 9, 0)""",
                    arrayOf(LEGACY_AUTHORIZATION_ERRORS.first()))
                execSQL("""INSERT INTO journey_degradation_states
                    (journeyId, journeyActive, connectivityPhase, fallbackDisposition,
                     validatedInternetAvailable, fallbackBindingProvisioned, transportAvailable,
                     degradationEpisodeId, lastDegradationEpisodeId, interruptionStartedAtMillis,
                     lastAuthenticatedCloudSuccessAtMillis, consecutiveRetryableCloudFailures,
                     recoveryStartedAtMillis, nextFallbackEnvelopeSequence, lastFallbackAttemptAtMillis,
                     lastFallbackAttemptEpisodeId, lastCoveredTelemetrySequence, rateWindowStartedAtMillis,
                     fallbackAttemptsInRateWindow, latestTelemetrySequence, latestBatteryPercent)
                    VALUES ('test', 1, 'HEALTHY', 'INACTIVE', 1, 0, 0, NULL, 7,
                            NULL, 1200, 0, NULL, 10, NULL, 7, 9, NULL, 0, 9, NULL)""")
                close()
            }
            migrations.runMigrationsAndValidate(name, 8, true, MIGRATION_7_8).use { migrated ->
                migrated.query("SELECT connectivityPhase, recoveryTargetTelemetrySequence, recoveryBacklogSatisfiedAtMillis FROM journey_degradation_states").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("RECOVERING", it.getString(0))
                    assertEquals(9L, it.getLong(1))
                    assertTrue(it.isNull(2))
                }
                migrated.query("SELECT highestTelemetrySequenceSynced, permanentlyBlocked, workRequested, legacyAuthorizationRecoveredAtMillis FROM journey_sync_states").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(2L, it.getLong(0))
                    assertEquals(1, it.getInt(1))
                    assertEquals(0, it.getInt(2))
                    assertTrue(it.isNull(3))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun barrierAndAuthorizationDiagnosticSurviveDatabaseReopen() = runBlocking {
        val name = "recovery-barrier-reopen-test.db"
        fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
        var database = open()
        var now = 1000L
        val policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
        val keys = object : FallbackKeyMaterialStore {
            override fun provision(keyId: Long, installationFallbackKey: ByteArray) = error("No test provisioning")
            override fun hasKey(keyId: Long) = false
            override fun <T> useKey(keyId: Long, block: (ByteArray) -> T): T? = null
        }
        fun coordinator(): DegradedConnectivityCoordinator = DegradedConnectivityCoordinator(
            RoomDegradedConnectivityStateStore(database, database.degradedConnectivityDao(), database.fallbackAttemptDao(),
                DurableFallbackAttemptAllocator(database.journeyDao(), database.telemetryDao(),
                    database.degradedConnectivityDao(), database.fallbackAttemptDao(), keys), policy),
            latestTelemetryReader = { database.telemetryDao().getLatest(it)?.toDomain() },
            policy = policy, clock = { now },
        )
        try {
            database.journeyDao().insertIfNoActive(JourneyEntity("test", "Synthetic", 10000, 1, JourneyStatus.ACTIVE, null, 1))
            repeat(3) { database.telemetryDao().insertForActiveJourney(
                TelemetrySample("test", 10L + it, 0.0, 0.0, 1f, null, null, ConnectivityState.NONE)) }
            database.syncStateDao().advanceCheckpoint("test", 2)
            database.syncStateDao().markSyncing("test", 100)
            database.syncStateDao().markFailure("test", LEGACY_AUTHORIZATION_ERRORS.first(), true)
            coordinator().activate("test", true)
            now++
            coordinator().freshHeartbeatSucceeded("test", now)
            assertEquals(ConnectivityPhase.RECOVERING, database.degradedConnectivityDao().get("test")!!.connectivityPhase)
            database.close()
            database = open()
            coordinator().activate("test", true)
            assertEquals(3L, database.degradedConnectivityDao().get("test")!!.recoveryTargetTelemetrySequence)
            assertTrue(database.fallbackAttemptDao().allForJourney("test").isEmpty())
            val local = RoomLocalSyncStore(database.journeyDao(), database.telemetryDao(), database.syncStateDao())
            val block = local.legacyAuthorizationBlocks().single()
            assertTrue(local.hasOutstandingWork())
            assertNull(local.nextCandidate())
            assertFalse(local.reactivateLegacyAuthorization(block.copy(lastError = "unrelated"), now))
            assertTrue(local.reactivateLegacyAuthorization(block, now))
            assertEquals(2L, local.checkpoint("test"))
            val restored = database.syncStateDao().get("test")!!
            assertEquals(block.lastError, restored.legacyAuthorizationFailure)
            assertEquals(100L, restored.legacyAuthorizationFailureAtMillis)
            assertEquals(now, restored.legacyAuthorizationRecoveredAtMillis)
            database.syncStateDao().advanceCheckpoint("test", 3)
            now++
            coordinator().timeAdvanced("test")
            val satisfied = database.degradedConnectivityDao().get("test")!!.recoveryBacklogSatisfiedAtMillis
            database.close()
            database = open()
            coordinator().activate("test", true)
            assertEquals(satisfied, database.degradedConnectivityDao().get("test")!!.recoveryBacklogSatisfiedAtMillis)
            assertEquals(ConnectivityPhase.RECOVERING, database.degradedConnectivityDao().get("test")!!.connectivityPhase)
            now++
            coordinator().freshHeartbeatSucceeded("test", now)
            assertEquals(ConnectivityPhase.HEALTHY, database.degradedConnectivityDao().get("test")!!.connectivityPhase)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
