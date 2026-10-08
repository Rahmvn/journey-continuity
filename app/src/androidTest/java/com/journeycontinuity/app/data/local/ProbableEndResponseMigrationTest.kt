package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbableEndResponseMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun v15To16PreservesReadyAndPresentedWithoutFabricatingResponses() {
        val name = "probable-end-response-migration-test.db"
        try {
            migrations.createDatabase(name, 15).use { old ->
                for (i in 1..2) {
                    old.execSQL("""INSERT INTO journeys(id,destination,expectedArrivalAt,startedAt,status,activeSlot)
                        VALUES('journey$i','Synthetic destination',5000000,1000,'ACTIVE',?)""",
                        arrayOf<Any?>(if (i == 1) 1 else null))
                    old.execSQL("""INSERT INTO probable_end_episodes(
                        id,journeyId,policyVersion,evidenceClass,createdAtMillis,generation,
                        evidenceFloorSequence,evidenceFloorEventTimeMillis,lifecycle,liveSlot,
                        first_telemetrySequence,first_observedAtMillis,first_fingerprint,
                        final_telemetrySequence,final_observedAtMillis,final_fingerprint,
                        observationCount,finalDistanceMeters,finalAccuracyMeters,destinationSlot)
                        VALUES(?,?,'alabarin-probable-end-v1','SYSTEM_INFERENCE',181000,0,0,0,
                        'CANDIDATE',1,1,1000,?,4,181000,?,4,0,8,'structured-destination-at-creation')""",
                        arrayOf("candidate$i", "journey$i", "a".repeat(64), "b".repeat(64)))
                    old.execSQL("""INSERT INTO probable_end_presentations(
                        id,journeyId,candidateEpisodeId,generation,policyVersion,
                        historyBaselineAtMillis,guardVersion,status,liveSlot,createdAtMillis,
                        presentedAtMillis,revision)
                        VALUES(?,?,?,0,'alabarin-probable-end-v1',1000,0,?,1,182000,?,?)""",
                        arrayOf<Any?>("presentation$i", "journey$i", "candidate$i",
                            if (i == 1) "READY_TO_PRESENT" else "PRESENTED",
                            if (i == 1) null else 183000L, if (i == 1) 0L else 1L))
                }
            }
            migrations.runMigrationsAndValidate(name, 16, true, MIGRATION_15_16).use { migrated ->
                migrated.query("""SELECT id,status,presentedAtMillis,respondedAtMillis,
                    resultAcknowledgedAtMillis,completionEffectsAppliedAtMillis
                    FROM probable_end_presentations ORDER BY id""").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("presentation1", rows.getString(0)); assertEquals("READY_TO_PRESENT", rows.getString(1))
                    assertTrue(rows.isNull(2)); assertTrue(rows.isNull(3)); assertTrue(rows.isNull(4)); assertTrue(rows.isNull(5))
                    assertTrue(rows.moveToNext())
                    assertEquals("presentation2", rows.getString(0)); assertEquals("PRESENTED", rows.getString(1))
                    assertEquals(183000L, rows.getLong(2)); assertTrue(rows.isNull(3)); assertTrue(rows.isNull(4)); assertTrue(rows.isNull(5))
                    assertFalse(rows.moveToNext())
                }
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                migrated.query("SELECT count(*) FROM journeys").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
                migrated.query("SELECT count(*) FROM probable_end_episodes").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            }
            val reopened = Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addMigrations(MIGRATION_15_16, MIGRATION_16_17).build()
            try {
                kotlinx.coroutines.runBlocking {
                    assertEquals("READY_TO_PRESENT", reopened.probableEndPresentationDao().byId("presentation1")!!.status)
                    assertEquals("PRESENTED", reopened.probableEndPresentationDao().byId("presentation2")!!.status)
                    assertNull(reopened.probableEndPresentationDao().byId("presentation2")!!.respondedAtMillis)
                }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
