package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.work.WorkManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.probableend.*
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProbableEndAutoEndDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "probable-end-auto-end-test.db"
    private val start = 1_700_000_000_000L
    private var now = start + 60_000
    private lateinit var db: JourneyDatabase
    private var registerCalls = 0
    private var finalizeCalls = 0
    private var finishEffects = 0
    private var registration: ProbableEndRegistration? = null
    private var finalization: ProbableEndFinalization? = null
    private val wakes = mutableListOf<String>()
    private val wake = ProbableEndAutomaticWakeScheduler { id, _ -> wakes += id }
    private val deadlines = ProbableEndDeadlineScheduler { _, _ -> }
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun detector() = RoomProbableEndStore(db, JourneyClock { now })
    private fun guard() = RoomProbableEndGuardStore(db, JourneyClock { now })
    private fun clear() = VerificationGuardRead.Available(AuthoritativeVerificationGuard("test", true,
        VerificationGuardDecision.CLEAR, VerificationKnowledge.CLEAR,
        VerificationGuardReason.VERIFICATION_CLEAR, 0, start, start, now, now + 300_000))
    private fun presentation() = RoomProbableEndPresentationCoordinator(db, guard(),
        object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) = clear()
        }, JourneyClock { now }, deadlines)
    private fun deadline() = RoomProbableEndDeadlineCoordinator(db, deadlines, JourneyClock { now })
    private fun response() = RoomProbableEndResponseCoordinator(db, detector(), { _, _ -> }, JourneyClock { now })
    private fun auto() = RoomProbableEndAutoEndCoordinator(db, object : ProbableEndAutoEndGateway {
        override suspend fun register(request: ProbableEndServerWindowRequest): ProbableEndRegistration {
            registerCalls++
            return registration ?: ProbableEndRegistration.Registered(now, now + 1_800_000)
        }
        override suspend fun finalize(journeyId: String, presentationEpisodeId: String): ProbableEndFinalization {
            finalizeCalls++
            return finalization ?: ProbableEndFinalization.Ended(now)
        }
    }, wake, { finishEffects++ }, clock = JourneyClock { now })

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
    private suspend fun fix() {
        db.telemetryDao().insertForActiveJourney(TelemetrySample("test", now, 8.1560008,
            4.7227435, 8f, null, null, ConnectivityState.NONE))
        detector().consumePending("test")
    }
    private suspend fun candidate(): ProbableEndEpisodeEntity {
        repeat(4) { now += 60_000; fix() }
        return db.probableEndDao().unclosed("test").single()
    }
    private suspend fun presented(markPresented: Boolean = true): ProbableEndPresentationEntity {
        candidate()
        val first = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.REBASE, guard().apply(first, clear()))
        val later = candidate()
        val proof = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, guard().apply(proof, clear()))
        val id = presentation().recheckAndPromote("test", later.id)!!
        if (markPresented) assertTrue(presentation().recordForegroundOpportunity("test", id))
        return db.probableEndPresentationDao().byId(id)!!
    }

    @Test fun readyEpisodeCannotRegisterBeforeDurablePresentation() = runBlocking {
        val p = presented(markPresented = false)
        assertEquals(ProbableEndPresentationStatus.READY_TO_PRESENT.name, p.status)
        assertEquals(ProbableEndAutomaticOutcome.OBSOLETE, auto().register(p.id))
        assertEquals(ProbableEndAutomaticOutcome.OBSOLETE, auto().advance(p.id))
        assertEquals(0, registerCalls)
        assertEquals(0, finalizeCalls)
        assertNull(db.probableEndPresentationDao().byId(p.id)!!.serverWindowRegisteredAtMillis)
    }

    @Test fun offlinePresentationStartsFullServerWindowOnlyWhenRegistrationRecovers() = runBlocking {
        val p = presented()
        registration = ProbableEndRegistration.Unknown
        auto().register(p.id)
        now = requireNotNull(p.confirmationDeadlineAtMillis) + 600_000
        deadline().expire(p.deadlineIdentity())
        reopen()
        registration = null
        assertEquals(ProbableEndAutomaticOutcome.TOO_EARLY, auto().advance(p.id))
        val registered = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(now, registered.serverWindowRegisteredAtMillis)
        assertEquals(now + 1_800_000, registered.serverWindowNotBeforeAtMillis)
        assertEquals(p.confirmationDeadlineAtMillis, registered.confirmationDeadlineAtMillis)
        assertEquals(0, finalizeCalls)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        now = requireNotNull(registered.serverWindowNotBeforeAtMillis)
        assertEquals(ProbableEndAutomaticOutcome.ENDED, auto().advance(p.id))
    }

    @Test fun serverReadyAloneCannotClaimBeforeLocalDeadlineAndHumanResponseWins() = runBlocking {
        val p = presented()
        // Independent server time can be behind the local clock; only local expiry
        // plus a registered boundary permits a claim, never this timestamp alone.
        registration = ProbableEndRegistration.Registered(now - 1_800_000, now)
        auto().register(p.id)
        assertEquals(ProbableEndAutomaticOutcome.OBSOLETE, auto().advance(p.id))
        assertEquals(0, finalizeCalls)
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        assertEquals(ProbableEndAutomaticOutcome.OBSOLETE, auto().advance(p.id))
    }

    @Test fun registrationIsServerTimedIdempotentAndPreservesDeadlineWorkerIdentity() = runBlocking {
        val p = presented()
        assertEquals(ProbableEndAutomaticOutcome.PENDING, auto().register(p.id))
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(p.revision, saved.revision)
        assertEquals(now, saved.serverWindowRegisteredAtMillis)
        assertEquals(now + 1_800_000, saved.serverWindowNotBeforeAtMillis)
        assertEquals(ProbableEndAutomaticOutcome.PENDING, auto().register(p.id))
        assertEquals(1, registerCalls)
        now = requireNotNull(p.confirmationDeadlineAtMillis)
        assertEquals(ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION,
            deadline().expire(p.deadlineIdentity()))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
    }

    @Test fun offlineRegistrationInventsNoServerTimeAndKeepsHumanResponse() = runBlocking {
        val p = presented()
        registration = ProbableEndRegistration.Unknown
        assertEquals(ProbableEndAutomaticOutcome.PENDING, auto().register(p.id))
        reopen()
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertNull(saved.serverWindowRegisteredAtMillis)
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(saved.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        assertEquals(ProbableEndAutomaticOutcome.OBSOLETE, auto().advance(p.id))
    }

    @Test fun bothDeadlinesRequiredAndServerTooEarlyReleasesClaim() = runBlocking {
        val p = presented(); auto().register(p.id)
        now = requireNotNull(p.confirmationDeadlineAtMillis) - 1
        assertEquals(ProbableEndAutomaticOutcome.OBSOLETE, auto().advance(p.id))
        assertEquals(0, finalizeCalls)
        now++
        assertEquals(ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION,
            deadline().expire(p.deadlineIdentity()))
        finalization = ProbableEndFinalization.TooEarly(now)
        assertEquals(ProbableEndAutomaticOutcome.TOO_EARLY, auto().advance(p.id))
        val restored = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndPresentationStatus.PRESENTED.name, restored.status)
        assertNull(restored.finalizationClaimedAtMillis)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(restored.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
    }

    @Test fun authoritativeSuccessCommitsExactLocalAutoEndAndEffectsOnce() = runBlocking {
        val p = presented(); auto().register(p.id)
        now = requireNotNull(p.confirmationDeadlineAtMillis)
        deadline().expire(p.deadlineIdentity())
        assertEquals(ProbableEndAutomaticOutcome.ENDED, auto().advance(p.id))
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        saved.validate()
        assertEquals(ProbableEndPresentationStatus.AUTO_ENDED.name, saved.status)
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById("test")!!.status)
        assertNull(saved.respondedAtMillis)
        assertEquals(1, finishEffects)
        reopen()
        assertEquals(ProbableEndAutomaticOutcome.ENDED, auto().advance(p.id))
        assertEquals(1, finishEffects)
        assertTrue(auto().acknowledgeResult(p.id))
        assertTrue(auto().acknowledgeResult(p.id))
    }

    @Test fun localTerminalCommitSerializesWithExistingFallbackHandoffGate() = runBlocking {
        val p = presented(); auto().register(p.id)
        now = requireNotNull(p.confirmationDeadlineAtMillis)
        deadline().expire(p.deadlineIdentity())
        val gate = JourneyTerminalHandoffGate()
        val handoffEntered = CompletableDeferred<Unit>()
        val releaseHandoff = CompletableDeferred<Unit>()
        val serverCommitted = CompletableDeferred<Unit>()
        val handoff = async { gate.withLock {
            handoffEntered.complete(Unit)
            releaseHandoff.await()
        } }
        handoffEntered.await()
        val coordinator = RoomProbableEndAutoEndCoordinator(db, object : ProbableEndAutoEndGateway {
            override suspend fun register(request: ProbableEndServerWindowRequest) = error("already registered")
            override suspend fun finalize(journeyId: String, presentationEpisodeId: String): ProbableEndFinalization {
                serverCommitted.complete(Unit)
                return ProbableEndFinalization.Ended(now)
            }
        }, wake, { finishEffects++ }, clock = JourneyClock { now }, terminalHandoffGate = gate)
        val ending = async { coordinator.advance(p.id) }
        serverCommitted.await()
        yield()
        assertFalse(ending.isCompleted)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        releaseHandoff.complete(Unit)
        handoff.await()
        assertEquals(ProbableEndAutomaticOutcome.ENDED, ending.await())
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById("test")!!.status)
    }

    @Test fun restartRecoversEffectsAfterLocalTerminalCommitWithoutRepeatingFinalization() = runBlocking {
        val p = presented(); auto().register(p.id)
        now = requireNotNull(p.confirmationDeadlineAtMillis)
        deadline().expire(p.deadlineIdentity())
        val interrupted = RoomProbableEndAutoEndCoordinator(db, object : ProbableEndAutoEndGateway {
            override suspend fun register(request: ProbableEndServerWindowRequest) = error("already registered")
            override suspend fun finalize(journeyId: String, presentationEpisodeId: String) =
                ProbableEndFinalization.Ended(now)
        }, wake, { error("simulated interrupted teardown") }, clock = JourneyClock { now })
        assertEquals(ProbableEndAutomaticOutcome.ENDED, interrupted.advance(p.id))
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById("test")!!.status)
        assertNull(db.probableEndPresentationDao().byId(p.id)!!.automaticEffectsAppliedAtMillis)
        reopen()
        auto().restore()
        assertEquals(1, finishEffects)
        assertNotNull(db.probableEndPresentationDao().byId(p.id)!!.automaticEffectsAppliedAtMillis)
        auto().restore()
        assertEquals(1, finishEffects)
        assertEquals(0, finalizeCalls)
    }

    @Test fun lostAckRetainsClaimBlocksLateResponseAndReconcilesAfterRestart() = runBlocking {
        val p = presented(); auto().register(p.id)
        now = requireNotNull(p.confirmationDeadlineAtMillis)
        deadline().expire(p.deadlineIdentity())
        finalization = ProbableEndFinalization.Unknown
        assertEquals(ProbableEndAutomaticOutcome.PENDING, auto().advance(p.id))
        val claimed = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndPresentationStatus.AUTO_END_CLAIMED.name, claimed.status)
        assertEquals(ProbableEndResponseOutcome.UNAVAILABLE,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        assertNull(db.journeyDao().completeActive(now))
        assertNull(db.journeyDao().stopMonitoringActive("test", now))
        reopen()
        finalization = ProbableEndFinalization.Ended(now)
        assertEquals(ProbableEndAutomaticOutcome.ENDED, auto().advance(p.id))
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById("test")!!.status)
    }

    @Test fun blockedGuardKillsExactClaimWithoutTerminalMutation() = runBlocking {
        val p = presented(); auto().register(p.id)
        val before = db.probableEndDao().state("test")!!
        now = requireNotNull(p.confirmationDeadlineAtMillis)
        deadline().expire(p.deadlineIdentity())
        finalization = ProbableEndFinalization.Blocked
        assertEquals(ProbableEndAutomaticOutcome.BLOCKED, auto().advance(p.id))
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        saved.validate()
        assertEquals(ProbableEndPresentationStatus.INVALIDATED.name, saved.status)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        assertEquals(LocalProbableEndLifecycle.INVALIDATED.name,
            db.probableEndDao().episodes("test").single { it.id == p.candidateEpisodeId }.lifecycle)
        val state = db.probableEndDao().state("test")!!
        assertTrue(state.generation > p.generation)
        assertTrue(state.evidenceFloorSequence >= before.consumedSequence)
        assertEquals(ProbableEndGuardBindingStatus.NEEDS_BASELINE.name,
            db.probableEndGuardDao().binding("test")!!.status)
        assertEquals(ProbableEndAutomaticOutcome.OBSOLETE, auto().advance(p.id))
    }

    @Test fun blockedRegistrationKillsOldEvidenceWithoutStartingWindow() = runBlocking {
        val p = presented()
        registration = ProbableEndRegistration.Blocked
        assertEquals(ProbableEndAutomaticOutcome.BLOCKED, auto().register(p.id))
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndPresentationStatus.INVALIDATED.name, saved.status)
        assertNull(saved.serverWindowRegisteredAtMillis)
        assertTrue(db.probableEndDao().state("test")!!.generation > p.generation)
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
    }

    @Test fun realWorkManagerEnqueueUsesOneExactEpisodeWorkName() {
        val episodeId = UUID.randomUUID().toString()
        val uniqueName = "probable-end-auto-end-$episodeId"
        val scheduler = WorkManagerProbableEndAutoEndScheduler(context)
        val work = WorkManager.getInstance(context)
        try {
            scheduler.schedule(episodeId, System.currentTimeMillis() + 3_600_000)
            scheduler.schedule(episodeId, System.currentTimeMillis() + 3_600_000)
            val pending = work.getWorkInfosForUniqueWork(uniqueName).get()
            assertEquals(1, pending.size)
        } finally {
            work.cancelUniqueWork(uniqueName).result.get()
        }
    }
}
