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
class OperationalBoundaryMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun v19To20PreservesAllRowsAndInventsNoOperationalDecision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "operational-boundary-migration.db"
        fun snapshot(db: SupportSQLiteDatabase, table: String): List<String> =
            db.query("SELECT * FROM `$table`").use { cursor -> buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {
                    if (cursor.isNull(it)) "NULL" else cursor.getString(it)
                })
            } }
        try {
            val oldRows = mutableMapOf<String, List<String>>()
            migrations.createDatabase(name, 19).use { old ->
                old.execSQL("""INSERT INTO journeys(id,destination,expectedArrivalAt,startedAt,status,
                    completedAt,endedAt,activeSlot) VALUES('legacy','Old destination',2000,1000,
                    'ACTIVE',NULL,NULL,1)""")
                old.execSQL("""INSERT INTO unresolved_boundaries(journeyId,admissionId,
                    admittedAtMillis,etaMillis,boundaryMillis,policyVersion,revision)
                    VALUES('legacy','30000000-0000-4000-8000-000000000010',1000,2000,86402000,
                    'alabarin-unresolved-boundary-v1',0)""")
                old.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table'").use {
                    while (it.moveToNext()) oldRows[it.getString(0)] = snapshot(old, it.getString(0))
                }
            }
            migrations.runMigrationsAndValidate(name, 20, true, MIGRATION_19_20).use { next ->
                oldRows.forEach { (table, rows) -> assertEquals(table, rows, snapshot(next, table)) }
                assertTrue(snapshot(next, "journey_operational_boundaries").isEmpty())
                next.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            val reopened = Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addMigrations(MIGRATION_19_20).build()
            try { runBlocking {
                    assertEquals("ACTIVE", reopened.journeyDao().getActive()!!.status.name)
                    assertNull(reopened.operationalBoundaryDao().get("legacy"))
                    assertEquals(86_402_000L, reopened.unresolvedBoundaryDao().get("legacy")!!.boundaryMillis)
                } } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
