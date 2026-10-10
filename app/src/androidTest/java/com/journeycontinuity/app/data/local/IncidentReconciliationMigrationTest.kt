package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IncidentReconciliationMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun v20To21PreservesEveryExistingTableAndCreatesNoClaimOrTerminalFact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "incident-reconciliation-migration.db"
        fun rows(db: SupportSQLiteDatabase, table: String) = db.query("SELECT * FROM `$table`").use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") {
                if (c.isNull(it)) "NULL" else c.getString(it) }) }
        }
        val previous = mutableMapOf<String, List<String>>()
        try {
            migrations.createDatabase(name, 20).use { old ->
                old.execSQL("""INSERT INTO journeys(id,destination,expectedArrivalAt,startedAt,status,
                    completedAt,endedAt,activeSlot) VALUES('legacy','Synthetic',2000,1000,'ACTIVE',NULL,NULL,1)""")
                old.execSQL("""INSERT INTO unresolved_boundaries(journeyId,admissionId,admittedAtMillis,
                    etaMillis,boundaryMillis,policyVersion,revision) VALUES('legacy','admission',1000,
                    2000,86402000,'alabarin-unresolved-boundary-v1',0)""")
                old.execSQL("""INSERT INTO journey_operational_boundaries(journeyId,admissionId,state,
                    pauseReason,lastAssessment,revision) VALUES('legacy','admission','PENDING_AUTHORITY',
                    'LOST_OR_INVALID_BOOT_ANCHOR','INSUFFICIENT_PROOF',1)""")
                old.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table'").use {
                    while (it.moveToNext()) previous[it.getString(0)] = rows(old, it.getString(0))
                }
            }
            migrations.runMigrationsAndValidate(name, 21, true, MIGRATION_20_21).use { next ->
                previous.forEach { (table, before) -> assertEquals(table, before, rows(next, table)) }
                assertTrue(rows(next, "journey_terminal_reconciliations").isEmpty())
                assertTrue(rows(next, "journey_incident_finalizations").isEmpty())
                assertTrue(rows(next, "journey_auto_end_finalizations").isEmpty())
                next.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            val db = Room.databaseBuilder(context, JourneyDatabase::class.java, name).addMigrations(MIGRATION_20_21).build()
            try { runBlocking {
                assertEquals("ACTIVE", db.journeyDao().getActive()!!.status.name)
                assertEquals("PENDING_AUTHORITY", db.operationalBoundaryDao().get("legacy")!!.state)
                assertNull(db.incidentReconciliationDao().get("legacy"))
                assertNull(db.incidentReconciliationDao().provenance("legacy"))
                assertNull(db.incidentReconciliationDao().autoEndProvenance("legacy"))
            } } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
