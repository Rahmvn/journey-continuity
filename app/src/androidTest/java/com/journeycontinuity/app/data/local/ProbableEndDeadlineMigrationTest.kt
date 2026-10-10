package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbableEndDeadlineMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun v16To17PreservesOldPresentedAndResponseWithoutInventingWindow() {
        val name = "probable-end-deadline-migration.db"
        try {
            migrations.createDatabase(name, 16).use { old ->
                for (i in 1..3) {
                    old.execSQL("""INSERT INTO journeys(id,destination,expectedArrivalAt,startedAt,status,activeSlot)
                        VALUES(?,'Synthetic destination',5000000,1000,'ACTIVE',?)""",
                        arrayOf<Any?>("journey$i", if (i == 1) 1 else null))
                    old.execSQL("""INSERT INTO probable_end_episodes(
                        id,journeyId,policyVersion,evidenceClass,createdAtMillis,generation,
                        evidenceFloorSequence,evidenceFloorEventTimeMillis,lifecycle,liveSlot,
                        first_telemetrySequence,first_observedAtMillis,first_fingerprint,
                        final_telemetrySequence,final_observedAtMillis,final_fingerprint,
                        observationCount,finalDistanceMeters,finalAccuracyMeters,destinationSlot)
                        VALUES(?,?,'alabarin-probable-end-v1','SYSTEM_INFERENCE',181000,?,0,0,
                        'INVALIDATED',NULL,1,1000,?,4,181000,?,4,0,8,'structured-destination-at-creation')""",
                        arrayOf<Any?>("candidate$i", "journey$i", i.toLong(), "a".repeat(64), "b".repeat(64)))
                    old.execSQL("""INSERT INTO probable_end_presentations(
                        id,journeyId,candidateEpisodeId,generation,policyVersion,
                        historyBaselineAtMillis,guardVersion,status,liveSlot,createdAtMillis,
                        presentedAtMillis,closedAtMillis,closureReason,revision,respondedAtMillis)
                        VALUES(?,?,?,?, 'alabarin-probable-end-v1',1000,0,?,?,182000,?,?,?,?,?)""",
                        arrayOf<Any?>("presentation$i", "journey$i", "candidate$i", i.toLong(),
                            when (i) { 1 -> "READY_TO_PRESENT"; 2 -> "PRESENTED"; else -> "RESPONDED_KEEP_MONITORING" },
                            if (i == 3) null else 1,
                            if (i == 1) null else 183000L,
                            if (i == 3) 184000L else null,
                            if (i == 3) "TRAVELLER_RESPONSE" else null,
                            if (i == 1) 0L else 1L,
                            if (i == 3) 184000L else null))
                }
            }
            migrations.runMigrationsAndValidate(name, 17, true, MIGRATION_16_17).use { migrated ->
                migrated.query("""SELECT status,presentedAtMillis,respondedAtMillis,
                    confirmationDeadlineAtMillis,deadlineStatus,deadlineExpiredAtMillis
                    FROM probable_end_presentations ORDER BY id""").use { rows ->
                    assertEquals(3, rows.count)
                    assertTrue(rows.moveToFirst()); assertEquals("READY_TO_PRESENT", rows.getString(0))
                    do { assertTrue(rows.isNull(3)); assertTrue(rows.isNull(4)); assertTrue(rows.isNull(5)) }
                    while (rows.moveToNext())
                }
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                migrated.query("SELECT count(*) FROM journeys").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
                migrated.query("SELECT count(*) FROM probable_end_episodes").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
                migrated.query("SELECT id,status,destination FROM journeys ORDER BY id").use { rows ->
                    assertEquals(3, rows.count)
                    for (i in 1..3) {
                        assertTrue(rows.moveToNext())
                        assertEquals("journey$i", rows.getString(0))
                        assertEquals("ACTIVE", rows.getString(1))
                        assertEquals("Synthetic destination", rows.getString(2))
                    }
                }
                migrated.query("SELECT id,journeyId FROM probable_end_episodes ORDER BY id").use { rows ->
                    for (i in 1..3) {
                        assertTrue(rows.moveToNext())
                        assertEquals("candidate$i", rows.getString(0))
                        assertEquals("journey$i", rows.getString(1))
                    }
                }
            }
            val reopened = Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21).build()
            try { runBlocking {
                val oldPresented = reopened.probableEndPresentationDao().byId("presentation2")!!
                oldPresented.validate()
                assertNull(oldPresented.confirmationDeadlineAtMillis)
                assertEquals("PRESENTED", oldPresented.status)
                assertTrue(reopened.probableEndPresentationDao().activeDeadlines().isEmpty())
                val response = reopened.probableEndPresentationDao().byId("presentation3")!!
                assertEquals("RESPONDED_KEEP_MONITORING", response.status)
                assertEquals(184000L, response.respondedAtMillis)
            } } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
