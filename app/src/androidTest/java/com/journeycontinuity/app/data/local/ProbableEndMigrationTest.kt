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
class ProbableEndMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedMigrationRollsBackNewTablesAndRetryPreservesExistingJourney() {
        val name = "probable-end-slice2-migration-rollback-test.db"
        try {
            migrations.createDatabase(name, 12).use {
                it.execSQL("""INSERT INTO journeys (id, destination, expectedArrivalAt, startedAt, status, activeSlot)
                    VALUES ('rollback', 'Synthetic legacy', 5000, 1000, 'ACTIVE', 1)""")
            }
            val failing = object : androidx.room.migration.Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_12_13.migrate(object : SupportSQLiteDatabase by db {
                        override fun execSQL(sql: String) {
                            if (sql.startsWith("CREATE TABLE IF NOT EXISTS `probable_end_episodes`"))
                                throw IllegalStateException("Synthetic migration fault")
                            db.execSQL(sql)
                        }
                    })
                }
            }
            val failed = Room.databaseBuilder(context, JourneyDatabase::class.java, name).addMigrations(failing, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17).build()
            try {
                try { failed.openHelper.writableDatabase; org.junit.Assert.fail("Migration should fail") }
                catch (_: IllegalStateException) { }
            } finally { failed.close() }
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(12, raw.version)
                raw.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'probable_end_%'", null).use {
                    assertFalse(it.moveToFirst())
                }
                raw.rawQuery("SELECT destination FROM journeys WHERE id = 'rollback'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("Synthetic legacy", it.getString(0))
                }
            }
            migrations.runMigrationsAndValidate(name, 13, true, MIGRATION_12_13).use {
                it.query("SELECT COUNT(*) FROM journeys WHERE id = 'rollback'").use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals(1L, rows.getLong(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationPreservesEveryExistingTableAndRowAndOpensWithNoInventedState() = runBlocking {
        val name = "probable-end-slice2-migration-test.db"
        var before: Map<String, List<List<String?>>> = emptyMap()
        var definitions: Map<String, String> = emptyMap()
        try {
            migrations.createDatabase(name, 12).use { old ->
                val tables = old.query("SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')").use {
                    buildMap { while (it.moveToNext()) put(it.getString(0), it.getString(1)) }
                }
                definitions = tables
                // Seed every existing Room table, including opaque fallback/heartbeat/checkpoint fields.
                // Nullable destination facts stay unknown; no historical precision is invented.
                tables.keys.sortedBy { if (it == "journeys") 0 else 1 }.forEach { table ->
                    val columns = mutableListOf<String>()
                    val values = mutableListOf<Any>()
                    old.query("PRAGMA table_info(`$table`)").use { fields ->
                        while (fields.moveToNext()) {
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
                                    column == "fallbackDisposition" -> "ALLOCATED"
                                    column == "transportState" -> "ALLOCATED"
                                    column == "eventType" -> "OBSERVATION"
                                    fields.getString(2) == "BLOB" -> byteArrayOf(1, 2, 3)
                                    fields.getString(2) == "TEXT" -> "synthetic"
                                    else -> 1L
                                }
                            }
                        }
                    }
                    old.execSQL("INSERT INTO `$table` (${columns.joinToString()}) VALUES (${values.joinToString { "?" }})", values.toTypedArray())
                }
                old.execSQL("""UPDATE journeys SET destinationCountryCode = 'NG', destinationStateId = 'ng-kwara',
                    destinationStateName = 'Kwara', destinationLgaId = 'ng-kwara-offa', destinationLgaName = 'Offa',
                    destinationKind = 'POINT', destinationLatitude = 8.1560008, destinationLongitude = 4.7227435,
                    destinationSelectionSource = 'MAP_PIN', destinationAdminDatasetVersion = 'alabarin-2026-10-05-v2',
                    destinationLocalUnitType = 'LGA', destinationBoundaryDatasetId = 'NG_OPERATIONAL_BOUNDARIES',
                    destinationBoundaryDatasetVersion = 'alabarin-2026-10-03-v1' WHERE id = 'migration'""")
                old.execSQL("""INSERT INTO journeys (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('legacy', 'Synthetic legacy', 5000, 1000, 'COMPLETED', 4000, NULL)""")
                before = tables.keys.associateWith { snapshot(old, it) }
            }
            migrations.runMigrationsAndValidate(name, 13, true, MIGRATION_12_13).use { migrated ->
                before.forEach { (table, rows) -> assertEquals(table, rows, snapshot(migrated, table)) }
                definitions.forEach { (table, originalSql) ->
                    migrated.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
                        assertTrue(it.moveToFirst()); assertEquals(originalSql, it.getString(0))
                    }
                }
                listOf("probable_end_detector_states", "probable_end_episodes").forEach { table ->
                    migrated.query("SELECT COUNT(*) FROM `$table`").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
                    migrated.query("PRAGMA foreign_key_list(`$table`)").use {
                        assertTrue(it.moveToFirst()); assertEquals("journeys", it.getString(2)); assertEquals("NO ACTION", it.getString(6))
                    }
                }
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                migrated.query("PRAGMA index_list(probable_end_episodes)").use {
                    val unique = buildSet { while (it.moveToNext()) if (it.getInt(2) == 1) add(it.getString(1)) }
                    assertTrue(unique.contains("index_probable_end_episodes_journeyId_generation"))
                    assertTrue(unique.contains("index_probable_end_episodes_journeyId_liveSlot"))
                }
            }
            val opened = Room.databaseBuilder(context, JourneyDatabase::class.java, name).addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17).build()
            try {
                assertNotNull(opened.journeyDao().getById("migration"))
                assertNotNull(opened.journeyDao().getById("migration")!!.toDomain().structuredDestination)
                assertNull(opened.journeyDao().getById("legacy")!!.toDomain().structuredDestination)
                assertNull(opened.probableEndDao().state("migration"))
                assertTrue(opened.probableEndDao().episodes("migration").isEmpty())
            } finally { opened.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun snapshot(db: SupportSQLiteDatabase, table: String): List<List<String?>> = db.query("SELECT * FROM `$table` ORDER BY rowid").use { row ->
        buildList { while (row.moveToNext()) add((0 until row.columnCount).map {
            when (row.getType(it)) {
                android.database.Cursor.FIELD_TYPE_NULL -> null
                android.database.Cursor.FIELD_TYPE_BLOB -> row.getBlob(it).joinToString("") { byte -> "%02x".format(byte) }
                else -> row.getString(it)
            }
        }) }
    }
}
