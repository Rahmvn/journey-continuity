package com.journeycontinuity.app.data.local

import android.Manifest
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.data.probableend.*
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import com.journeycontinuity.app.service.JourneyForegroundService
import com.journeycontinuity.app.service.JourneyServiceController
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separate acceptance UID: real Application database and service, fake server responses. */
@RunWith(AndroidJUnit4::class)
class ProbableEndAutoEndRuntimeAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as JourneyContinuityApplication
    private val service get() = JourneyServiceController(context)
    private var acceptanceDatabase: JourneyDatabase? = null

    @After fun cleanup() {
        service.stop()
        acceptanceDatabase?.close()
    }

    @Test fun serverAuthorizedLocalEndStopsForegroundServiceAndRestoresResult() = runBlocking {
        check(context.packageName == "com.journeycontinuity.app.m6isolation")
        check(app.currentAccountEmail == null) { "Disposable acceptance app must not have a Traveller session" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        acceptanceDatabase = db
        check(db.journeyDao().getActive() == null) { "Refusing an existing active Journey" }
        val id = UUID.randomUUID().toString()
        val base = System.currentTimeMillis()
        var now = base - 600_000
        val journey = Journey(id, "Synthetic isolated destination", base + 3_600_000,
            base - 660_000, JourneyStatus.ACTIVE, null,
            structuredDestination = StructuredDestination("NG", "ng-kwara", "Kwara",
                "ng-kwara-offa", "Offa", DestinationKind.POINT, 8.1560008, 4.7227435,
                DestinationSelectionSource.MAP_PIN, adminDatasetVersion = "alabarin-2026-10-05-v2",
                localUnitType = AdminLocalUnitType.LGA, boundaryDatasetId = DESTINATION_BOUNDARY_DATASET,
                boundaryDatasetVersion = "alabarin-2026-10-03-v1"))
        assertTrue(db.journeyDao().insertIfNoActive(journey.toEntity()))
        val clock = JourneyClock { now }
        val detector = RoomProbableEndStore(db, clock)
        val guardStore = RoomProbableEndGuardStore(db, clock)
        fun clear() = VerificationGuardRead.Available(AuthoritativeVerificationGuard(id, true,
            VerificationGuardDecision.CLEAR, VerificationKnowledge.CLEAR,
            VerificationGuardReason.VERIFICATION_CLEAR, 0, base - 600_000,
            base - 600_000, now, now + 300_000))
        suspend fun fourFixes(offset: Long) {
            repeat(4) { index ->
                now = base + offset + index * 60_000L
                db.telemetryDao().insertForActiveJourney(TelemetrySample(id, now, 8.1560008,
                    4.7227435, 8f, null, null, ConnectivityState.NONE))
                detector.consumePending(id)
            }
        }
        fourFixes(-600_000)
        val first = guardStore.prepare(id, GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.REBASE, guardStore.apply(first, clear()))
        fourFixes(-180_000)
        val candidate = db.probableEndDao().unclosed(id).single()
        val proof = guardStore.prepare(id, GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, guardStore.apply(proof, clear()))
        val presentation = RoomProbableEndPresentationCoordinator(db, guardStore,
            object : ProbableEndVerificationGuardGateway {
                override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) = clear()
            }, clock)
        val episodeId = presentation.recheckAndPromote(id, candidate.id)!!
        assertTrue(presentation.recordForegroundOpportunity(id, episodeId))
        val p = db.probableEndPresentationDao().byId(episodeId)!!
        val auto = RoomProbableEndAutoEndCoordinator(db, object : ProbableEndAutoEndGateway {
            override suspend fun register(request: ProbableEndServerWindowRequest) =
                ProbableEndRegistration.Registered(now, now + 1_800_000)
            override suspend fun finalize(journeyId: String, presentationEpisodeId: String) =
                ProbableEndFinalization.Ended(now)
        }, ProbableEndAutomaticWakeScheduler { _, _ -> },
            onTerminalEffects = { service.stop() }, clock = clock)
        assertEquals(ProbableEndAutomaticOutcome.PENDING, auto.register(episodeId))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { service.start() }
        repeat(50) {
            if (JourneyForegroundService.serviceRunning.value) return@repeat
            delay(100)
        }
        assertTrue("Foreground monitoring did not start", JourneyForegroundService.serviceRunning.value)
        now = requireNotNull(p.confirmationDeadlineAtMillis)
        assertEquals(ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION,
            RoomProbableEndDeadlineCoordinator(db, ProbableEndDeadlineScheduler { _, _ -> }, clock)
                .expire(p.deadlineIdentity()))
        assertEquals(ProbableEndAutomaticOutcome.ENDED, auto.advance(episodeId))
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(id)!!.status)
        assertFalse(JourneyForegroundService.serviceRunning.value)
        assertEquals(episodeId, db.probableEndPresentationDao().observePendingAutomaticResult().first()!!.episodeId)
        assertEquals(ProbableEndPresentationStatus.AUTO_ENDED.name,
            db.probableEndPresentationDao().byId(episodeId)!!.status)
    }
}
