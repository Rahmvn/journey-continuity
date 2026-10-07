package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room transactions/reopens with only the authoritative network boundary replaced. */
@RunWith(AndroidJUnit4::class)
class ProbableEndGuardDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "probable-end-slice3b-test.db"
    private val start = 1_700_000_000_000L
    private var now = start + 60_000
    private lateinit var db: JourneyDatabase
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun store() = RoomProbableEndGuardStore(db, JourneyClock { now })
    private fun local() = RoomProbableEndStore(db, JourneyClock { now })
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); db = open()
        db.journeyDao().insertIfNoActive(Journey("test", "Private synthetic label", start + 3_600_000, start,
            JourneyStatus.ACTIVE, null, structuredDestination = StructuredDestination("NG", "ng-kwara", "Kwara",
                "ng-kwara-offa", "Offa", DestinationKind.POINT, 8.1560008, 4.7227435,
                DestinationSelectionSource.MAP_PIN, adminDatasetVersion = "alabarin-2026-10-05-v2",
                localUnitType = AdminLocalUnitType.LGA, boundaryDatasetId = DESTINATION_BOUNDARY_DATASET,
                boundaryDatasetVersion = "alabarin-2026-10-03-v1")).toEntity())
        Unit
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun reopen() { db.close(); db = open() }
    private suspend fun fix(distance: Double = 0.0, accuracy: Float = 8f, eventTime: Long = now) {
        db.telemetryDao().insertForActiveJourney(TelemetrySample("test", eventTime,
            8.1560008 + Math.toDegrees(distance / GeographicDistance.EARTH_RADIUS_METERS), 4.7227435,
            accuracy, null, null, ConnectivityState.NONE))
        local().consumePending("test")
    }
    private suspend fun candidate(): ProbableEndEpisodeEntity {
        repeat(4) { now += 60_000; fix() }
        return db.probableEndDao().unclosed("test").single()
    }
    private fun read(version: Long = 0, baseline: Long = start,
        reason: VerificationGuardReason = VerificationGuardReason.VERIFICATION_CLEAR) = VerificationGuardRead.Available(
        AuthoritativeVerificationGuard("test", reason != VerificationGuardReason.JOURNEY_NOT_ACTIVE,
            if (reason == VerificationGuardReason.VERIFICATION_CLEAR) VerificationGuardDecision.CLEAR else VerificationGuardDecision.BLOCKED,
            when (reason) { VerificationGuardReason.VERIFICATION_CLEAR -> VerificationKnowledge.CLEAR
                VerificationGuardReason.VERIFYING, VerificationGuardReason.OPEN_VERIFICATION_CASE -> VerificationKnowledge.UNRESOLVED
                else -> VerificationKnowledge.UNKNOWN }, reason, version, baseline, baseline, now,
            if (reason == VerificationGuardReason.VERIFICATION_CLEAR) now + 300_000 else null))
    private suspend fun reconcile(response: VerificationGuardRead, trigger: GuardReconciliationTrigger = GuardReconciliationTrigger.NEW_CANDIDATE): GuardReconciliationDecision? {
        val request = store().prepare("test", trigger) ?: return null
        return store().apply(request, response)
    }
    private suspend fun bind(version: Long = 0) {
        candidate(); assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(version)))
    }
    private suspend fun continuity(id: String) = db.probableEndGuardDao().continuity(id)!!

    @Test fun firstClearInvalidatesUnboundCandidateAndAtomicallyPersistsFloor() = runBlocking {
        val old = candidate(); val before = db.probableEndDao().state("test")!!
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(17)))
        reopen()
        val state = db.probableEndDao().state("test")!!
        assertEquals(before.generation + 1, state.generation); assertEquals(4L, state.evidenceFloorSequence)
        assertEquals(now, state.evidenceFloorEventTimeMillis); assertNull(state.proximity)
        assertEquals("INVALIDATED", db.probableEndDao().episodes("test").single().lifecycle)
        assertEquals("INVALIDATED", continuity(old.id).status)
        assertEquals(17L, db.probableEndGuardDao().binding("test")!!.receipt().version)
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
    }
    @Test fun onlyNewStationarySequenceAfterFloorCanProveContinuityAcrossReopen() = runBlocking {
        bind(); val binding = db.probableEndGuardDao().binding("test")!!
        fix(eventTime = now - 60_000); reopen()
        assertNull(db.probableEndDao().state("test")!!.proximity)
        val fresh = candidate(); assertTrue(fresh.first.telemetrySequence > binding.evidenceFloorSequence)
        assertTrue(fresh.first.observedAtMillis > binding.evidenceFloorEventTimeMillis)
        assertEquals(4, fresh.observationCount); assertEquals(180_000L, fresh.evidence().observedSpanMillis)
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, reconcile(read()))
        val proof = continuity(fresh.id); reopen()
        assertEquals(proof, continuity(fresh.id)); assertEquals("CONTINUITY_PROVEN", proof.status)
        assertEquals("CANDIDATE", db.probableEndDao().unclosed("test").single().lifecycle)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
    }
    @Test fun higherVersionEvenClearPermanentlyInvalidatesAndRebinds() = runBlocking {
        bind(4); val old = candidate()
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(9)))
        reopen(); assertEquals("INVALIDATED", continuity(old.id).status)
        assertEquals(9L, db.probableEndGuardDao().binding("test")!!.guardVersion)
        fix(eventTime = old.final.observedAtMillis); assertTrue(db.probableEndDao().unclosed("test").isEmpty())
        val next = candidate(); assertNotEquals(old.id, next.id)
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, reconcile(read(9)))
    }
    @Test fun blockedWithoutVersionAdvanceInvalidatesAndLaterClearRebaselines() = runBlocking {
        bind(4); val old = candidate()
        assertEquals(GuardReconciliationDecision.BLOCKED, reconcile(read(4, reason = VerificationGuardReason.STALE_AUTHENTICATED_EVIDENCE)))
        assertEquals("INVALIDATED", continuity(old.id).status)
        assertEquals("BLOCKED", db.probableEndGuardDao().binding("test")!!.status)
        now += PROBABLE_END_GUARD_RETRY_SPACING_MILLIS
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(4), GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED))
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
        candidate(); assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, reconcile(read(4)))
    }
    @Test fun unknownAttemptsNeverInventBaselineOrAdvanceEvidenceAndRetryIsBounded() = runBlocking {
        val old = candidate(); val before = db.probableEndDao().state("test")!!
        val unknown = VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE)
        assertEquals(GuardReconciliationDecision.UNKNOWN, reconcile(unknown))
        repeat(20) { now += 1000; assertNull(store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)) }
        now += 60_000
        assertNull(store().prepare("test", GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED))
        reopen(); assertEquals(before, db.probableEndDao().state("test")); assertNull(db.probableEndGuardDao().binding("test"))
        assertEquals(old, db.probableEndDao().unclosed("test").single())
        now += PROBABLE_END_GUARD_RETRY_SPACING_MILLIS
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(5), GuardReconciliationTrigger.RESTORE))
    }
    @Test fun offlineHigherVersionAfterResolutionCannotRescueCandidate() = runBlocking {
        bind(7); val old = candidate(); val before = db.probableEndDao().state("test")!!
        assertEquals(GuardReconciliationDecision.UNKNOWN, reconcile(VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE)))
        reopen(); assertEquals(before, db.probableEndDao().state("test")); now += PROBABLE_END_GUARD_RETRY_SPACING_MILLIS
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(10), GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED))
        assertEquals("INVALIDATED", continuity(old.id).status)
    }
    @Test fun successfulHeartbeatsRefreshKnownContinuityWithoutPerBeatGuardPolling() = runBlocking {
        bind(7); val current = candidate()
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, reconcile(read(7)))
        repeat(4) {
            now += 60_000
            assertNull(store().prepare("test", GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED))
        }
        reopen(); now += 60_000
        val refresh = store().prepare("test", GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED)
        assertNotNull(refresh)
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, store().apply(refresh!!, read(7)))
        assertEquals("CONTINUITY_PROVEN", continuity(current.id).status)
    }
    @Test fun regressionAndWrongJourneyDoNotOverwriteBindingOrFloor() = runBlocking {
        bind(7); val old = candidate()
        val before = db.probableEndDao().state("test")!!
        for (response in listOf(read(6), VerificationGuardRead.Available(read(7).guard.copy(journeyId = "other")))) {
            assertEquals(GuardReconciliationDecision.UNKNOWN, reconcile(response, GuardReconciliationTrigger.RESTORE))
            now += PROBABLE_END_GUARD_RETRY_SPACING_MILLIS
            assertEquals(7L, db.probableEndGuardDao().binding("test")!!.guardVersion)
            assertEquals(before, db.probableEndDao().state("test")); assertEquals(old, db.probableEndDao().unclosed("test").single())
        }
    }
    @Test fun genuineNewEpochRequiresFreshBaselineEvenWithLowerOpaqueVersion() = runBlocking {
        bind(7); val old = candidate()
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(0, baseline = start + 10_000)))
        assertEquals("INVALIDATED", continuity(old.id).status)
        assertEquals(start + 10_000, db.probableEndGuardDao().binding("test")!!.historyBaselineAtMillis)
    }
    @Test fun inFlightDepartureInvalidationWinsAndCannotResurrectOldGeneration() = runBlocking {
        bind(); val old = candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        now += 60_000; fix(200.0); reopen(); now += 60_000; fix(200.0)
        assertNull(store().apply(request, read()))
        assertEquals("INVALIDATED", continuity(old.id).status)
        candidate(); assertNull(store().apply(request, read()))
        assertEquals("PENDING_GUARD", db.probableEndGuardDao().continuity(db.probableEndDao().unclosed("test").single().id)!!.status)
    }
    @Test fun compatibleLaterTelemetryDoesNotDiscardExactCandidateResponse() = runBlocking {
        bind(); val old = candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        now += 60_000; fix()
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, store().apply(request, read()))
        assertEquals(old.id, db.probableEndDao().unclosed("test").single().id)
    }
    @Test fun terminalWinsOverLateClearWithoutLifecycleMutation() = runBlocking {
        for (cancel in listOf(false, true)) {
            if (cancel) { db.close(); setup() }
            bind(); val old = candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
            if (cancel) db.journeyDao().stopMonitoringActive("test", now) else db.journeyDao().completeActive(now)
            assertEquals(GuardReconciliationDecision.BLOCKED, store().apply(request, read()))
            assertEquals("INVALIDATED", continuity(old.id).status)
            assertEquals(if (cancel) JourneyStatus.CANCELLED else JourneyStatus.COMPLETED, db.journeyDao().getById("test")!!.status)
        }
    }
    @Test fun concurrentRequestsAreDurablyRevisionBoundAndConvergeOnOneProof() = runBlocking {
        bind(); val c = candidate()
        val first = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertNull(store().prepare("test", GuardReconciliationTrigger.RESTORE))
        now += PROBABLE_END_GUARD_RETRY_SPACING_MILLIS
        val second = store().prepare("test", GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED)!!
        assertNull(store().apply(first, read()))
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, store().apply(second, read()))
        assertNull(store().apply(first, VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE)))
        assertNull(store().apply(first, read(9)))
        assertNull(store().apply(second, read())); assertEquals("CONTINUITY_PROVEN", continuity(c.id).status)
    }
    @Test fun telemetryArrivingDuringFirstBindingIsQuarantinedAtAtomicTail() = runBlocking {
        candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        now += 60_000; fix()
        assertEquals(GuardReconciliationDecision.REBASE, store().apply(request, read()))
        assertEquals(5L, db.probableEndDao().state("test")!!.evidenceFloorSequence)
        assertNull(db.probableEndDao().state("test")!!.proximity)
    }
    @Test fun guardTransactionFailureCannotPartiallyBindOrLoseTelemetry() = runBlocking {
        val old = candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER guard_fault BEFORE INSERT ON probable_end_guard_bindings BEGIN SELECT RAISE(ABORT, 'synthetic fault'); END")
        try { store().apply(request, read()); fail("Fault must roll back") } catch (_: android.database.SQLException) { }
        assertNull(db.probableEndGuardDao().binding("test")); assertEquals(old, db.probableEndDao().unclosed("test").single())
        assertEquals(4L, db.telemetryDao().getLatest("test")!!.sequence)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER guard_fault")
        assertEquals(GuardReconciliationDecision.REBASE, store().apply(request, read()))
    }
    @Test fun suppressionAndItsExitRequirementSurviveGuardRebase() = runBlocking {
        bind(); val old = candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        local().suppressCandidate("test", old.id, now)
        assertNull(store().apply(request, read(4)))
        repeat(4) { now += 60_000; fix() }
        assertTrue(db.probableEndDao().unclosed("test").isEmpty()); assertTrue(db.probableEndDao().state("test")!!.requiresReliableExit)
        now += 60_000; fix(200.0); reopen(); now += 60_000; fix(200.0)
        candidate(); assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(4)))
        assertEquals("SUPPRESSED", db.probableEndDao().episodes("test").first { it.id == old.id }.lifecycle)
    }
    @Test fun poorAccuracyAndGapStillResetQualifyingEvidenceWithoutThresholdDrift() = runBlocking {
        bind(); now += 60_000; fix(); now += 60_000; fix(accuracy = 51f)
        assertNull(db.probableEndDao().state("test")!!.proximity)
        now += 60_000; fix(); now += 90_001; fix()
        assertEquals(1, db.probableEndDao().state("test")!!.proximity!!.count)
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
    }
    @Test fun unknownAndProvenRestartRetainExactStateAndNoAdditionalDwell() = runBlocking {
        bind(); val c = candidate(); reconcile(read()); val row = db.probableEndDao().state("test")!!
        val binding = db.probableEndGuardDao().binding("test")!!; val proof = continuity(c.id)
        reopen(); local().resume("test")
        assertEquals(row, db.probableEndDao().state("test")); assertEquals(binding, db.probableEndGuardDao().binding("test")); assertEquals(proof, continuity(c.id))
    }
    @Test fun requestAndPersistenceRenderingRevealNoPrivateEvidence() = runBlocking {
        bind(); val c = candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        for (value in listOf(request, request.binding, continuity(c.id))) {
            assertFalse(value.toString().contains("8.1560008")); assertFalse(value.toString().contains("4.7227435"));
            assertFalse(value.toString().contains("Private synthetic label"))
        }
    }
    @Test fun anotherJourneyCannotConsumeARequestOrShareItsBinding() = runBlocking {
        bind(); candidate(); val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        db.journeyDao().completeActive(now)
        val original = db.journeyDao().getById("test")!!
        db.journeyDao().insertIfNoActive(original.copy(id = "other", status = JourneyStatus.ACTIVE,
            activeSlot = 1, completedAt = null, endedAt = null))
        db.telemetryDao().insertForActiveJourney(TelemetrySample("other", now, 8.1560008, 4.7227435, 8f, null, null, ConnectivityState.NONE))
        local().consumePending("other")
        assertNull(store().apply(request.copy(journeyId = "other"), read()))
        assertNull(db.probableEndGuardDao().binding("other"))
        assertEquals(1, db.probableEndDao().state("other")!!.proximity!!.count)
    }
    @Test fun cancelledNetworkWorkAndRepeatedRestoreCannotManufactureAProof() = runBlocking {
        val old = candidate(); var calls = 0
        fun coordinator() = ProbableEndGuardCoordinator(store(), object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?): VerificationGuardRead {
                calls++; throw CancellationException()
            }
        })
        try { coordinator().reconcile("test", GuardReconciliationTrigger.RESTORE); fail("Cancellation required") }
        catch (_: CancellationException) { }
        reopen(); coordinator().reconcile("test", GuardReconciliationTrigger.RESTORE)
        assertEquals(1, calls); assertEquals(old, db.probableEndDao().unclosed("test").single())
        assertNull(db.probableEndGuardDao().binding("test"))
    }
    @Test fun corruptBindingFailsClosedAndDoesNotInventLowerVersion() = runBlocking {
        bind(7); val old = candidate()
        db.openHelper.writableDatabase.execSQL("UPDATE probable_end_guard_bindings SET establishedServerAtMillis=0 WHERE journeyId='test'")
        val request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.UNKNOWN, store().apply(request, read(0)))
        assertEquals(7L, db.probableEndGuardDao().binding("test")!!.guardVersion)
        assertEquals(old, db.probableEndDao().unclosed("test").single())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
    }
    @Test fun aLaterServerEvidenceBarrierCannotBeCrossedByOldCandidateEvidence() = runBlocking {
        bind(); val old = candidate()
        val response = VerificationGuardRead.Available(read().guard.copy(evidenceNotBeforeMillis = now))
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(response))
        assertEquals("INVALIDATED", continuity(old.id).status)
        assertEquals(now, db.probableEndDao().state("test")!!.evidenceFloorEventTimeMillis)
    }
    @Test fun tooLongRequestAndAmbiguousFutureServerClockRemainUnknown() = runBlocking {
        candidate(); var request = store().prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!
        val initialResponse = read()
        now += 300_000
        assertEquals(GuardReconciliationDecision.UNKNOWN, store().apply(request, initialResponse))
        assertNull(db.probableEndGuardDao().binding("test"))
        request = store().prepare("test", GuardReconciliationTrigger.RESTORE)!!
        val future = VerificationGuardRead.Available(read().guard.copy(evaluatedAtMillis = now + 30_001,
            freshUntilMillis = now + 330_001))
        assertEquals(GuardReconciliationDecision.UNKNOWN, store().apply(request, future))
        assertNull(db.probableEndGuardDao().binding("test")); assertEquals(0L, db.probableEndDao().state("test")!!.generation)
    }
    @Test fun corruptContinuityCannotCauseNetworkSpamOrOverwriteTelemetry() = runBlocking {
        bind(); val c = candidate()
        db.openHelper.writableDatabase.execSQL("UPDATE probable_end_candidate_continuity SET status='CONTINUITY_PROVEN' WHERE episodeId='${c.id}'")
        var requests = 0
        val coordinator = ProbableEndGuardCoordinator(store(), object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?): VerificationGuardRead {
                requests++; return this@ProbableEndGuardDatabaseTest.read()
            }
        })
        repeat(5) { coordinator.reconcile("test", GuardReconciliationTrigger.RESTORE) }
        assertEquals(0, requests); assertEquals(c, db.probableEndDao().unclosed("test").single())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
    }
    @Test fun ambiguousClockCannotRescueBlockedOrInterveningEvidence() = runBlocking {
        bind(4); val c = candidate()
        val response = VerificationGuardRead.Available(read(7, reason = VerificationGuardReason.VERIFYING).guard.copy(
            evaluatedAtMillis = now + 30_001))
        assertEquals(GuardReconciliationDecision.BLOCKED, reconcile(response))
        assertEquals("INVALIDATED", continuity(c.id).status)
        assertEquals("NEEDS_BASELINE", db.probableEndGuardDao().binding("test")!!.status)
        assertEquals(7L, db.probableEndGuardDao().binding("test")!!.receipt().version)
        assertEquals(now, db.probableEndDao().state("test")!!.evidenceFloorEventTimeMillis)
        now += PROBABLE_END_GUARD_RETRY_SPACING_MILLIS
        assertEquals(GuardReconciliationDecision.REBASE, reconcile(read(7), GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED))
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
        candidate(); assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, reconcile(read(7)))
    }
}
