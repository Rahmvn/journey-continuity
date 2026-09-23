package com.journeycontinuity.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.FallbackDisposition
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private var database: JourneyDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            TEST_DATABASE_1_2,
            TEST_DATABASE_2_3,
            TEST_DATABASE_3_4,
            TEST_DATABASE_4_5,
            TEST_DATABASE_5_6,
            TEST_DATABASE_6_7,
        ).forEach(context::deleteDatabase)
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom1To2PreservesExistingJourney() {
        migrationHelper.createDatabase(TEST_DATABASE_1_2, 1).apply {
            execSQL(
                """INSERT INTO journeys
                    (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('existing', 'Abuja', 5000, 1000, 'ACTIVE', NULL, 1)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_1_2,
            2,
            true,
            MIGRATION_1_2,
        )

        migrated.query("SELECT destination, status FROM journeys WHERE id = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Abuja", cursor.getString(0))
            assertEquals("ACTIVE", cursor.getString(1))
        }
        migrated.query("SELECT COUNT(*) FROM telemetry_observations").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom2To3PreservesJourneysAndTelemetryAndCreatesCheckpoint() {
        migrationHelper.createDatabase(TEST_DATABASE_2_3, 2).apply {
            execSQL(
                """INSERT INTO journeys
                    (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('existing', 'Abuja', 5000, 1000, 'COMPLETED', 4000, NULL)""",
            )
            execSQL(
                """INSERT INTO telemetry_observations
                    (journeyId, sequence, eventTime, latitude, longitude, accuracyMeters,
                     batteryPercent, isCharging, connectivity)
                    VALUES ('existing', 1, 2000, 9.0, 7.0, 12.0, 75, 0, 'NONE')""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_2_3,
            3,
            true,
            MIGRATION_2_3,
        )

        migrated.query("SELECT status, completedAt FROM journeys WHERE id = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("COMPLETED", cursor.getString(0))
            assertEquals(4_000L, cursor.getLong(1))
        }
        migrated.query(
            "SELECT sequence, eventTime FROM telemetry_observations WHERE journeyId = 'existing'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(2_000L, cursor.getLong(1))
        }
        migrated.query(
            """SELECT highestTelemetrySequenceSynced, phase, workRequested
               FROM journey_sync_states WHERE journeyId = 'existing'""",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals("PENDING", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom3To4PreservesEvidenceAndCreatesHeartbeatCounter() {
        migrationHelper.createDatabase(TEST_DATABASE_3_4, 3).apply {
            execSQL(
                """INSERT INTO journeys
                    (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('existing', 'Abuja', 5000, 1000, 'ACTIVE', NULL, 1)""",
            )
            execSQL(
                """INSERT INTO telemetry_observations
                    (journeyId, sequence, eventTime, latitude, longitude, accuracyMeters,
                     batteryPercent, isCharging, connectivity)
                    VALUES ('existing', 1, 2000, 9.0, 7.0, 12.0, 75, 0, 'CELLULAR')""",
            )
            execSQL(
                """INSERT INTO journey_sync_states
                    (journeyId, highestTelemetrySequenceSynced, lastSuccessfulSyncAt,
                     lastAttemptAt, phase, lastError, permanentlyBlocked, changeVersion, workRequested)
                    VALUES ('existing', 1, 2500, 2400, 'IDLE', NULL, 0, 1, 0)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_3_4,
            4,
            true,
            MIGRATION_3_4,
        )

        migrated.query("SELECT status FROM journeys WHERE id = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("ACTIVE", cursor.getString(0))
        }
        migrated.query("SELECT sequence FROM telemetry_observations WHERE journeyId = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        migrated.query(
            """SELECT lastAllocatedHeartbeatSequence, latestCloudHeartbeatSequence, monitoringPhase
               FROM journey_heartbeat_states WHERE journeyId = 'existing'""",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals(true, cursor.isNull(2))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom4To5PreservesJourneysAndTelemetryAndAddsDegradationState() {
        migrationHelper.createDatabase(TEST_DATABASE_4_5, 4).apply {
            execSQL(
                """INSERT INTO journeys
                    (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('existing', 'Abuja', 5000, 1000, 'ACTIVE', NULL, 1)""",
            )
            execSQL(
                """INSERT INTO telemetry_observations
                    (journeyId, sequence, eventTime, latitude, longitude, accuracyMeters,
                     batteryPercent, isCharging, connectivity)
                    VALUES ('existing', 3, 2000, 9.0, 7.0, 12.0, 75, 0, 'CELLULAR')""",
            )
            execSQL(
                """INSERT INTO journey_sync_states
                    (journeyId, highestTelemetrySequenceSynced, lastSuccessfulSyncAt,
                     lastAttemptAt, phase, lastError, permanentlyBlocked, changeVersion, workRequested)
                    VALUES ('existing', 2, 1900, 1800, 'PENDING', NULL, 0, 1, 1)""",
            )
            execSQL(
                """INSERT INTO journey_heartbeat_states
                    (journeyId, lastAllocatedHeartbeatSequence, latestCloudHeartbeatSequence,
                     lastHeartbeatAttemptAt, lastSuccessfulHeartbeatAt, monitoringPhase,
                     lastCloudContactAt, lastError)
                    VALUES ('existing', 1, 1, 1800, 1800, 'EVIDENCE_FRESH', 1800, NULL)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_4_5,
            5,
            true,
            MIGRATION_4_5,
        )

        migrated.query("SELECT status FROM journeys WHERE id = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("ACTIVE", cursor.getString(0))
        }
        migrated.query("SELECT sequence FROM telemetry_observations WHERE journeyId = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM journey_degradation_states").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom5To6PreservesJourneyTelemetryAndDegradationData() {
        migrationHelper.createDatabase(TEST_DATABASE_5_6, 5).apply {
            execSQL(
                """INSERT INTO journeys
                    (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('existing', 'Abuja', 5000, 1000, 'ACTIVE', NULL, 1)""",
            )
            execSQL(
                """INSERT INTO telemetry_observations
                    (journeyId, sequence, eventTime, latitude, longitude, accuracyMeters,
                     batteryPercent, isCharging, connectivity)
                    VALUES ('existing', 3, 2000, 9.0, 7.0, 12.0, 75, 0, 'CELLULAR')""",
            )
            execSQL(
                """INSERT INTO journey_degradation_states
                    (journeyId, journeyActive, connectivityPhase, fallbackDisposition,
                     validatedInternetAvailable, fallbackBindingProvisioned, transportAvailable,
                     degradationEpisodeId, lastDegradationEpisodeId, interruptionStartedAtMillis,
                     lastAuthenticatedCloudSuccessAtMillis, consecutiveRetryableCloudFailures,
                     recoveryStartedAtMillis, nextFallbackEnvelopeSequence, lastFallbackAttemptAtMillis,
                     lastFallbackAttemptEpisodeId, lastCoveredTelemetrySequence, rateWindowStartedAtMillis,
                     fallbackAttemptsInRateWindow, latestTelemetrySequence, latestBatteryPercent)
                    VALUES ('existing', 1, 'DEGRADED', 'UNAVAILABLE', 0, 0, 0, 2, 2,
                            1000, NULL, 2, NULL, 1, NULL, NULL, NULL, NULL, 0, 3, 75)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_5_6,
            6,
            true,
            MIGRATION_5_6,
        )

        migrated.query("SELECT status FROM journeys WHERE id = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("ACTIVE", cursor.getString(0))
        }
        migrated.query("SELECT sequence FROM telemetry_observations WHERE journeyId = 'existing'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
        }
        migrated.query(
            "SELECT connectivityPhase, degradationEpisodeId FROM journey_degradation_states WHERE journeyId = 'existing'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("DEGRADED", cursor.getString(0))
            assertEquals(2L, cursor.getLong(1))
        }
        migrated.query("SELECT COUNT(*) FROM fallback_attempts").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM journey_fallback_bindings").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom6To7PreservesImmutableAttemptAndAddsTransportMetadata() {
        migrationHelper.createDatabase(TEST_DATABASE_6_7, 6).apply {
            execSQL(
                """INSERT INTO journeys
                    (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('existing', 'Abuja', 5000, 1000, 'ACTIVE', NULL, 1)""",
            )
            execSQL(
                """INSERT INTO fallback_attempts
                    (journeyId, degradationEpisodeId, envelopeSequence, telemetrySequence,
                     observationEventTime, eventType, protectedPayloadText, nonce, payloadSha256,
                     allocatedAt, transportState, transportAttemptCount, lastAttemptAt, terminalAt)
                    VALUES ('existing', 1, 1, 3, 2000, 'OBSERVATION',
                            'JC1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
                            zeroblob(12), zeroblob(32), 2100, 'ALLOCATED', 0, NULL, NULL)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_6_7,
            7,
            true,
            MIGRATION_6_7,
        )
        migrated.query(
            """SELECT envelopeSequence, telemetrySequence, handoffGeneration, handoffStartedAt,
                      nextRetryAt, lastTransportOutcome, lastTransportResultCode, uncertainSince
               FROM fallback_attempts WHERE journeyId = 'existing'""",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(3L, cursor.getLong(1))
            assertEquals(0, cursor.getInt(2))
            for (index in 3..7) assertEquals(true, cursor.isNull(index))
        }
        migrated.close()
    }

    @Test
    fun sequencesAreJourneyScopedUniqueAndCompletionRejectsNewTelemetry() = runBlocking {
        val db = createInMemoryDatabase()
        val journeyDao = db.journeyDao()
        val telemetryDao = db.telemetryDao()

        assertEquals(true, journeyDao.insertIfNoActive(journey("one").toEntity()))
        val oneFirst = telemetryDao.insertForActiveJourney(sample("one", 1_000L))
        val oneSecond = telemetryDao.insertForActiveJourney(sample("one", 2_000L))
        assertEquals(1L, oneFirst?.sequence)
        assertEquals(2L, oneSecond?.sequence)
        assertEquals("one", oneSecond?.journeyId)

        assertNotNull(journeyDao.completeActive(3_000L))
        assertNull(telemetryDao.insertForActiveJourney(sample("one", 4_000L)))
        assertEquals(2L, telemetryDao.observeCount("one").first())

        assertEquals(true, journeyDao.insertIfNoActive(journey("two").toEntity()))
        val twoFirst = telemetryDao.insertForActiveJourney(sample("two", 5_000L))
        assertEquals(1L, twoFirst?.sequence)
        assertEquals("two", twoFirst?.journeyId)

        try {
            db.openHelper.writableDatabase.execSQL(
                """INSERT INTO telemetry_observations
                    (journeyId, sequence, eventTime, latitude, longitude, accuracyMeters,
                     batteryPercent, isCharging, connectivity)
                    VALUES ('two', 1, 6000, 9.0, 7.0, 8.0, 70, 0, 'NONE')""",
            )
            fail("Expected duplicate journey sequence to violate the unique index")
        } catch (_: SQLiteConstraintException) {
            // Database-level uniqueness is the expected protection.
        }
    }

    @Test
    fun heartbeatSequencePersistsAndCompletionRejectsFurtherAllocation() = runBlocking {
        val db = createInMemoryDatabase()
        val journeyDao = db.journeyDao()
        val heartbeatDao = db.heartbeatDao()

        assertEquals(true, journeyDao.insertIfNoActive(journey("heartbeat").toEntity()))
        assertEquals(
            1L,
            heartbeatDao.allocateForActiveJourney("heartbeat", 10_000, 0)?.sequence,
        )
        assertEquals(
            2L,
            heartbeatDao.allocateForActiveJourney("heartbeat", 20_000, 0)?.sequence,
        )
        assertNotNull(journeyDao.completeActive(30_000))
        assertNull(heartbeatDao.allocateForActiveJourney("heartbeat", 40_000, 0))
    }

    @Test
    fun degradationStateIsUniquePerJourneyAndDoesNotBlockTelemetryPersistence() = runBlocking {
        val db = createInMemoryDatabase()
        assertEquals(true, db.journeyDao().insertIfNoActive(journey("degraded").toEntity()))
        val dao = db.degradedConnectivityDao()
        val initial = JourneyDegradationStateEntity(
            journeyId = "degraded",
            journeyActive = true,
            connectivityPhase = ConnectivityPhase.INTERRUPTED,
            fallbackDisposition = FallbackDisposition.INACTIVE,
            validatedInternetAvailable = false,
            fallbackBindingProvisioned = false,
            transportAvailable = false,
            degradationEpisodeId = null,
            lastDegradationEpisodeId = 0,
            interruptionStartedAtMillis = 1_000,
            lastAuthenticatedCloudSuccessAtMillis = null,
            consecutiveRetryableCloudFailures = 0,
            recoveryStartedAtMillis = null,
            nextFallbackEnvelopeSequence = 1,
            lastFallbackAttemptAtMillis = null,
            lastFallbackAttemptEpisodeId = null,
            lastCoveredTelemetrySequence = null,
            rateWindowStartedAtMillis = null,
            fallbackAttemptsInRateWindow = 0,
            latestTelemetrySequence = null,
            latestBatteryPercent = null,
        )
        dao.upsert(initial)
        dao.upsert(
            initial.copy(
                connectivityPhase = ConnectivityPhase.DEGRADED,
                fallbackDisposition = FallbackDisposition.UNAVAILABLE,
                degradationEpisodeId = 1,
                lastDegradationEpisodeId = 1,
            ),
        )

        assertNotNull(db.telemetryDao().insertForActiveJourney(sample("degraded", 2_000)))
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM journey_degradation_states WHERE journeyId = 'degraded'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        assertEquals(1L, db.telemetryDao().observeCount("degraded").first())
    }

    private fun createInMemoryDatabase(): JourneyDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK)
            .build()
            .also { database = it }
    }

    private fun journey(id: String) = Journey(
        id = id,
        destination = "Destination $id",
        expectedArrivalAt = 10_000L,
        startedAt = 500L,
        status = JourneyStatus.ACTIVE,
        completedAt = null,
    )

    private fun sample(journeyId: String, eventTime: Long) = TelemetrySample(
        journeyId = journeyId,
        eventTime = eventTime,
        latitude = 9.0765,
        longitude = 7.3986,
        accuracyMeters = 12.5f,
        batteryPercent = 64,
        isCharging = false,
        connectivity = ConnectivityState.CELLULAR,
    )

    private companion object {
        const val TEST_DATABASE_1_2 = "journey-migration-1-2-test"
        const val TEST_DATABASE_2_3 = "journey-migration-2-3-test"
        const val TEST_DATABASE_3_4 = "journey-migration-3-4-test"
        const val TEST_DATABASE_4_5 = "journey-migration-4-5-test"
        const val TEST_DATABASE_5_6 = "journey-migration-5-6-test"
        const val TEST_DATABASE_6_7 = "journey-migration-6-7-test"
    }
}
