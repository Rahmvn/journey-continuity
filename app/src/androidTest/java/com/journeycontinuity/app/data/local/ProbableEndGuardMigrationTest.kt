package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbableEndGuardMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun additiveMigrationPreservesEveryExistingTableAndInventsNoGuardKnowledge() = runBlocking {
        val name = "probable-end-guard-migration-test.db"
        var before: Map<String, List<List<String?>>> = emptyMap()
        try {
            migrations.createDatabase(name, 13).use { old ->
                val tables = old.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use {
                    buildList { while (it.moveToNext()) add(it.getString(0)) }
                }
                for (table in tables.sortedBy { when (it) { "journeys" -> 0; "probable_end_episodes" -> 2; else -> 1 } }) {
                    val columns = mutableListOf<String>(); val values = mutableListOf<Any>()
                    old.query("PRAGMA table_info(`$table`)").use { fields -> while (fields.moveToNext()) {
                        val column = fields.getString(1)
                        if (fields.getInt(3) == 1 || column == "activeSlot") {
                            columns += "`$column`"
                            values += when {
                                column == "journeyId" || (column == "id" && fields.getString(2) == "TEXT") -> "migration"
                                column == "status" -> if (table == "journey_fallback_bindings") "PROVISIONED" else "ACTIVE"
                                column == "destination" -> "Synthetic legacy destination"
                                column == "phase" -> "PENDING"
                                column == "connectivity" -> "NONE"
                                column == "connectivityPhase" -> "DEGRADED"
                                column == "fallbackDisposition" || column == "transportState" -> "ALLOCATED"
                                column == "eventType" -> "OBSERVATION"
                                column == "lifecycle" -> "CANDIDATE"
                                fields.getString(2) == "BLOB" -> byteArrayOf(1, 2, 3)
                                fields.getString(2) == "TEXT" -> "synthetic"
                                else -> 1L
                            }
                        }
                    } }
                    old.execSQL("INSERT INTO `$table` (${columns.joinToString()}) VALUES (${values.joinToString { "?" }})", values.toTypedArray())
                }
                old.execSQL("""UPDATE journeys SET startedAt=1000,expectedArrivalAt=3601000,
                    destinationCountryCode='NG',destinationStateId='ng-kwara',destinationStateName='Kwara',
                    destinationLgaId='ng-kwara-offa',destinationLgaName='Offa',destinationKind='POINT',
                    destinationLatitude=8.1560008,destinationLongitude=4.7227435,destinationSelectionSource='MAP_PIN',
                    destinationAdminDatasetVersion='alabarin-2026-10-05-v2',destinationLocalUnitType='LGA',
                    destinationBoundaryDatasetId='NG_OPERATIONAL_BOUNDARIES',destinationBoundaryDatasetVersion='alabarin-2026-10-03-v1'""")
                old.execSQL("UPDATE telemetry_observations SET sequence=1,eventTime=1000,latitude=8.1560008,longitude=4.7227435,accuracyMeters=8")
                for (sequence in 2..4) old.execSQL("""INSERT INTO telemetry_observations(journeyId,sequence,eventTime,latitude,longitude,accuracyMeters,connectivity)
                    VALUES('migration',?, ?,8.1560008,4.7227435,8,'NONE')""", arrayOf<Any>(sequence, 1000L + (sequence - 1) * 60_000L))
                val firstFingerprint = "a".repeat(64); val finalFingerprint = "b".repeat(64)
                old.execSQL("""UPDATE probable_end_episodes SET policyVersion='alabarin-probable-end-v1',evidenceClass='SYSTEM_INFERENCE',
                    generation=0,evidenceFloorSequence=0,evidenceFloorEventTimeMillis=0,createdAtMillis=181000,liveSlot=1,
                    first_telemetrySequence=1,first_observedAtMillis=1000,first_fingerprint=?,
                    final_telemetrySequence=4,final_observedAtMillis=181000,final_fingerprint=?,observationCount=4,
                    finalDistanceMeters=0,finalAccuracyMeters=8,destinationSlot='structured-destination-at-creation'""", arrayOf(firstFingerprint, finalFingerprint))
                old.execSQL("""UPDATE probable_end_detector_states SET journeyStartedAtMillis=1000,policyVersion='alabarin-probable-end-v1',
                    generation=0,evidenceFloorSequence=0,evidenceFloorEventTimeMillis=0,consumedSequence=4,lastEvaluationAtMillis=181000,
                    last_telemetrySequence=4,last_observedAtMillis=181000,last_fingerprint=?,requiresReliableExit=0,
                    proximity_firstSequence=1,proximity_firstTime=1000,proximity_firstFingerprint=?,
                    proximity_latestSequence=4,proximity_latestTime=181000,proximity_latestFingerprint=?,proximity_count=4""",
                    arrayOf(finalFingerprint, firstFingerprint, finalFingerprint))
                before = tables.associateWith { rows(old, it) }
            }
            migrations.runMigrationsAndValidate(name, 14, true, MIGRATION_13_14).use { migrated ->
                before.forEach { (table, original) -> assertEquals(table, original, rows(migrated, table)) }
                for (table in listOf("probable_end_guard_bindings", "probable_end_candidate_continuity")) {
                    assertTrue(rows(migrated, table).isEmpty())
                    migrated.query("PRAGMA foreign_key_list(`$table`)").use { assertTrue(it.moveToFirst()); assertEquals("NO ACTION", it.getString(6)) }
                }
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            val reopened = Room.databaseBuilder(context, JourneyDatabase::class.java, name).addMigrations(MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20).build()
            try {
                assertNotNull(reopened.journeyDao().getById("migration")); assertNotNull(reopened.probableEndDao().state("migration"))
                assertEquals(1, reopened.probableEndDao().episodes("migration").size)
                assertEquals(180_000L, reopened.probableEndDao().episodes("migration").single().evidence().observedSpanMillis)
                assertEquals(4, reopened.probableEndDao().state("migration")!!.restore().proximity!!.count)
                assertNull(reopened.probableEndGuardDao().binding("migration")); assertNull(reopened.probableEndGuardDao().continuity("migration"))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun migrationFailureRollsBackAndCleanRetrySucceeds() {
        val name = "probable-end-guard-failed-migration-test.db"
        try {
            migrations.createDatabase(name, 13).use { it.execSQL("INSERT INTO journeys(id,destination,expectedArrivalAt,startedAt,status,activeSlot) VALUES('legacy','Synthetic',5000,1000,'ACTIVE',1)") }
            val failing = object : androidx.room.migration.Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_13_14.migrate(object : SupportSQLiteDatabase by db {
                        override fun execSQL(sql: String) {
                            if (sql.contains("CREATE TABLE IF NOT EXISTS `probable_end_candidate_continuity`")) throw IllegalStateException("Synthetic migration fault")
                            db.execSQL(sql)
                        }
                    })
                }
            }
            val db = Room.databaseBuilder(context, JourneyDatabase::class.java, name).addMigrations(failing, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20).build()
            try { try { db.openHelper.writableDatabase; fail("Expected rollback") } catch (_: IllegalStateException) { } }
            finally { db.close() }
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(13, it.version)
                it.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'probable_end_guard_%' OR name='probable_end_candidate_continuity'", null).use { c -> assertFalse(c.moveToFirst()) }
            }
            migrations.runMigrationsAndValidate(name, 14, true, MIGRATION_13_14).use {
                assertEquals(1, rows(it, "journeys").size); assertTrue(rows(it, "probable_end_guard_bindings").isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = db.query("SELECT * FROM `$table` ORDER BY rowid").use { row ->
        buildList { while (row.moveToNext()) add((0 until row.columnCount).map {
            when (row.getType(it)) { android.database.Cursor.FIELD_TYPE_NULL -> null
                android.database.Cursor.FIELD_TYPE_BLOB -> row.getBlob(it).joinToString("") { byte -> "%02x".format(byte) }
                else -> row.getString(it) }
        }) }
    }
}
