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
class UnresolvedBoundaryMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        JourneyDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun v18To19AddsNoHistoricalAuthorityAndPreservesEveryExistingTable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "unresolved-boundary-migration.db"
        try {
            val snapshots = mutableMapOf<String, List<String>>()
            fun dump(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
                database.query("SELECT * FROM `$table`").use { cursor -> buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {
                        if (cursor.isNull(it)) "NULL" else cursor.getString(it)
                    })
                } }
            migrations.createDatabase(name, 18).use { old ->
                listOf("ACTIVE", "COMPLETED", "CANCELLED", "AUTO_ENDED").forEachIndexed { i, status ->
                    old.execSQL("""INSERT INTO journeys(id,destination,expectedArrivalAt,startedAt,status,
                        completedAt,endedAt,activeSlot) VALUES(?,'Legacy',9000,1000,?,?,?,?)""",
                        arrayOf<Any?>("legacy$i", status, if (status == "COMPLETED") 5000 else null,
                            if (status in listOf("CANCELLED","AUTO_ENDED")) 5000 else null,
                            if (status == "ACTIVE") 1 else null))
                }
                old.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table'").use {
                    while(it.moveToNext()) snapshots[it.getString(0)] = dump(old, it.getString(0))
                }
            }
            migrations.runMigrationsAndValidate(name, 19, true, MIGRATION_18_19).use { next ->
                snapshots.forEach { (table, rows) -> assertEquals(table, rows, dump(next, table)) }
                next.query("SELECT count(*) FROM unresolved_boundaries").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
                next.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            val reopened = Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addMigrations(MIGRATION_18_19).build()
            try { runBlocking {
                assertNull(reopened.unresolvedBoundaryDao().get("legacy0"))
                assertEquals(9000, reopened.journeyDao().getById("legacy0")!!.expectedArrivalAt.toInt())
                assertEquals(com.journeycontinuity.app.domain.JourneyStatus.AUTO_ENDED,
                    reopened.journeyDao().getById("legacy3")!!.status)
            } } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
