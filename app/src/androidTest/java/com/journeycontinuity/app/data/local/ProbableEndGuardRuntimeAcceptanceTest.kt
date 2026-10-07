package com.journeycontinuity.app.data.local

import android.Manifest
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.data.repository.RoomProbableEndStore
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.GuardReconciliationTrigger
import com.journeycontinuity.app.service.JourneyForegroundService
import com.journeycontinuity.app.service.JourneyServiceController
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production Application/service wiring, credential-free separate package/UID only. */
@RunWith(AndroidJUnit4::class)
class ProbableEndGuardRuntimeAcceptanceTest {
    @Test fun unavailableGuardRemainsSecondaryAcrossServiceRecreation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.journeycontinuity.app.probableendguardacceptance") { "Isolated package required" }
        check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty()) { "Credential-free acceptance required" }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val application = context.applicationContext as JourneyContinuityApplication
        var db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        val controller = JourneyServiceController(context)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val journey = Journey(id, "Synthetic isolated runtime", now + 3_600_000, now - 400_000,
            JourneyStatus.ACTIVE, null, structuredDestination = StructuredDestination("NG", "ng-kwara", "Kwara",
                "ng-kwara-offa", "Offa", DestinationKind.POINT, 8.1560008, 4.7227435,
                DestinationSelectionSource.MAP_PIN, adminDatasetVersion = "alabarin-2026-10-05-v2",
                localUnitType = AdminLocalUnitType.LGA, boundaryDatasetId = DESTINATION_BOUNDARY_DATASET,
                boundaryDatasetVersion = "alabarin-2026-10-03-v1"))
        try {
            check(db.journeyDao().getActive() == null) { "Refusing existing ACTIVE fixture" }
            assertTrue(db.journeyDao().insertIfNoActive(journey.toEntity()))
            for (offset in listOf(180_000L, 120_000, 60_000, 0)) {
                val observed = now - offset
                db.telemetryDao().insertForActiveJourney(TelemetrySample(id, observed, 8.1560008, 4.7227435, 8f,
                    null, null, ConnectivityState.NONE))
                RoomProbableEndStore(db, JourneyClock { observed }).consumePending(id)
            }
            val candidate = db.probableEndDao().unclosed(id).single()
            application.resumeLocalProbableEnd(id, drain = true)
            withTimeout(10_000) { while (db.probableEndGuardDao().continuity(candidate.id)?.attemptedAtMillis == null) delay(50) }
            assertNull(db.probableEndGuardDao().binding(id))
            assertEquals("UNBOUND", db.probableEndGuardDao().continuity(candidate.id)!!.status)
            db.close()
            db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
            assertEquals(candidate.id, db.probableEndDao().unclosed(id).single().id)
            assertEquals("UNBOUND", db.probableEndGuardDao().continuity(candidate.id)!!.status)
            repeat(2) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.start() }
                withTimeout(10_000) { while (JourneyForegroundService.activeRuntimeJourneyId.value != id) delay(50) }
                assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById(id)!!.status)
                assertEquals(candidate.id, db.probableEndDao().unclosed(id).single().id)
                application.requestProbableEndGuard(id, GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED)
                assertNull(db.probableEndGuardDao().binding(id))
                InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.stop() }
                withTimeout(10_000) { while (JourneyForegroundService.serviceRunning.value) delay(50) }
            }
            assertTrue(db.telemetryDao().getLatest(id)!!.sequence >= 4)
            assertEquals(180_000L, db.probableEndDao().unclosed(id).single().evidence().observedSpanMillis)
        } finally {
            controller.stop()
            if (db.journeyDao().getActive()?.id == id) {
                db.journeyDao().completeActive(System.currentTimeMillis())
                RoomProbableEndStore(db).consumePending(id)
            }
            db.close()
        }
    }
}
