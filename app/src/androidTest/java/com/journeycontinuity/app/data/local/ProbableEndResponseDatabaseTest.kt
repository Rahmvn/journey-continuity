package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbableEndResponseDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "probable-end-response-test.db"
    private val start = 1_700_000_000_000L
    private var now = start + 60_000
    private lateinit var db: JourneyDatabase
    private var effects = 0
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun detector() = RoomProbableEndStore(db, JourneyClock { now })
    private fun guard() = RoomProbableEndGuardStore(db, JourneyClock { now })
    private fun response(effectsFail: Boolean = false) = RoomProbableEndResponseCoordinator(db,
        detector(), { _, _ -> if (effectsFail) error("Synthetic effect fault") else effects++ }, JourneyClock { now })
    private fun presentation() = RoomProbableEndPresentationCoordinator(db, guard(),
        object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) = clear()
        }, JourneyClock { now })
    private fun clear() = VerificationGuardRead.Available(AuthoritativeVerificationGuard("test", true,
        VerificationGuardDecision.CLEAR, VerificationKnowledge.CLEAR,
        VerificationGuardReason.VERIFICATION_CLEAR, 0, start, start, now, now + 300_000))

    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); db = open()
        assertTrue(db.journeyDao().insertIfNoActive(Journey("test", "Synthetic destination", start + 3_600_000,
            start, JourneyStatus.ACTIVE, null, structuredDestination = StructuredDestination("NG", "ng-kwara", "Kwara",
                "ng-kwara-offa", "Offa", DestinationKind.POINT, 8.1560008, 4.7227435,
                DestinationSelectionSource.MAP_PIN, adminDatasetVersion = "alabarin-2026-10-05-v2",
                localUnitType = AdminLocalUnitType.LGA, boundaryDatasetId = DESTINATION_BOUNDARY_DATASET,
                boundaryDatasetVersion = "alabarin-2026-10-03-v1")).toEntity()))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun reopen() { db.close(); db = open() }
    private suspend fun fix(distance: Double = 0.0) {
        db.telemetryDao().insertForActiveJourney(TelemetrySample("test", now,
            8.1560008 + Math.toDegrees(distance / GeographicDistance.EARTH_RADIUS_METERS), 4.7227435,
            8f, null, null, ConnectivityState.NONE))
        detector().consumePending("test")
    }
    private suspend fun candidate(): ProbableEndEpisodeEntity {
        repeat(4) { now += 60_000; fix() }
        return db.probableEndDao().unclosed("test").single()
    }
    private suspend fun presented(): ProbableEndPresentationEntity {
        candidate()
        val first = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.REBASE, guard().apply(first, clear()))
        val later = candidate()
        val proof = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, guard().apply(proof, clear()))
        val id = presentation().recheckAndPromote("test", later.id)!!
        assertTrue(presentation().recordForegroundOpportunity("test", id))
        return db.probableEndPresentationDao().byId(id)!!
    }

    @Test fun completedResponseAndJourneyCommitTogetherAndResultRestoresOnce() = runBlocking {
        val p = presented(); val request = p.responseRequest()
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(request, ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
        assertEquals(now, db.journeyDao().getById("test")!!.completedAt)
        assertTrue(db.syncStateDao().get("test")!!.workRequested)
        assertEquals(ProbableEndPresentationStatus.RESPONDED_COMPLETED.name,
            db.probableEndPresentationDao().byId(p.id)!!.status)
        assertNull(db.probableEndPresentationDao().live("test"))
        assertEquals(1, effects)
        reopen()
        assertEquals(p.id, db.probableEndPresentationDao().observePendingCompletedResult().first()!!.episodeId)
        assertEquals(ProbableEndResponseOutcome.ALREADY_ACCEPTED,
            response().respond(request, ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        assertEquals(1, effects)
        assertTrue(response().acknowledgeCompletedResult(p.id))
        assertNull(db.probableEndPresentationDao().observePendingCompletedResult().first())
        assertTrue(response().acknowledgeCompletedResult(p.id))
    }

    @Test fun crashAfterCommitBeforeEffectsRecoversWithoutRecompleting() = runBlocking {
        val p = presented(); val request = p.responseRequest()
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response(effectsFail = true).respond(request, ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
        assertNull(db.probableEndPresentationDao().byId(p.id)!!.completionEffectsAppliedAtMillis)
        reopen(); response().resumeCompletionEffects()
        assertEquals(1, effects)
        assertNotNull(db.probableEndPresentationDao().byId(p.id)!!.completionEffectsAppliedAtMillis)
        response().resumeCompletionEffects(); assertEquals(1, effects)
    }

    @Test fun activityCancellationAfterCommitStillFinishesLocalEffects() = runBlocking {
        val p = presented()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coordinator = RoomProbableEndResponseCoordinator(db, detector(), { _, _ ->
                entered.complete(Unit)
                release.await()
                effects++
            }, JourneyClock { now })
            val call = scope.async {
                coordinator.respond(p.responseRequest(), ProbableEndTravellerResponse.JOURNEY_COMPLETED)
            }
            withTimeout(10_000) { entered.await() }
            assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
            call.cancel()
            release.complete(Unit)
            withTimeout(10_000) {
                while (db.probableEndPresentationDao().byId(p.id)!!.completionEffectsAppliedAtMillis == null)
                    delay(20)
            }
            assertEquals(1, effects)
            assertEquals(ProbableEndPresentationStatus.RESPONDED_COMPLETED.name,
                db.probableEndPresentationDao().byId(p.id)!!.status)
        } finally { scope.cancel() }
    }

    @Test fun keepMonitoringSuppressesExactCandidateAndNeedsExitThenNewDwell() = runBlocking {
        val p = presented(); val oldCandidate = p.candidateEpisodeId
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertEquals(ProbableEndPresentationStatus.RESPONDED_KEEP_MONITORING.name,
            db.probableEndPresentationDao().byId(p.id)!!.status)
        assertEquals(LocalProbableEndLifecycle.SUPPRESSED.name,
            db.probableEndDao().episodes("test").last().lifecycle)
        assertTrue(db.probableEndDao().state("test")!!.requiresReliableExit)
        assertNull(db.probableEndPresentationDao().live("test"))
        assertEquals(ProbableEndResponseOutcome.ALREADY_ACCEPTED,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        reopen(); detector().consumePending("test")
        repeat(4) { now += 60_000; fix() }
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
        now += 60_000; fix(300.0)
        assertTrue(db.probableEndDao().state("test")!!.requiresReliableExit)
        now += 60_000; fix(300.0)
        assertFalse(db.probableEndDao().state("test")!!.requiresReliableExit)
        repeat(4) { now += 60_000; fix() }
        val later = db.probableEndDao().unclosed("test").single()
        assertNotEquals(oldCandidate, later.id)
        assertNull(presentation().recheckAndPromote("test", later.id))
        assertNull(db.probableEndPresentationDao().live("test"))
    }

    @Test fun competingResponsesHaveExactlyOneWinnerAndTerminalRaceFailsClosed() = runBlocking {
        val p = presented(); val request = p.responseRequest()
        val outcomes = coroutineScope {
            val complete = async { response().respond(request, ProbableEndTravellerResponse.JOURNEY_COMPLETED) }
            val keep = async { response().respond(request, ProbableEndTravellerResponse.KEEP_MONITORING) }
            listOf(complete.await(), keep.await())
        }
        assertEquals(1, outcomes.count { it == ProbableEndResponseOutcome.ACCEPTED })
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertNotNull(saved.respondedAtMillis)
        if (saved.status == ProbableEndPresentationStatus.RESPONDED_COMPLETED.name)
            assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
        else {
            assertEquals(ProbableEndPresentationStatus.RESPONDED_KEEP_MONITORING.name, saved.status)
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        }
    }

    @Test fun duplicateCompletedCallbacksCommitOneResponseAndOneJourneyTerminal() = runBlocking {
        val p = presented(); val request = p.responseRequest()
        val coordinator = response()
        val outcomes = coroutineScope {
            val first = async { coordinator.respond(request, ProbableEndTravellerResponse.JOURNEY_COMPLETED) }
            val second = async { coordinator.respond(request, ProbableEndTravellerResponse.JOURNEY_COMPLETED) }
            listOf(first.await(), second.await())
        }
        assertEquals(1, outcomes.count { it == ProbableEndResponseOutcome.ACCEPTED })
        assertEquals(1, outcomes.count { it == ProbableEndResponseOutcome.ALREADY_ACCEPTED })
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
        assertEquals(ProbableEndPresentationStatus.RESPONDED_COMPLETED.name,
            db.probableEndPresentationDao().byId(p.id)!!.status)
        assertEquals(1, effects)
    }

    @Test fun terminalJourneyAndWrongEpisodeCannotRespond() = runBlocking {
        val p = presented()
        val wrong = p.responseRequest().copy(journeyId = "other")
        assertEquals(ProbableEndResponseOutcome.UNAVAILABLE,
            response().respond(wrong, ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        db.journeyDao().completeActive(now)
        assertEquals(ProbableEndResponseOutcome.UNAVAILABLE,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        assertEquals(ProbableEndPresentationStatus.INVALIDATED.name,
            db.probableEndPresentationDao().byId(p.id)!!.status)
    }

    @Test fun staleRevisionAndConflictingResponseCannotReplaceKeepMonitoring() = runBlocking {
        val p = presented()
        val stale = p.responseRequest().copy(presentationRevision = p.revision + 1)
        assertEquals(ProbableEndResponseOutcome.UNAVAILABLE,
            response().respond(stale, ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        assertEquals(ProbableEndPresentationStatus.PRESENTED.name,
            db.probableEndPresentationDao().byId(p.id)!!.status)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)

        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        val accepted = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndResponseOutcome.UNAVAILABLE,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        assertEquals(accepted.respondedAtMillis,
            db.probableEndPresentationDao().byId(p.id)!!.respondedAtMillis)
        assertEquals(accepted.status, db.probableEndPresentationDao().byId(p.id)!!.status)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        assertEquals(0, effects)
    }
}
