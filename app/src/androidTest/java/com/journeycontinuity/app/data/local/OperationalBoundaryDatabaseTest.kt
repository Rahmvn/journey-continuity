package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.RoomOperationalBoundaryCoordinator
import com.journeycontinuity.app.data.repository.RoomProbableEndStore
import com.journeycontinuity.app.data.repository.LocalProbableEndRunStatus
import com.journeycontinuity.app.data.repository.RoomUnresolvedBoundaryStore
import com.journeycontinuity.app.data.unresolved.UnresolvedBoundaryGateway
import com.journeycontinuity.app.degraded.FallbackEnvelopeV1
import com.journeycontinuity.app.degraded.FallbackTransportState
import com.journeycontinuity.app.degraded.FallbackDisposition
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.degraded.RoomFallbackProvisioningLocalStore
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.DestinationEvidenceReference
import com.journeycontinuity.app.domain.probableend.ObservationEvidenceReference
import com.journeycontinuity.app.domain.probableend.ProbableEndPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationalBoundaryDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "operational-boundary.db"
    private val id = "20000000-0000-4000-8000-000000000020"
    private val owner = "10000000-0000-4000-8000-000000000020"
    private val start = 1_790_000_000_000L
    private val anchor = BoundaryReceipt(1_000, 4, "isolated-installation")
    private lateinit var db: JourneyDatabase
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun reopen() { db.close(); db = open() }
    private fun now(elapsed: Long, boot: Int? = 4, installation: String = anchor.installationId) =
        BoundaryReceipt(elapsed, boot, installation)
    private suspend fun boundary() = db.unresolvedBoundaryDao().get(id)!!
    private suspend fun operational() = db.operationalBoundaryDao().get(id)!!
    private fun coordinator(wallClock: Long = start + 5_000) =
        RoomOperationalBoundaryCoordinator(db, diagnosticWallClock = { wallClock })
    private fun sample() = TelemetrySample(id, start + 2_000, 9.0, 7.0, 10f, 55, false,
        ConnectivityState.CELLULAR)
    private suspend fun presentedWithoutServerWindow(): Pair<ProbableEndEpisodeEntity, ProbableEndPresentationEntity> {
        val observedAt = listOf(start + 1_000, start + 61_000, start + 121_000, start + 181_000)
        observedAt.forEach { assertNotNull(db.telemetryDao().insertForActiveJourney(sample().copy(eventTime = it))) }
        val candidate = ProbableEndEpisodeEntity(
            id = "candidate-$id", journeyId = id, policyVersion = ProbableEndPolicy.VERSION,
            createdAtMillis = start + 190_000, generation = 0, evidenceFloorSequence = 0,
            evidenceFloorEventTimeMillis = 0,
            first = ObservationEvidenceReference(1, observedAt.first(), "a".repeat(64)),
            final = ObservationEvidenceReference(4, observedAt.last(), "b".repeat(64)),
            observationCount = 4, finalDistanceMeters = 10.0, finalAccuracyMeters = 5.0,
            destinationSlot = DestinationEvidenceReference(id).slot)
        candidate.evidence()
        db.probableEndDao().insert(candidate)
        val presentedAt = start + 210_000
        val presentation = ProbableEndPresentationEntity(
            id = "presentation-$id", journeyId = id, candidateEpisodeId = candidate.id,
            generation = candidate.generation, policyVersion = candidate.policyVersion,
            historyBaselineAtMillis = start, guardVersion = 0,
            status = ProbableEndPresentationStatus.PRESENTED.name,
            createdAtMillis = start + 200_000, presentedAtMillis = presentedAt,
            confirmationDeadlineAtMillis = presentedAt + 30 * 60_000L,
            deadlineStatus = ProbableEndDeadlineStatus.ACTIVE.name, revision = 1)
        presentation.validate()
        assertNull(presentation.serverWindowRegisteredAtMillis)
        db.probableEndPresentationDao().insert(presentation)
        return candidate to presentation
    }

    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); db = open()
        assertTrue(db.journeyDao().insertIfNoActive(Journey(id, "Synthetic", start + 1_000,
            start, JourneyStatus.ACTIVE, null).toEntity(), anchor))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun exactNominalCapPausesOnceWithoutTerminalizingOrLosingQueuedEvidence() = runBlocking {
        val duration = boundary().boundaryMillis - start
        val before = coordinator(Long.MAX_VALUE).evaluate(id, now(anchor.elapsedRealtimeMillis + duration - 1))
        assertEquals(OperationalBoundaryAssessment.BEFORE_APPROXIMATE_CAP, before)
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF, boundary().proof())
        assertNotNull(db.telemetryDao().insertForActiveJourney(sample()))
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            coordinator(1).evaluate(id, now(anchor.elapsedRealtimeMillis + duration)))
        val paused = operational()
        assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name, paused.state)
        assertEquals(OperationalPauseReason.APPROXIMATE_MONOTONIC_CAP.name, paused.pauseReason)
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF.name, paused.lastAssessment)
        assertEquals(OperationalBoundaryAssessment.ALREADY_PAUSED,
            coordinator().evaluate(id, now(anchor.elapsedRealtimeMillis + duration + 1)))
        assertEquals(paused, operational())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertEquals(1, db.journeyDao().getActive()!!.activeSlot)
        assertNull(db.journeyDao().observeCollectable().first())
        assertNull(db.telemetryDao().insertForActiveJourney(sample()))
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, start + 6_000, 0))
        assertNotNull(db.telemetryDao().getLatest(id)) // Existing unsent evidence is retained.
        assertEquals(LocalProbableEndRunStatus.INELIGIBLE, RoomProbableEndStore(db).consumePending(id).status)
        assertNotNull(db.telemetryDao().getLatest(id))
        assertNull(db.journeyDao().getActive()!!.endedAt)
        reopen()
        assertEquals(paused, operational())
        assertNull(db.journeyDao().observeCollectable().first())
    }

    @Test fun backwardWallClockClosesPresentedHistoryWithoutCorruptingIt() = runBlocking {
        val (candidate, presentation) = presentedWithoutServerWindow()
        val latestEvidence = db.telemetryDao().getLatest(id)
        val backwardClock = presentation.createdAtMillis - 1
        val duration = boundary().boundaryMillis - start
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            coordinator(backwardClock).evaluate(id, now(anchor.elapsedRealtimeMillis + duration)))
        val closed = db.probableEndPresentationDao().byId(presentation.id)!!
        val closedCandidate = db.probableEndDao().episodes(id).single()
        assertEquals(ProbableEndPresentationStatus.INVALIDATED.name, closed.status)
        assertEquals(ProbableEndDeadlineStatus.CANCELLED.name, closed.deadlineStatus)
        assertEquals(presentation.presentedAtMillis, closed.closedAtMillis)
        assertTrue(closed.closedAtMillis!! >= presentation.createdAtMillis)
        assertTrue(closed.closedAtMillis >= candidate.createdAtMillis)
        assertTrue(closedCandidate.closedAtMillis!! >= candidate.final.observedAtMillis)
        assertEquals(closed.closedAtMillis, closedCandidate.closedAtMillis)
        closed.validate()
        closedCandidate.evidence()
        assertEquals(latestEvidence, db.telemetryDao().getLatest(id))
        val paused = operational()
        assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name, paused.state)
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF, boundary().proof())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertEquals(1, db.journeyDao().getActive()!!.activeSlot)
        assertNull(db.journeyDao().getActive()!!.endedAt)
        assertEquals(OperationalBoundaryAssessment.ALREADY_PAUSED,
            coordinator(backwardClock).evaluate(id, now(anchor.elapsedRealtimeMillis + duration + 1)))
        assertEquals(paused, operational())
        reopen()
        db.probableEndPresentationDao().byId(presentation.id)!!.validate()
        db.probableEndDao().episodes(id).single().evidence()
        assertEquals(paused, operational())
        assertNull(db.probableEndPresentationDao().live(id))
        assertNull(db.journeyDao().observeCollectable().first())
        assertEquals(latestEvidence, db.telemetryDao().getLatest(id))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
    }

    @Test fun forwardWallClockKeepsOrdinaryProbableEndClosureTime() = runBlocking {
        val (_, presentation) = presentedWithoutServerWindow()
        val forwardClock = requireNotNull(presentation.presentedAtMillis) + 1_000
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            coordinator(forwardClock).evaluate(id, now(anchor.elapsedRealtimeMillis +
                boundary().boundaryMillis - start)))
        assertEquals(forwardClock, db.probableEndPresentationDao().byId(presentation.id)!!.closedAtMillis)
        assertEquals(forwardClock, db.probableEndDao().episodes(id).single().closedAtMillis)
    }

    @Test fun invalidBootOrInstallationPausesConservativelyAndWallClockIsNotAProof() = runBlocking {
        val result = coordinator().evaluate(id, now(1_001, boot = 5))
        assertEquals(OperationalBoundaryAssessment.PAUSED, result)
        assertEquals(OperationalPauseReason.LOST_OR_INVALID_BOOT_ANCHOR.name, operational().pauseReason)
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF, boundary().proof())
        reopen()
        assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name, operational().state)
        assertEquals(OperationalBoundaryAssessment.ALREADY_PAUSED,
            coordinator().evaluate(id, now(1_001, boot = null, installation = "other")))
    }

    @Test fun missingAdmissionAnchorPausesWithoutInventingUtcPassage() = runBlocking {
        assertNotNull(db.journeyDao().completeActive(start + 10))
        val second = "20000000-0000-4000-8000-000000000021"
        assertTrue(db.journeyDao().insertIfNoActive(Journey(second, "Synthetic second",
            start + 2_000, start, JourneyStatus.ACTIVE, null).toEntity()))
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            coordinator().evaluate(second, now(2_000)))
        assertEquals(OperationalPauseReason.LOST_OR_INVALID_BOOT_ANCHOR.name,
            db.operationalBoundaryDao().get(second)!!.pauseReason)
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF,
            db.unresolvedBoundaryDao().get(second)!!.proof())
        assertEquals(second, db.journeyDao().getActive()!!.id)
    }

    @Test fun migratedValidAdmissionWithoutOperationalRowPausesWithoutInventingAnchor() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM journey_operational_boundaries WHERE journeyId = ?", arrayOf(id))
        assertNull(db.operationalBoundaryDao().get(id))
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            coordinator().evaluate(id, now(2_000)))
        assertEquals(OperationalPauseReason.LOST_OR_INVALID_BOOT_ANCHOR.name,
            operational().pauseReason)
        assertNull(operational().anchorElapsedRealtimeMillis)
        assertEquals(boundary().admissionId, operational().admissionId)
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF, boundary().proof())
    }

    @Test fun failedPostCommitServiceEffectCannotUndoDurablePause() = runBlocking {
        var calls = 0
        val boundaryCoordinator = RoomOperationalBoundaryCoordinator(db,
            afterPause = { calls++; error("simulated process-side stop failure") })
        val duration = boundary().boundaryMillis - start
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            boundaryCoordinator.evaluate(id, now(anchor.elapsedRealtimeMillis + duration)))
        assertEquals(1, calls)
        reopen()
        assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name, operational().state)
        assertNull(db.journeyDao().observeCollectable().first())
        assertEquals(OperationalBoundaryAssessment.ALREADY_PAUSED,
            coordinator().evaluate(id, now(anchor.elapsedRealtimeMillis + duration + 1)))
    }

    @Test fun authenticatedBoundaryProofOutranksAStaleBootAnchor() = runBlocking {
        val admitted = boundary().admission()
        val observation = UnresolvedObservation(id, owner, admitted.admissionId, admitted.policyVersion,
            admitted.admittedAtMillis, admitted.etaMillis, admitted.boundaryMillis, start,
            admitted.boundaryMillis, UnresolvedEligibility.NOT_ELIGIBLE, UnresolvedReason.NOT_VERIFYING,
            null, null, null, null, null)
        assertTrue(RoomUnresolvedBoundaryStore(db, UnresolvedBoundaryGateway { observation }, { anchor })
            .refresh(id))
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            coordinator().evaluate(id, now(10, boot = 5)))
        assertEquals(OperationalPauseReason.AUTHENTICATED_BOUNDARY_PROOF.name, operational().pauseReason)
        assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF, boundary().proof())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
    }

    @Test fun laterServerProofStrengthensAssessmentWithoutRewritingOriginalPrivacyReason() = runBlocking {
        assertEquals(OperationalBoundaryAssessment.PAUSED,
            coordinator().evaluate(id, now(10, boot = 5)))
        val original = operational()
        val admitted = boundary().admission()
        val observation = UnresolvedObservation(id, owner, admitted.admissionId, admitted.policyVersion,
            admitted.admittedAtMillis, admitted.etaMillis, admitted.boundaryMillis, start,
            admitted.boundaryMillis, UnresolvedEligibility.NOT_ELIGIBLE, UnresolvedReason.NOT_VERIFYING,
            null, null, null, null, null)
        assertTrue(RoomUnresolvedBoundaryStore(db, UnresolvedBoundaryGateway { observation }, { anchor })
            .refresh(id))
        assertEquals(OperationalBoundaryAssessment.ALREADY_PAUSED,
            coordinator().evaluate(id, now(10, boot = 5)))
        assertEquals(original.pauseReason, operational().pauseReason)
        assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF.name, operational().lastAssessment)
        assertEquals(original.revision + 1, operational().revision)
    }

    @Test fun pendingStateBlocksReadyFallbackAndPreservesPriorAttemptHistory() = runBlocking {
        val provisioning = RoomFallbackProvisioningLocalStore(db, db.fallbackAttemptDao())
        assertTrue(provisioning.activeJourneyExists(id))
        db.degradedConnectivityDao().upsert(DegradedConnectivityState(journeyId = id,
            journeyActive = true, fallbackDisposition = FallbackDisposition.ELIGIBLE).toEntity())
        val attemptId = db.fallbackAttemptDao().insert(FallbackAttemptEntity(
            journeyId = id, degradationEpisodeId = 1, envelopeSequence = 1,
            telemetrySequence = 0, observationEventTime = start,
            eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
            protectedPayloadText = "JC1:synthetic", nonce = ByteArray(12),
            payloadSha256 = ByteArray(32), allocatedAt = start))
        assertNotNull(db.fallbackAttemptDao().getNextReadyAttempt(id, start + 1))
        assertEquals(OperationalBoundaryAssessment.PAUSED, coordinator().evaluate(id, now(2, boot = 5)))
        assertNull(db.fallbackAttemptDao().getNextReadyAttempt(id, start + 6_000))
        assertEquals(0, db.fallbackAttemptDao().claimForHandoff(attemptId, start + 6_000))
        assertEquals(FallbackTransportState.ALLOCATED,
            db.fallbackAttemptDao().getByLocalAttemptId(attemptId)!!.transportState)
        assertFalse(db.degradedConnectivityDao().get(id)!!.journeyActive)
        assertEquals(FallbackDisposition.INACTIVE,
            db.degradedConnectivityDao().get(id)!!.fallbackDisposition)
        assertFalse(provisioning.activeJourneyExists(id))
    }
}
