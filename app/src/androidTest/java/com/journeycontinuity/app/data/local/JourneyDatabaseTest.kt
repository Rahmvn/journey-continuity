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
import com.journeycontinuity.app.domain.DestinationKind
import com.journeycontinuity.app.domain.DestinationSelectionSource
import com.journeycontinuity.app.domain.StructuredDestination
import com.journeycontinuity.app.domain.AdminLocalUnitType
import com.journeycontinuity.app.domain.DestinationProviderAttribution
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
            TEST_DATABASE_9_10,
            TEST_DATABASE_10_11,
            TEST_DATABASE_11_12,
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
    @Throws(IOException::class)
    fun migrationFrom9To10PreservesCompletedHistoryWithoutInventingEndTime() {
        migrationHelper.createDatabase(TEST_DATABASE_9_10, 9).use { db ->
            db.execSQL("""INSERT INTO journeys
                (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                VALUES ('old-completed', 'Abuja', 5000, 1000, 'COMPLETED', 4000, NULL)""")
        }
        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_9_10, 10, true, MIGRATION_9_10,
        ).use { db ->
            db.query("SELECT status, completedAt, endedAt FROM journeys WHERE id = 'old-completed'").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("COMPLETED", row.getString(0))
                assertEquals(4_000L, row.getLong(1))
                assertEquals(true, row.isNull(2))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom10To11PreservesTextExactlyAndDoesNotInventStructure() {
        migrationHelper.createDatabase(TEST_DATABASE_10_11, 10).use { db ->
            db.execSQL("""INSERT INTO journeys
                (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot, endedAt)
                VALUES ('old-active', '  Ilorin  ', 5000, 1000, 'ACTIVE', NULL, 1, NULL)""")
        }
        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_10_11, 11, true, MIGRATION_10_11,
        ).use { db ->
            db.query("""SELECT destination, expectedArrivalAt, startedAt, status, activeSlot,
                destinationCountryCode IS NULL AND destinationStateId IS NULL AND
                destinationStateName IS NULL AND destinationLgaId IS NULL AND
                destinationLgaName IS NULL AND destinationKind IS NULL AND
                destinationLatitude IS NULL AND destinationLongitude IS NULL AND
                destinationSelectionSource IS NULL AND destinationProvider IS NULL AND
                destinationProviderPlaceId IS NULL AND destinationAdminDatasetVersion IS NULL AND
                destinationCustomPlaceName IS NULL
                FROM journeys WHERE id = 'old-active'""").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("  Ilorin  ", row.getString(0))
                assertEquals(5_000L, row.getLong(1))
                assertEquals(1_000L, row.getLong(2))
                assertEquals("ACTIVE", row.getString(3))
                assertEquals(1, row.getInt(4))
                assertEquals(1, row.getInt(5))
            }
            db.execSQL("UPDATE journeys SET status = 'COMPLETED', completedAt = 4000, activeSlot = NULL WHERE id = 'old-active'")
            db.query("SELECT status, completedAt, destination FROM journeys WHERE id = 'old-active'").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("COMPLETED", row.getString(0))
                assertEquals(4_000L, row.getLong(1))
                assertEquals("  Ilorin  ", row.getString(2))
            }
        }
    }

    @Test fun migrationFrom11To12PreservesLegacyAndAllUnknownFacts() {
        var originalLabelBytes: String? = null
        var originalIndexes: Map<String, String> = emptyMap()
        migrationHelper.createDatabase(TEST_DATABASE_11_12, 11).use { db ->
            db.execSQL("""INSERT INTO journeys
                (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot, endedAt)
                VALUES ('legacy-12', '  Ilorin  ', 5000, 1000, 'ACTIVE', NULL, 1, NULL)""")
            db.query("SELECT hex(CAST(destination AS BLOB)) FROM journeys WHERE id = 'legacy-12'").use { row ->
                check(row.moveToFirst()); originalLabelBytes = row.getString(0)
            }
            originalIndexes = databaseDefinitions(db, "index")
            db.execSQL("""CREATE TRIGGER migration_canary BEFORE UPDATE OF id ON journeys
                BEGIN SELECT RAISE(ABORT, 'preserved trigger'); END""")
        }
        migrationHelper.runMigrationsAndValidate(TEST_DATABASE_11_12, 12, true, MIGRATION_11_12).use { db ->
            assertEquals(originalIndexes, databaseDefinitions(db, "index"))
            assertEquals(true, databaseDefinitions(db, "trigger").containsKey("migration_canary"))
            db.query("SELECT hex(CAST(destination AS BLOB)) FROM journeys WHERE id = 'legacy-12'").use { row ->
                check(row.moveToFirst()); assertEquals(originalLabelBytes, row.getString(0))
            }
            val destinationTriggers = databaseDefinitions(db, "trigger").filterKeys { it.startsWith("journeys_destination_") }
            assertEquals(3, destinationTriggers.size)
            listOf("destinationLocalUnitType", "destinationBoundaryDatasetId",
                "destinationBoundaryDatasetVersion", "destinationProviderAttribution").forEach { field ->
                destinationTriggers.values.forEach { sql -> assertEquals("Trigger must retain $field", true, sql.contains(field)) }
            }
            db.query("SELECT * FROM journeys WHERE id = 'legacy-12'").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("  Ilorin  ", row.getString(row.getColumnIndexOrThrow("destination")))
                assertEquals(5000L, row.getLong(row.getColumnIndexOrThrow("expectedArrivalAt")))
                assertEquals(1000L, row.getLong(row.getColumnIndexOrThrow("startedAt")))
                row.columnNames.filter { it.startsWith("destination") && it != "destination" }.forEach {
                    assertEquals("No invented $it", true, row.isNull(row.getColumnIndexOrThrow(it)))
                }
            }
            db.execSQL("UPDATE journeys SET status = 'CANCELLED', endedAt = 4000, activeSlot = NULL WHERE id = 'legacy-12'")
            db.query("SELECT destination, status, endedAt FROM journeys WHERE id = 'legacy-12'").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("  Ilorin  ", row.getString(0))
                assertEquals("CANCELLED", row.getString(1))
                assertEquals(4000L, row.getLong(2))
            }
        }
    }

    @Test fun destinationTriggerRecreationRollsBackOnFailure() = runBlocking {
        val db = createInMemoryDatabase()
        val sqlite = db.openHelper.writableDatabase
        assertEquals(true, db.journeyDao().insertIfNoActive(journey("atomic-triggers").copy(structuredDestination = evidencePoint()).toEntity()))
        val before = databaseDefinitions(sqlite, "trigger").filterKeys { it.startsWith("journeys_destination_") }
        val failing = object : androidx.sqlite.db.SupportSQLiteDatabase by sqlite {
            override fun execSQL(sql: String) {
                if (sql.startsWith("CREATE TRIGGER `journeys_destination_validate_update`")) {
                    throw android.database.sqlite.SQLiteException("Simulated trigger recreation failure")
                }
                sqlite.execSQL(sql)
            }
        }
        try { FALLBACK_ATTEMPT_INVARIANT_CALLBACK.onOpen(failing); fail("Expected simulated recreation failure") }
        catch (error: android.database.sqlite.SQLiteException) {
            assertEquals("Simulated trigger recreation failure", error.message)
        }
        assertEquals(false, sqlite.inTransaction())
        assertEquals(before, databaseDefinitions(sqlite, "trigger").filterKeys { it.startsWith("journeys_destination_") })
        try {
            sqlite.execSQL("UPDATE journeys SET destinationBoundaryDatasetVersion = 'changed' WHERE id = 'atomic-triggers'")
            fail("Original destination protection must survive rollback")
        } catch (_: android.database.sqlite.SQLiteException) { /* protection survived */ }
        assertNotNull(db.journeyDao().completeActive(2000))
        FALLBACK_ATTEMPT_INVARIANT_CALLBACK.onOpen(sqlite)
        assertEquals(before, databaseDefinitions(sqlite, "trigger").filterKeys { it.startsWith("journeys_destination_") })
    }

    private fun databaseDefinitions(db: androidx.sqlite.db.SupportSQLiteDatabase, type: String): Map<String, String> =
        buildMap {
            db.query("SELECT name, sql FROM sqlite_master WHERE type = ? AND sql IS NOT NULL ORDER BY name", arrayOf(type)).use { rows ->
                while (rows.moveToNext()) put(rows.getString(0), rows.getString(1))
            }
        }

    @Test fun everySubtypeRoundTripsNewEvidenceAndLifecycleUpdates() = runBlocking {
        val db = createInMemoryDatabase()
        val attribution = DestinationProviderAttribution("OpenStreetMap", "? OpenStreetMap contributors", "ODbL", "https://www.openstreetmap.org/copyright")
        val reverse = evidencePoint().copy(provider = "GEOAPIFY", providerAttribution = attribution)
        listOf(evidencePoint(), reverse, reverse.copy(providerPlaceId = "reverse-id"),
            reverse.copy(selectionSource = DestinationSelectionSource.PLACE_SEARCH, providerPlaceId = "place-id"),
            evidenceLocality(), evidenceLocality().copy(stateId = "ng-fct", stateName = "Federal Capital Territory",
                localUnitId = "ng-fct-abuja-municipal", localUnitName = "Abuja Municipal Area Council",
                localUnitType = AdminLocalUnitType.AREA_COUNCIL)).forEachIndexed { index, facts ->
            val id = "evidence-$index"
            assertEquals(true, db.journeyDao().insertIfNoActive(journey(id).copy(structuredDestination = facts).toEntity()))
            assertEquals(facts, db.journeyDao().getActive()?.toDomain()?.structuredDestination)
            assertNotNull(db.journeyDao().completeActive(2000))
            db.openHelper.readableDatabase.query("SELECT destinationLocalUnitType, destinationBoundaryDatasetId, destinationBoundaryDatasetVersion, destinationProviderAttribution FROM journeys WHERE id = ?", arrayOf(id)).use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals(facts.localUnitType?.name, row.getString(0))
                assertEquals(facts.boundaryDatasetId, if (row.isNull(1)) null else row.getString(1))
                assertEquals(facts.boundaryDatasetVersion, if (row.isNull(2)) null else row.getString(2))
                if (facts.providerAttribution == null) assertEquals(true, row.isNull(3))
                else assertEquals(facts.providerAttribution, kotlinx.serialization.json.Json.decodeFromString<DestinationProviderAttribution>(row.getString(3)))
            }
        }
    }

    @Test fun everyNewEvidenceFieldIsImmutableIncludingNullProviderEvidence() = runBlocking {
        val db = createInMemoryDatabase()
        assertEquals(true, db.journeyDao().insertIfNoActive(journey("frozen").copy(structuredDestination = evidencePoint()).toEntity()))
        listOf("destinationLocalUnitType = 'AREA_COUNCIL'", "destinationBoundaryDatasetId = 'other'",
            "destinationBoundaryDatasetVersion = 'other'", "destinationProviderAttribution = '{}'",
            "destinationProvider = 'GEOAPIFY'", "destinationProviderPlaceId = 'enriched'").forEach { mutation ->
            try {
                db.openHelper.writableDatabase.execSQL("UPDATE journeys SET $mutation WHERE id = 'frozen'")
                fail("Frozen evidence changed: $mutation")
            } catch (error: android.database.sqlite.SQLiteException) {
                assertEquals(true, error.message?.contains("Journey destination is immutable"))
            }
        }
        assertEquals(evidencePoint(), db.journeyDao().getActive()?.toDomain()?.structuredDestination)
        assertNotNull(db.journeyDao().completeActive(2000))
    }

    private fun evidencePoint() = StructuredDestination("NG", "ng-kwara", "Kwara", "ng-kwara-offa", "Offa",
        DestinationKind.POINT, 8.1560008, 4.7227435, DestinationSelectionSource.MAP_PIN,
        adminDatasetVersion = "alabarin-2026-10-05-v2", localUnitType = AdminLocalUnitType.LGA,
        boundaryDatasetId = "NG_OPERATIONAL_BOUNDARIES", boundaryDatasetVersion = "alabarin-2026-10-03-v1")

    private fun evidenceLocality() = StructuredDestination("NG", "ng-kwara", "Kwara", "ng-kwara-offa", "Offa",
        DestinationKind.LOCALITY, null, null, DestinationSelectionSource.LOCALITY_SELECTION,
        adminDatasetVersion = "alabarin-2026-10-05-v2", localUnitType = AdminLocalUnitType.LGA)

    @Test fun structuredPointAndLocalityRoundTripAndRemainImmutable() = runBlocking {
        val db = createInMemoryDatabase()
        val dao = db.journeyDao()
        val point = evidencePoint().copy(customPlaceName = "Grandma's house")
        assertEquals(true, dao.insertIfNoActive(journey("point").copy(
            destination = "Grandma's house", structuredDestination = point).toEntity()))
        assertEquals(point, dao.getActive()?.toDomain()?.structuredDestination)
        dao.getActive()?.let { current ->
            db.openHelper.writableDatabase.execSQL(
                "UPDATE journeys SET status = 'ACTIVE' WHERE id = '${current.id}'",
            )
        }
        assertEquals(false, dao.insertIfNoActive(journey("blocked").toEntity()))
        assertNotNull(dao.completeActive(2_000))
        val locality = evidenceLocality()
        assertEquals(true, dao.insertIfNoActive(journey("locality").copy(
            destination = "Offa", structuredDestination = locality).toEntity()))
        assertEquals(locality, dao.getActive()?.toDomain()?.structuredDestination)
        assertNull(dao.getActive()?.destinationLatitude)
        try {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE journeys SET destinationLatitude = 8.50 WHERE id = 'point'",
            )
            fail("Stored destination point must be immutable")
        } catch (_: android.database.sqlite.SQLiteException) { /* expected */ }
    }

    @Test fun rawRoomInsertRejectsHalfSelectedPoint() {
        val db = createInMemoryDatabase()
        try {
            db.openHelper.writableDatabase.execSQL("""INSERT INTO journeys
                (id, destination, expectedArrivalAt, startedAt, status, activeSlot,
                 destinationCountryCode, destinationStateId, destinationStateName,
                 destinationLgaId, destinationLgaName, destinationKind,
                 destinationLatitude, destinationSelectionSource)
                VALUES ('invalid', 'Bad point', 5000, 1000, 'ACTIVE', 1,
                        'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'POINT',
                        8.49, 'MAP_PIN')""")
            fail("A half-selected point must not be stored")
        } catch (_: android.database.sqlite.SQLiteException) { /* expected */ }
        assertNull(runBlocking { db.journeyDao().getActive() })
    }

    @Test fun rawRoomWritesRejectPartialFactsAndLocalityCoordinates() {
        val db = createInMemoryDatabase()
        val sql = listOf(
            """INSERT INTO journeys (id, destination, expectedArrivalAt, startedAt, status,
                destinationStateId) VALUES ('partial-state', 'Offa', 5000, 1000, 'COMPLETED', 'NG-KW')""",
            """INSERT INTO journeys (id, destination, expectedArrivalAt, startedAt, status,
                destinationLatitude, destinationLongitude)
                VALUES ('partial-coordinates', 'Offa', 5000, 1000, 'COMPLETED', 8.49, 4.56)""",
            """INSERT INTO journeys (id, destination, expectedArrivalAt, startedAt, status,
                destinationCountryCode, destinationStateId, destinationStateName,
                destinationLgaId, destinationLgaName, destinationKind,
                destinationLatitude, destinationLongitude, destinationSelectionSource)
                VALUES ('locality-point', 'Offa', 5000, 1000, 'COMPLETED',
                    'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'LOCALITY',
                    8.49, 4.56, 'LOCALITY_SELECTION')""",
        )
        sql.forEach { statement ->
            try {
                db.openHelper.writableDatabase.execSQL(statement)
                fail("Partial or imprecise destination facts must not persist")
            } catch (_: android.database.sqlite.SQLiteException) { /* expected */ }
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM journeys").use { row ->
            assertEquals(true, row.moveToFirst())
            assertEquals(0L, row.getLong(0))
        }
    }

    @Test
    fun monitoringStopIsDurableAndRejectsNewEvidence() = runBlocking {
        val db = createInMemoryDatabase()
        val dao = db.journeyDao()
        assertEquals(true, dao.insertIfNoActive(journey("stopped").toEntity()))
        assertNotNull(db.telemetryDao().insertForActiveJourney(sample("stopped", 1_000L)))
        assertNotNull(db.heartbeatDao().allocateForActiveJourney("stopped", 1_000L, 0))
        assertNull(dao.stopMonitoringActive("another-journey", 2_000L))
        assertEquals(JourneyStatus.ACTIVE, dao.getActive()?.status)

        val stopped = dao.stopMonitoringActive("stopped", 2_000L)

        assertEquals(JourneyStatus.CANCELLED, stopped?.status)
        assertEquals(2_000L, stopped?.endedAt)
        assertNull(stopped?.completedAt)
        assertNull(stopped?.activeSlot)
        assertNull(dao.observeActive().first())
        assertEquals(stopped, dao.getById("stopped"))
        assertNull(db.telemetryDao().insertForActiveJourney(sample("stopped", 3_000L)))
        assertNull(db.heartbeatDao().allocateForActiveJourney("stopped", 3_000L, 0))
        assertEquals(1L, db.telemetryDao().observeCount("stopped").first())
        assertNull(dao.stopMonitoringActive("stopped", 4_000L))
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
        const val TEST_DATABASE_9_10 = "journey-migration-9-10-test"
        const val TEST_DATABASE_10_11 = "journey-migration-10-11-test"
        const val TEST_DATABASE_11_12 = "journey-migration-11-12-test"
    }
}
