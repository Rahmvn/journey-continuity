package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exact Room transactions with only the authenticated network boundary replaced. */
@RunWith(AndroidJUnit4::class)
class ProbableEndPresentationDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "probable-end-presentation-test.db"
    private val start = 1_700_000_000_000L
    private var now = start + 60_000
    private lateinit var db: JourneyDatabase
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun local() = RoomProbableEndStore(db, JourneyClock { now })
    private fun guard() = RoomProbableEndGuardStore(db, JourneyClock { now })
    private fun coordinator(read: VerificationGuardRead) = RoomProbableEndPresentationCoordinator(db, guard(),
        object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) = read
        }, JourneyClock { now })
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
        local().consumePending("test")
    }
    private suspend fun candidate(): ProbableEndEpisodeEntity {
        repeat(4) { now += 60_000; fix() }
        return db.probableEndDao().unclosed("test").single()
    }
    private fun clear(version: Long = 0, baseline: Long = start) = VerificationGuardRead.Available(
        AuthoritativeVerificationGuard("test", true, VerificationGuardDecision.CLEAR,
            VerificationKnowledge.CLEAR, VerificationGuardReason.VERIFICATION_CLEAR,
            version, baseline, baseline, now, now + 300_000))
    private fun blocked(version: Long = 0) = VerificationGuardRead.Available(
        AuthoritativeVerificationGuard("test", true, VerificationGuardDecision.BLOCKED,
            VerificationKnowledge.UNRESOLVED, VerificationGuardReason.VERIFYING,
            version, start, start, now, null))
    private suspend fun provenCandidate(): ProbableEndEpisodeEntity {
        candidate()
        val first = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.REBASE, guard().apply(first, clear()))
        val later = candidate()
        val proof = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, guard().apply(proof, clear()))
        return later
    }

    @Test fun firstClearCannotCreatePresentationAndMigrationInventsNoOpportunity() = runBlocking {
        val old = candidate()
        assertNull(coordinator(clear()).recheckAndPromote("test", old.id))
        assertNull(db.probableEndPresentationDao().live("test"))
        val request = guard().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.REBASE, guard().apply(request, clear()))
        assertNull(db.probableEndPresentationDao().live("test"))
    }

    @Test fun freshExactClearPromotesOnceAndForegroundReceiptSurvivesReopen() = runBlocking {
        val candidate = provenCandidate()
        val coordinator = coordinator(clear())
        val id = coordinator.recheckAndPromote("test", candidate.id)!!
        assertEquals(ProbableEndPresentationStatus.READY_TO_PRESENT.name,
            db.probableEndPresentationDao().live("test")!!.status)
        assertNull(db.probableEndPresentationDao().live("test")!!.presentedAtMillis)
        reopen()
        assertEquals(id, db.probableEndPresentationDao().live("test")!!.id)
        assertTrue(coordinator(clear()).recordForegroundOpportunity("test", id))
        val presentedAt = db.probableEndPresentationDao().live("test")!!.presentedAtMillis
        now += 1_000
        assertTrue(coordinator(clear()).recordForegroundOpportunity("test", id))
        assertEquals(id, coordinator(clear()).recheckAndPromote("test", candidate.id))
        reopen()
        assertEquals(presentedAt, db.probableEndPresentationDao().live("test")!!.presentedAtMillis)
        assertEquals(ProbableEndPresentationStatus.PRESENTED.name, db.probableEndPresentationDao().live("test")!!.status)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertFalse(coordinator(clear()).recordForegroundOpportunity("other", id))
        assertFalse(db.probableEndPresentationDao().live("test").toString().contains("8.156"))
    }

    @Test fun unknownAndBlockedNeverPresentEvenWhenVersionUnchanged() = runBlocking {
        val candidate = provenCandidate()
        assertNull(coordinator(VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE))
            .recheckAndPromote("test", candidate.id))
        assertNull(db.probableEndPresentationDao().live("test"))
        // An unavailable read can leave this exact candidate pending. A later ordinary guard
        // reconciliation restores continuity before another presentation request.
        now += PROBABLE_END_GUARD_RETRY_SPACING_MILLIS
        val retry = guard().prepare("test", GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED)!!
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, guard().apply(retry, clear()))
        assertNull(coordinator(blocked()).recheckAndPromote("test", candidate.id))
        assertEquals(LocalProbableEndLifecycle.INVALIDATED.name,
            db.probableEndDao().episodes("test").last().lifecycle)
        assertNull(db.probableEndPresentationDao().live("test"))
    }

    @Test fun higherVersionAndLateDepartureCannotPromote() = runBlocking {
        val candidate = provenCandidate()
        assertNull(coordinator(clear(version = 1)).recheckAndPromote("test", candidate.id))
        assertNull(db.probableEndPresentationDao().live("test"))
    }

    @Test fun lateClearAfterReliableDepartureOrTerminalJourneyDoesNothing() = runBlocking {
        val candidate = provenCandidate()
        val request = guard().prepare("test", GuardReconciliationTrigger.FOREGROUND_PRESENTATION)!!
        now += 60_000; fix(distance = 300.0)
        now += 60_000; fix(distance = 300.0)
        assertNull(guard().apply(request, clear()))
        assertNull(db.probableEndPresentationDao().live("test"))
        assertEquals(LocalProbableEndLifecycle.INVALIDATED.name,
            db.probableEndDao().episodes("test").last().lifecycle)
        assertNull(coordinator(clear()).recheckAndPromote("test", candidate.id))
    }

    @Test fun lateClearAfterExactCandidateSuppressionCannotCreatePresentation() = runBlocking {
        val candidate = provenCandidate()
        val request = guard().prepare("test", GuardReconciliationTrigger.FOREGROUND_PRESENTATION)!!
        assertEquals(LocalProbableEndRunStatus.PROGRESSED,
            local().suppressCandidate("test", candidate.id, now).status)
        assertNull(guard().apply(request, clear()))
        assertNull(db.probableEndPresentationDao().live("test"))
        assertEquals(LocalProbableEndLifecycle.SUPPRESSED.name,
            db.probableEndDao().episodes("test").last().lifecycle)
        assertTrue(db.probableEndDao().state("test")!!.requiresReliableExit)
    }

    @Test fun terminalJourneyClosesExistingPromptAndLateResponseCannotReviveIt() = runBlocking {
        val candidate = provenCandidate()
        val id = coordinator(clear()).recheckAndPromote("test", candidate.id)!!
        val stale = guard().prepare("test", GuardReconciliationTrigger.FOREGROUND_PRESENTATION)!!
        now += 1_000
        assertNotNull(db.journeyDao().completeActive(now))
        assertEquals(GuardReconciliationDecision.BLOCKED, guard().apply(stale, clear()))
        assertNull(db.probableEndPresentationDao().live("test"))
        assertEquals(ProbableEndPresentationStatus.INVALIDATED.name,
            db.probableEndPresentationDao().byId(id)!!.status)
        assertFalse(coordinator(clear()).recordForegroundOpportunity("test", id))
    }
}
