package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.data.repository.RoomUnresolvedBoundaryStore
import com.journeycontinuity.app.data.unresolved.UnresolvedBoundaryGateway
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.service.JourneyForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production admission and monitoring in a separate, credential-free app UID. */
@RunWith(AndroidJUnit4::class)
class UnresolvedBoundaryRuntimeAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "com.journeycontinuity.app.m6isolation")
        check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty())
    }
    private val app get() = context.applicationContext as JourneyContinuityApplication

    @After fun cleanup() {
        runBlocking {
            app.journeyServiceController.stop()
            app.journeyRepository.completeActive(System.currentTimeMillis())
        }
    }

    @Test fun admissionPersists72HourCapAndFoundationCannotStopRealMonitoring() = runBlocking {
        assertNull(app.journeyRepository.activeJourney.first())
        val startedAt = System.currentTimeMillis()
        val excessive = app.journeyLifecycle.start("Synthetic test Journey", startedAt + 259_260_000L)
        assertEquals(StartJourneyResult.ExpectedArrivalOutOfRange, excessive)
        assertNull(app.journeyRepository.activeJourney.first())
        val admitted = app.journeyLifecycle.start("Synthetic test Journey", startedAt + 3_600_000L)
        assertTrue(admitted is StartJourneyResult.Started)
        val journey = (admitted as StartJourneyResult.Started).journey
        val database = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        try {
            val foundation = database.unresolvedBoundaryDao().get(journey.id)!!
            assertEquals(journey.startedAt, foundation.admittedAtMillis)
            assertEquals(journey.expectedArrivalAt, foundation.etaMillis)
            assertEquals(journey.expectedArrivalAt + 86_400_000L, foundation.boundaryMillis)
            assertEquals(BoundaryProof.INSUFFICIENT_PROOF, foundation.proof())
            app.journeyServiceController.start()
            withTimeout(10_000) {
                while (JourneyForegroundService.monitoringJourneyId.value != journey.id) delay(250)
            }
            assertEquals(journey.id, JourneyForegroundService.monitoringJourneyId.value)
            val a = foundation.admission()
            val observation = UnresolvedObservation(journey.id,
                "10000000-0000-4000-8000-000000000010", a.admissionId, a.policyVersion,
                a.admittedAtMillis, a.etaMillis, a.boundaryMillis, a.admittedAtMillis,
                a.boundaryMillis, UnresolvedEligibility.NOT_ELIGIBLE,
                UnresolvedReason.NOT_VERIFYING, null, null, null, 0, a.admittedAtMillis)
            val store = RoomUnresolvedBoundaryStore(database,
                UnresolvedBoundaryGateway { observation },
                { BoundaryReceipt(android.os.SystemClock.elapsedRealtime(), 1, "isolated-test") })
            assertTrue(store.refresh(journey.id)) // Controlled gateway fixture, not hosted authority.
            assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF,
                database.unresolvedBoundaryDao().get(journey.id)!!.proof())
            assertEquals(journey.id, JourneyForegroundService.monitoringJourneyId.value)
            assertEquals(JourneyStatus.ACTIVE, database.journeyDao().getById(journey.id)!!.status)
            assertNull(database.journeyDao().getById(journey.id)!!.endedAt)
            assertNull(database.unresolvedBoundaryDao().get(journey.id)!!.utcUncertaintyMillis)
        } finally { database.close() }
    }
}
