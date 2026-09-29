package com.journeycontinuity.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnerAdoptionStateDatabaseTest {
    @Test fun emptyActiveAndCompletedJourneySnapshots() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        try {
            val state = database.ownerAdoptionStateDao()
            assertFalse(state.hasAnyOwnerScopedRows())
            database.openHelper.writableDatabase.execSQL(
                """INSERT INTO journeys
                   (id, destination, expectedArrivalAt, startedAt, status, activeSlot)
                   VALUES ('journey-a', 'Abuja', 200, 100, 'ACTIVE', 1)""",
            )
            assertTrue(state.hasAnyOwnerScopedRows())
            database.openHelper.writableDatabase.execSQL(
                """UPDATE journeys SET status = 'COMPLETED', completedAt = 300,
                   activeSlot = NULL WHERE id = 'journey-a'""",
            )
            assertTrue(state.hasAnyOwnerScopedRows())
        } finally {
            database.close()
        }
    }

    @Test fun orphanedEvidenceAndFallbackRowsEachBlockAdoption() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java).build()
        try {
            val sqlite = database.openHelper.writableDatabase
            // Model a partial restore: SQLite tables survived but the parent
            // Journey and owner preference did not. Production never creates
            // these rows without a Journey.
            sqlite.setForeignKeyConstraintsEnabled(false)
            val rows = listOf(
                "telemetry_observations" to """INSERT INTO telemetry_observations
                    (journeyId, sequence, eventTime, latitude, longitude, accuracyMeters, connectivity)
                    VALUES ('lost-owner', 1, 100, 0, 0, 10, 'UNKNOWN')""",
                "journey_sync_states" to """INSERT INTO journey_sync_states
                    (journeyId, highestTelemetrySequenceSynced, phase, permanentlyBlocked,
                     changeVersion, workRequested)
                    VALUES ('lost-owner', 0, 'PENDING', 0, 1, 1)""",
                "journey_heartbeat_states" to """INSERT INTO journey_heartbeat_states
                    (journeyId, lastAllocatedHeartbeatSequence, latestCloudHeartbeatSequence)
                    VALUES ('lost-owner', 0, 0)""",
                "journey_degradation_states" to """INSERT INTO journey_degradation_states
                    (journeyId, journeyActive, connectivityPhase, fallbackDisposition,
                     validatedInternetAvailable, fallbackBindingProvisioned, transportAvailable,
                     lastDegradationEpisodeId, consecutiveRetryableCloudFailures,
                     nextFallbackEnvelopeSequence, fallbackAttemptsInRateWindow)
                    VALUES ('lost-owner', 0, 'DEGRADED', 'UNAVAILABLE', 0, 0, 0, 1, 0, 1, 0)""",
                "journey_fallback_bindings" to """INSERT INTO journey_fallback_bindings
                    (journeyId, keyId, journeyHandle, status, provisionedAt)
                    VALUES ('lost-owner', 1, X'00112233445566778899AABBCCDDEEFF', 'ACTIVE', 100)""",
                "fallback_attempts" to """INSERT INTO fallback_attempts
                    (journeyId, degradationEpisodeId, envelopeSequence, telemetrySequence,
                     observationEventTime, eventType, protectedPayloadText, nonce,
                     payloadSha256, allocatedAt, transportState, transportAttemptCount,
                     handoffGeneration)
                    VALUES ('lost-owner', 1, 1, 1, 100, 'OBSERVATION', 'test', X'000000000000000000000000',
                     X'0000000000000000000000000000000000000000000000000000000000000000',
                     100, 'ALLOCATED', 0, 0)""",
            )
            for ((table, insert) in rows) {
                assertFalse(database.ownerAdoptionStateDao().hasAnyOwnerScopedRows())
                sqlite.execSQL(insert)
                assertTrue("$table must block adoption", database.ownerAdoptionStateDao().hasAnyOwnerScopedRows())
                sqlite.execSQL("DELETE FROM $table")
            }
        } finally {
            database.close()
        }
    }
}
