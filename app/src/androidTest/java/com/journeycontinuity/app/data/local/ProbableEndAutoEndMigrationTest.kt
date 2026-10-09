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
class ProbableEndAutoEndMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun v17To18PreservesWindowAndHumanResponseWithoutInventingServerAuthority() {
        val name = "probable-end-auto-end-migration.db"
        try {
            migrations.createDatabase(name, 17).use { old ->
                for (i in 1..2) {
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
                        presentedAtMillis,closedAtMillis,closureReason,revision,respondedAtMillis,
                        confirmationDeadlineAtMillis,deadlineStatus)
                        VALUES(?,?,?,?, 'alabarin-probable-end-v1',1000,0,?,?,182000,
                        183000,?,?,1,?,1983000,?)""",
                        arrayOf<Any?>("presentation$i", "journey$i", "candidate$i", i.toLong(),
                            if (i == 1) "PRESENTED" else "RESPONDED_KEEP_MONITORING",
                            if (i == 1) 1 else null,
                            if (i == 1) null else 184000L,
                            if (i == 1) null else "TRAVELLER_RESPONSE",
                            if (i == 1) null else 184000L,
                            if (i == 1) "ACTIVE" else "CANCELLED"))
                }
            }
            migrations.runMigrationsAndValidate(name, 18, true, MIGRATION_17_18).use { migrated ->
                migrated.query("""SELECT id,status,confirmationDeadlineAtMillis,deadlineStatus,
                    serverWindowRegisteredAtMillis,serverWindowNotBeforeAtMillis,
                    finalizationClaimedAtMillis,automaticEndedAtMillis,
                    automaticEffectsAppliedAtMillis,automaticResultAcknowledgedAtMillis
                    FROM probable_end_presentations ORDER BY id""").use { rows ->
                    assertEquals(2, rows.count)
                    assertTrue(rows.moveToFirst()); assertEquals("PRESENTED", rows.getString(1))
                    assertEquals(1983000L, rows.getLong(2)); assertEquals("ACTIVE", rows.getString(3))
                    for (column in 4..9) assertTrue(rows.isNull(column))
                    assertTrue(rows.moveToNext()); assertEquals("RESPONDED_KEEP_MONITORING", rows.getString(1))
                    assertEquals(1983000L, rows.getLong(2)); assertEquals("CANCELLED", rows.getString(3))
                    for (column in 4..9) assertTrue(rows.isNull(column))
                }
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                migrated.query("SELECT count(*) FROM journeys WHERE status = 'ACTIVE'").use {
                    it.moveToFirst(); assertEquals(2, it.getInt(0))
                }
                migrated.query("SELECT count(*) FROM probable_end_episodes").use {
                    it.moveToFirst(); assertEquals(2, it.getInt(0))
                }
            }
            val reopened = Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20).build()
            try { runBlocking {
                val presented = reopened.probableEndPresentationDao().byId("presentation1")!!
                presented.validate(); assertNull(presented.serverWindowRegisteredAtMillis)
                assertEquals("PRESENTED", presented.status)
                val responded = reopened.probableEndPresentationDao().byId("presentation2")!!
                responded.validate(); assertEquals("RESPONDED_KEEP_MONITORING", responded.status)
                assertNull(responded.finalizationClaimedAtMillis)
            } } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
