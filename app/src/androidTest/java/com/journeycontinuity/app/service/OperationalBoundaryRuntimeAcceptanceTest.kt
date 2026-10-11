package com.journeycontinuity.app.service

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.BoundaryAcceptanceApplication
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.MainActivity
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.repository.RoomOperationalBoundaryCoordinator
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.OperationalBoundaryState
import com.journeycontinuity.app.domain.StartJourneyResult
import com.journeycontinuity.app.telemetry.LocationReleaseEvidence
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException

/** Real service and fused listener in a credential-free, separate app UID. */
@RunWith(AndroidJUnit4::class)
class OperationalBoundaryRuntimeAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also {
        check(it.packageName == "com.journeycontinuity.app.boundaryacceptance")
        check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty())
    }
    private val app get() = context.applicationContext as JourneyContinuityApplication
    private val clockApp get() = app as BoundaryAcceptanceApplication
    private var database: JourneyDatabase? = null
    private var fixtureId: String? = null

    @Before fun setup() = runBlocking {
        clockApp.resetElapsed()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION)
        database = Room.databaseBuilder(context, JourneyDatabase::class.java,
            "journey-continuity.db").build()
        assertNull(withTimeout(15_000) { app.journeyRepository.activeJourney.first() })
        val result = withTimeout(15_000) { app.journeyLifecycle.start("Isolated privacy boundary fixture",
            System.currentTimeMillis() + 3_600_000L) }
        assertTrue(result is StartJourneyResult.Started)
        fixtureId = (result as StartJourneyResult.Started).journey.id
    }

    @After fun cleanup() {
        app.journeyServiceController.stop()
        clockApp.resetElapsed()
        database?.clearAllTables()
        database?.close()
    }

    private suspend fun awaitListener(id: String) = withTimeout(15_000) {
        while (JourneyForegroundService.monitoringJourneyId.value != id) delay(100)
    }

    private suspend fun openForegroundActivity() {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -n ${context.packageName}/${MainActivity::class.java.name}",
        )).use { it.readBytes() }
        withTimeout(10_000) {
            var resumed = false
            while (!resumed) {
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
                }
                if (!resumed) delay(100)
            }
        }
    }

    private suspend fun awaitStopped(id: String) = withTimeout(15_000) {
        while (JourneyForegroundService.monitoringJourneyId.value == id ||
            JourneyForegroundService.serviceRunning.value) delay(100)
    }

    private suspend fun armServiceTimerNearCap(id: String) {
        val db = requireNotNull(database)
        val admission = db.unresolvedBoundaryDao().get(id)!!.admission()
        val anchor = db.operationalBoundaryDao().get(id)!!.anchor()!!
        clockApp.setRemaining(admission, anchor, 12_000L)
    }

    private suspend fun awaitProductionCutoff(id: String) {
        val db = requireNotNull(database)
        withTimeout(30_000) {
            while (db.operationalBoundaryDao().get(id)?.state !=
                OperationalBoundaryState.PENDING_AUTHORITY.name) delay(100)
        }
        awaitStopped(id)
        withTimeout(15_000) {
            while (JourneyForegroundService.lastLocationRelease.value !is LocationReleaseEvidence.Removed ||
                JourneyForegroundService.lastLocationRelease.value?.owner?.journeyId != id) delay(100)
        }
        assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
            db.operationalBoundaryDao().get(id)?.state)
        assertEquals(id, db.journeyDao().getActive()?.id)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()?.status)
    }

    @Test fun foregroundServiceTimerInitiatesCutoffAndRestartDoesNotCollect() = runBlocking {
        val id = requireNotNull(fixtureId)
        armServiceTimerNearCap(id)
        openForegroundActivity()
        app.journeyServiceController.start()
        awaitListener(id)
        awaitProductionCutoff(id)
        app.journeyServiceController.start()
        delay(1_500)
        assertNull(JourneyForegroundService.monitoringJourneyId.value)
        assertFalse(JourneyForegroundService.serviceRunning.value)
    }

    @Test fun sameBootServiceRestartRestoresOnlyTheExactActiveJourney() = runBlocking {
        val id = requireNotNull(fixtureId)
        openForegroundActivity()
        app.journeyServiceController.start()
        awaitListener(id)
        app.journeyServiceController.stop()
        awaitStopped(id)
        assertEquals(OperationalBoundaryState.MONITORING.name,
            requireNotNull(database).operationalBoundaryDao().get(id)?.state)
        app.journeyServiceController.start()
        awaitListener(id)
        assertEquals(id, JourneyForegroundService.activeRuntimeJourneyId.value)
    }

    @Test fun processRecoveryKeepsValidSameBootMonitoringWithoutStartingAService() = runBlocking {
        val id = requireNotNull(fixtureId)
        assertTrue(app.recoverOperationalBoundaryOnEntry())
        assertEquals(OperationalBoundaryState.MONITORING.name,
            requireNotNull(database).operationalBoundaryDao().get(id)?.state)
    }

    @Test fun backgroundServiceTimerInitiatesCutoffAndRemovesListener() = runBlocking {
        val id = requireNotNull(fixtureId)
        armServiceTimerNearCap(id)
        openForegroundActivity()
        app.journeyServiceController.start()
        awaitListener(id)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        delay(1_000)
        var resumed = true
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).any { it.packageName == context.packageName }
        }
        assertFalse("The isolated app must be backgrounded before cutoff", resumed)
        awaitProductionCutoff(id)
    }

    @Test fun changedBootEvidenceIsPausedBeforeListenerRegistration() = runBlocking {
        val id = requireNotNull(fixtureId)
        requireNotNull(database).openHelper.writableDatabase.execSQL(
            "UPDATE journey_operational_boundaries SET anchorBootCount = anchorBootCount + 1 WHERE journeyId = ?",
            arrayOf(id))
        val previousEntries = clockApp.processRecoveryCalls
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        delay(500)
        openForegroundActivity()
        assertEquals("Activity foreground recovery did not run",
            true, withTimeoutOrNull(5_000) {
                while (clockApp.processRecoveryCalls <= previousEntries) delay(100)
                true
            })
        withTimeout(15_000) {
            while (requireNotNull(database).operationalBoundaryDao().get(id)?.state !=
                OperationalBoundaryState.PENDING_AUTHORITY.name) delay(100)
        }
        assertNull(JourneyForegroundService.monitoringJourneyId.value)
        app.journeyServiceController.start()
        delay(1_500)
        assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
            requireNotNull(database).operationalBoundaryDao().get(id)?.state)
        assertNull(JourneyForegroundService.monitoringJourneyId.value)
        assertFalse(JourneyForegroundService.serviceRunning.value)
    }

    @Test fun canceledPostCommitEffectIsRecoveredByServiceRoomObserver() = runBlocking {
        val id = requireNotNull(fixtureId)
        openForegroundActivity()
        app.journeyServiceController.start()
        awaitListener(id)
        val db = requireNotNull(database)
        val admission = db.unresolvedBoundaryDao().get(id)!!.admission()
        val anchor = db.operationalBoundaryDao().get(id)!!.anchor()!!
        val due = anchor.copy(elapsedRealtimeMillis = anchor.elapsedRealtimeMillis +
            admission.boundaryMillis - admission.admittedAtMillis)
        // Simulate a worker that aborts its post-commit effect. The service must observe Room's
        // durable pause and release its real Play services listener without that callback.
        val worker = RoomOperationalBoundaryCoordinator(db,
            afterPause = { throw CancellationException("worker canceled before teardown") })
        assertTrue(runCatching { worker.evaluate(id, due) }.exceptionOrNull() is CancellationException)
        val stopped = withTimeoutOrNull(15_000) {
            while (JourneyForegroundService.monitoringJourneyId.value == id ||
                JourneyForegroundService.activeRuntimeJourneyId.value == id) delay(100)
            true
        }
        assertEquals("Observer did not stop collection: monitoring=${JourneyForegroundService.monitoringJourneyId.value}, " +
            "active=${JourneyForegroundService.activeRuntimeJourneyId.value}, " +
            "service=${JourneyForegroundService.serviceRunning.value}, " +
            "boundary=${db.operationalBoundaryDao().get(id)?.state}", true, stopped)
        val removed = withTimeoutOrNull(15_000) {
            while (JourneyForegroundService.lastLocationRelease.value !is LocationReleaseEvidence.Removed ||
                JourneyForegroundService.lastLocationRelease.value?.owner?.journeyId != id) delay(100)
            true
        }
        assertEquals("Removal was not acknowledged: ${JourneyForegroundService.lastLocationRelease.value}",
            true, removed)
        assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
            db.operationalBoundaryDao().get(id)?.state)
        assertEquals(id, db.journeyDao().getActive()?.id)
        delay(1_000)
        assertNull(JourneyForegroundService.monitoringJourneyId.value)
        assertFalse("The paused Journey must not retain an idle foreground service",
            JourneyForegroundService.serviceRunning.value)
    }
}
