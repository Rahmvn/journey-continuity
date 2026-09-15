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
import com.journeycontinuity.app.domain.TelemetrySample
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
    }

    @Test
    @Throws(IOException::class)
    fun migrationFrom1To2PreservesExistingJourney() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """INSERT INTO journeys
                    (id, destination, expectedArrivalAt, startedAt, status, completedAt, activeSlot)
                    VALUES ('existing', 'Abuja', 5000, 1000, 'ACTIVE', NULL, 1)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
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

    private fun createInMemoryDatabase(): JourneyDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, JourneyDatabase::class.java)
            .allowMainThreadQueries()
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
        const val TEST_DATABASE = "journey-migration-test"
    }
}
