package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbableEndDeadlineDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "probable-end-deadline-test.db"
    private val start = 1_700_000_000_000L
    private var now = start + 60_000
    private lateinit var db: JourneyDatabase
    private val scheduled = mutableListOf<ProbableEndDeadlineIdentity>()
    private val scheduler = ProbableEndDeadlineScheduler { identity, _ -> scheduled += identity }
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun detector() = RoomProbableEndStore(db, JourneyClock { now })
    private fun guard() = RoomProbableEndGuardStore(db, JourneyClock { now })
    private fun presentation() = RoomProbableEndPresentationCoordinator(db, guard(),
        object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) = clear()
        }, JourneyClock { now }, scheduler)
    private fun deadline() = RoomProbableEndDeadlineCoordinator(db, scheduler, JourneyClock { now })
    private fun response() = RoomProbableEndResponseCoordinator(db, detector(), { _, _ -> }, JourneyClock { now })
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
    private suspend fun fix(distanceMeters: Double = 0.0) {
        db.telemetryDao().insertForActiveJourney(TelemetrySample("test", now,
            8.1560008 + Math.toDegrees(distanceMeters / GeographicDistance.EARTH_RADIUS_METERS),
            4.7227435, 8f, null, null, ConnectivityState.NONE))
        detector().consumePending("test")
    }
    private suspend fun candidate(): ProbableEndEpisodeEntity {
        repeat(4) { now += 60_000; fix() }
        return db.probableEndDao().unclosed("test").single()
    }
    private suspend fun ready(): ProbableEndPresentationEntity {
        candidate()
        val first = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.REBASE, guard().apply(first, clear()))
        val later = candidate()
        val proof = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, guard().apply(proof, clear()))
        val id = presentation().recheckAndPromote("test", later.id)!!
        return db.probableEndPresentationDao().byId(id)!!
    }
    private suspend fun presented(): ProbableEndPresentationEntity {
        val p = ready()
        assertTrue(presentation().recordForegroundOpportunity("test", p.id))
        return db.probableEndPresentationDao().byId(p.id)!!
    }

    @Test fun readyHasNoDeadlineAndFirstPresentationSetsImmutableAbsoluteDeadline() = runBlocking {
        val p = ready()
        assertNull(p.confirmationDeadlineAtMillis)
        assertNull(p.deadlineStatus)
        val firstAt = now
        assertTrue(presentation().recordForegroundOpportunity("test", p.id))
        val started = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(firstAt + PROBABLE_END_WINDOW_MILLIS, started.confirmationDeadlineAtMillis)
        assertEquals(ProbableEndDeadlineStatus.ACTIVE.name, started.deadlineStatus)
        assertEquals(1, scheduled.size)
        now += 10 * 60_000
        reopen()
        deadline().restore()
        assertTrue(presentation().recordForegroundOpportunity("test", p.id))
        val restored = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(started.id, restored.id)
        assertEquals(started.presentedAtMillis, restored.presentedAtMillis)
        assertEquals(started.confirmationDeadlineAtMillis, restored.confirmationDeadlineAtMillis)
        assertEquals(started.revision, restored.revision)
        assertEquals(3, scheduled.size)
    }

    @Test fun strictBoundaryDuplicateAndRestartNeverMutateJourney() = runBlocking {
        val p = presented(); val identity = p.deadlineIdentity()
        now = identity.deadlineAtMillis - 1
        assertEquals(ProbableEndExpiryOutcome.TOO_EARLY, deadline().expire(identity))
        assertEquals(ProbableEndDeadlineStatus.ACTIVE.name,
            db.probableEndPresentationDao().byId(p.id)!!.deadlineStatus)
        now++
        reopen()
        assertEquals(ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION, deadline().expire(identity))
        val expired = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(identity.deadlineAtMillis, expired.deadlineExpiredAtMillis)
        assertEquals(ProbableEndDeadlineStatus.EXPIRED_PENDING_FINALIZATION.name, expired.deadlineStatus)
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE, deadline().expire(identity))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
        assertEquals(ProbableEndPresentationStatus.PRESENTED.name, expired.status)
    }

    @Test fun lateWakeRecordsActualExpiryWithoutMovingOriginalDeadline() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        now = work.deadlineAtMillis + 60 * 60_000L
        assertEquals(ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION, deadline().expire(work))
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(work.deadlineAtMillis, saved.confirmationDeadlineAtMillis)
        assertEquals(now, saved.deadlineExpiredAtMillis)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
    }

    @Test fun backwardClockAndStaleWorkerIdentityFailClosed() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        now = requireNotNull(p.presentedAtMillis) - 1
        assertEquals(ProbableEndExpiryOutcome.TOO_EARLY, deadline().expire(work))
        now = work.deadlineAtMillis
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE,
            deadline().expire(work.copy(revision = work.revision + 1)))
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE,
            deadline().expire(work.copy(journeyId = "other")))
        assertEquals(ProbableEndDeadlineStatus.ACTIVE.name,
            db.probableEndPresentationDao().byId(p.id)!!.deadlineStatus)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
    }

    @Test fun explicitResponsesCancelWindowAndStaleWorkCannotExpire() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        now += 1_000
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name,
            db.probableEndPresentationDao().byId(p.id)!!.deadlineStatus)
        now = work.deadlineAtMillis
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE, deadline().expire(work))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
    }

    @Test fun completedResponseCancelsWindowInSameLocalTransaction() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        now += 1_000
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.JOURNEY_COMPLETED))
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name, saved.deadlineStatus)
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
        now = work.deadlineAtMillis
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE, deadline().expire(work))
    }

    @Test fun localSuppressionClosesExactWindowBeforeStaleWorker() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        assertEquals(LocalProbableEndRunStatus.PROGRESSED,
            detector().suppressCandidate("test", p.candidateEpisodeId, now).status)
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name,
            db.probableEndPresentationDao().byId(p.id)!!.deadlineStatus)
        now = work.deadlineAtMillis
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE, deadline().expire(work))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
    }

    @Test fun oldWorkerCannotExpireNewPresentationAfterSuppressionAndRearm() = runBlocking {
        val old = presented(); val oldWork = old.deadlineIdentity()
        now += 1_000
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(old.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        now += 60_000; fix(300.0)
        now += 60_000; fix(300.0)
        repeat(4) { now += 60_000; fix() }
        val candidate = db.probableEndDao().unclosed("test").single()
        val newEpisode = ProbableEndPresentationEntity(
            id = "new-presentation", journeyId = "test", candidateEpisodeId = candidate.id,
            generation = candidate.generation, policyVersion = candidate.policyVersion,
            historyBaselineAtMillis = start, guardVersion = 0,
            status = ProbableEndPresentationStatus.PRESENTED.name,
            createdAtMillis = now, presentedAtMillis = now, revision = 1,
            confirmationDeadlineAtMillis = now + PROBABLE_END_WINDOW_MILLIS,
            deadlineStatus = ProbableEndDeadlineStatus.ACTIVE.name)
        newEpisode.validate()
        db.probableEndPresentationDao().insert(newEpisode)
        now = oldWork.deadlineAtMillis
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE, deadline().expire(oldWork))
        assertEquals(ProbableEndDeadlineStatus.ACTIVE.name,
            db.probableEndPresentationDao().byId(newEpisode.id)!!.deadlineStatus)
    }

    @Test fun terminalAndLocalInvalidationCancelWindow() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        db.journeyDao().completeActive(now)
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name,
            db.probableEndPresentationDao().byId(p.id)!!.deadlineStatus)
        now = work.deadlineAtMillis
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE, deadline().expire(work))
    }

    @Test fun duplicateWorkersConvergeAndResponseRaceNeverLeavesBothOutcomes() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        now = work.deadlineAtMillis
        val results = coroutineScope { listOf(async { deadline().expire(work) },
            async { deadline().expire(work) }).map { it.await() } }
        assertEquals(1, results.count { it == ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION })
        val current = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndDeadlineStatus.EXPIRED_PENDING_FINALIZATION.name, current.deadlineStatus)
        assertEquals(p.revision, current.revision)
        // Slice 4C1 keeps explicit intent available until the later finalization slice.
        assertEquals(ProbableEndResponseOutcome.ACCEPTED,
            response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING))
        val afterResponse = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name, afterResponse.deadlineStatus)
        assertEquals(work.deadlineAtMillis, afterResponse.deadlineExpiredAtMillis)
    }

    @Test fun concurrentKeepResponseAndWorkerCannotLeaveExpiredUnansweredState() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        now = work.deadlineAtMillis
        val results = coroutineScope {
            val expiry = async { deadline().expire(work) }
            val keep = async { response().respond(p.responseRequest(), ProbableEndTravellerResponse.KEEP_MONITORING) }
            expiry.await() to keep.await()
        }
        assertEquals(ProbableEndResponseOutcome.ACCEPTED, results.second)
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name, saved.deadlineStatus)
        assertEquals(ProbableEndPresentationStatus.RESPONDED_KEEP_MONITORING.name, saved.status)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById("test")!!.status)
    }

    @Test fun concurrentCompletionAndWorkerLeavesOnlyExplicitCompletedOutcome() = runBlocking {
        val p = presented(); val work = p.deadlineIdentity()
        now = work.deadlineAtMillis
        val results = coroutineScope {
            val expiry = async { deadline().expire(work) }
            val completed = async { response().respond(p.responseRequest(), ProbableEndTravellerResponse.JOURNEY_COMPLETED) }
            expiry.await() to completed.await()
        }
        assertEquals(ProbableEndResponseOutcome.ACCEPTED, results.second)
        val saved = db.probableEndPresentationDao().byId(p.id)!!
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name, saved.deadlineStatus)
        assertEquals(ProbableEndPresentationStatus.RESPONDED_COMPLETED.name, saved.status)
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
        assertEquals(ProbableEndExpiryOutcome.OBSOLETE, deadline().expire(work))
    }
}
