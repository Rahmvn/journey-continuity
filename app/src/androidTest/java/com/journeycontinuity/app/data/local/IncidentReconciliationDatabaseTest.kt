package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.auth.*
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.data.unresolved.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import com.journeycontinuity.app.degraded.*
import com.journeycontinuity.app.heartbeat.*

@RunWith(AndroidJUnit4::class)
class IncidentReconciliationDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "incident-reconciliation.db"
    private val id = "20000000-0000-4000-8000-000000000021"
    private val owner = "10000000-0000-4000-8000-000000000021"
    private val case = "40000000-0000-4000-8000-000000000021"
    private val start = 1_790_000_000_000L
    private var now = start + 86_400_000 + 60_000
    private lateinit var db: JourneyDatabase
    private var server = RemoteJourneyState(id, owner, JourneyStatus.ACTIVE, null, null)
    private var offline = false
    private var authFailure = false
    private var finalCalls = 0
    private var stopCalls = 0
    private var effects = 0
    private var reads = 0
    private var serverMissing = false
    private var terminalProof: IncidentProvenance? = null
    private var autoProof: AutoEndProvenance? = null
    private var autoReadFailure: IncidentReconciliationOutcome? = null
    private var beforeAutoRead: suspend () -> Unit = {}
    private var eligibleReason = UnresolvedReason.CURRENT_UNRESOLVED
    private var currentCase = case
    private var finalDecision = "UNRESOLVED_INCIDENT"
    private var loseFinalAck = false
    private var loseStopAck = false
    private var beforeFinal: suspend () -> Unit = {}
    private var beforeFinalDispatch: suspend () -> AuthorityRead.Failed? = { null }
    private var finalOverride: suspend (IncidentClaim) -> AuthorityRead<IncidentFinalizerResponse>? = { null }
    private var beforeEligibility: suspend () -> Unit = {}
    private val handoffGate = JourneyTerminalHandoffGate()
    private var failEffects = false
    private var effectOverride: suspend (String) -> Unit = {}
    private val attempts = mutableListOf<IncidentClaim>()
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun reopen() { db.close(); db = open() }
    private fun utc(millis: Long) = Instant.ofEpochMilli(millis).toString()
    private fun sample() = TelemetrySample(id, start + 2_000, 9.0, 7.0, 10f, null, null, ConnectivityState.NONE)
    private suspend fun claim(): IncidentClaim {
        val b = db.unresolvedBoundaryDao().get(id)!!
        val episodeAt = Instant.ofEpochMilli(b.boundaryMillis - 1_000).plusNanos(123_456).toString()
        return IncidentClaim(id, owner, b.admissionId, b.policyVersion, utc(start), utc(b.etaMillis),
            utc(b.boundaryMillis), currentCase, 1, episodeAt, 1, episodeAt)
    }
    private fun coordinator() = RoomIncidentReconciliationCoordinator(db, object : IncidentAuthorityGateway {
        override suspend fun eligibility(journeyId: String): AuthorityRead<IncidentEligibility> {
            beforeEligibility()
            return AuthorityRead.Value(IncidentEligibility(if (eligibleReason == UnresolvedReason.CURRENT_UNRESOLVED)
                UnresolvedEligibility.ELIGIBLE else UnresolvedEligibility.NOT_ELIGIBLE,
                eligibleReason, if (eligibleReason == UnresolvedReason.CURRENT_UNRESOLVED) claim() else null))
        }
        override suspend fun finalize(claim: IncidentClaim, beforeDispatch: suspend () -> Boolean): AuthorityRead<IncidentFinalizerResponse> {
            beforeFinalDispatch()?.let { return it }
            if (!beforeDispatch()) return AuthorityRead.Failed(IncidentReconciliationOutcome.OBSOLETE)
            finalCalls++; attempts += claim; beforeFinal()
            finalOverride(claim)?.let { return it }
            if (finalDecision == "UNRESOLVED_INCIDENT") {
                terminalProof = IncidentProvenance(claim.copy(admittedAt = null), utc(now),
                    utc(now + 604_800_000), "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
                server = RemoteJourneyState(id, owner, JourneyStatus.UNRESOLVED_INCIDENT, now, null)
                if (loseFinalAck) return AuthorityRead.Failed(IncidentReconciliationOutcome.UNAVAILABLE)
            }
            return AuthorityRead.Value(IncidentFinalizerResponse(finalDecision, server.status, server.endedAt?.let(::utc),
                claim.boundaryAt, claim.verificationCaseId, terminalProof?.accessExpiresAt))
        }
        override suspend fun provenance(journeyId: String): AuthorityRead<IncidentProvenance> {
            reads++; return terminalProof?.let { AuthorityRead.Value(it) }
                ?: AuthorityRead.Failed(IncidentReconciliationOutcome.INCONSISTENT)
        }
        override suspend fun autoEndProvenance(journeyId: String): AuthorityRead<AutoEndProvenance> {
            beforeAutoRead()
            return autoReadFailure?.let { AuthorityRead.Failed(it) }
                ?: autoProof?.let { AuthorityRead.Value(it) }
                ?: AuthorityRead.Failed(IncidentReconciliationOutcome.INCONSISTENT)
        }
    }, cloud, stop, terminalHandoffGate = handoffGate, clock = JourneyClock { now }, onStoppedEffects = { target ->
        if (failEffects) error("Simulated process interruption before teardown")
        effectOverride(target)
        effects++
    })
    private val cloud = object : CloudSyncGateway {
        override suspend fun authenticatedOwnerId(): String {
            if (authFailure) throw CloudSyncException(SyncFailureKind.AUTHENTICATION, "Expired")
            if (offline) throw CloudSyncException(SyncFailureKind.TRANSIENT, "Offline")
            return owner
        }
        override suspend fun ownerJourneyState(journeyId: String, ownerId: String) = server.takeUnless { serverMissing }
        override suspend fun upsertJourney(journey: Journey, ownerId: String) {
            assertEquals(owner, ownerId)
            if (server.status != JourneyStatus.ACTIVE) throw CloudSyncException(SyncFailureKind.TERMINAL_CONFLICT, "Terminal")
            server = RemoteJourneyState(journey.id, ownerId, journey.status, journey.endedAt, journey.completedAt)
            serverMissing = false
        }
        override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) = Unit
    }
    private val stop = object : ActiveJourneyStopRemote {
        override suspend fun stop(journeyId: String, ownerId: String): MonitoringStopAcknowledgement {
            stopCalls++; server = RemoteJourneyState(id, owner, JourneyStatus.CANCELLED, now, null)
            if (loseStopAck) throw AmbiguousMonitoringStopException(java.io.IOException())
            return MonitoringStopAcknowledgement(JourneyStatus.CANCELLED, now)
        }
        override suspend fun ownerJourneyState(journeyId: String, ownerId: String) = server
    }
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); db = open()
        db.journeyDao().insertIfNoActive(Journey(id, "Synthetic", start + 1_000, start,
            JourneyStatus.ACTIVE, null).toEntity(), BoundaryReceipt(1_000, 4, "isolated"))
        db.telemetryDao().insertForActiveJourney(sample())
        RoomOperationalBoundaryCoordinator(db).evaluate(id, BoundaryReceipt(86_402_000, 4, "isolated"))
        Unit
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun offlineCompleteIsDurableAndPrecedesEveryFinalizerClaim() = runBlocking {
        assertNotNull(db.journeyDao().completeActive(now))
        val intent = db.incidentReconciliationDao().get(id)!!
        assertEquals("COMPLETE", intent.explicitAction); assertEquals("PENDING", intent.explicitState)
        offline = true; assertEquals(IncidentReconciliationOutcome.UNAVAILABLE, coordinator().advance(id))
        assertEquals(0, finalCalls); assertNull(db.incidentReconciliationDao().get(id)!!.claimJson)
        reopen(); offline = false; now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals("ACKNOWLEDGED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(intent.explicitActionId, db.incidentReconciliationDao().get(id)!!.explicitActionId)
        assertEquals(JourneyStatus.COMPLETED, server.status); assertEquals(0, finalCalls)
    }
    @Test fun offlineStopRetainsActiveSlotAndEvidenceThenRecoversWithoutSignOut() = runBlocking {
        val pause = db.operationalBoundaryDao().get(id)!!
        offline = true
        assertTrue(coordinator().requestStop(id, owner)); assertTrue(coordinator().requestStop(id, owner))
        assertEquals(pause, db.operationalBoundaryDao().get(id))
        assertEquals(IncidentReconciliationOutcome.UNAVAILABLE, coordinator().advance(id))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertNull(db.journeyDao().getActive()!!.endedAt); assertNull(db.journeyDao().observeCollectable().first())
        assertNull(db.telemetryDao().insertForActiveJourney(sample()))
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now, 0))
        assertTrue(db.fallbackAttemptDao().operationallyPaused(id) > 0)
        assertEquals(1, db.telemetryDao().getAfterSequence(id, 0, 50).size)
        assertNull(db.journeyDao().completeActive(now))
        reopen(); offline = false; now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById(id)!!.status)
        assertNull(db.journeyDao().getActive()); assertEquals(1, stopCalls); assertEquals(0, finalCalls)
    }
    @Test fun committedClaimRejectsBothLateActionsWhileRequestIsInFlight() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFinal = { entered.complete(Unit); release.await() }
        val c = coordinator(); val result = async { c.advance(id) }; entered.await()
        assertEquals("PENDING", db.incidentReconciliationDao().get(id)!!.claimState)
        assertNull(db.journeyDao().completeActive(now))
        assertFalse(c.requestStop(id, owner)); release.complete(Unit)
        assertEquals(IncidentReconciliationOutcome.TERMINAL, result.await())
        assertEquals(JourneyStatus.UNRESOLVED_INCIDENT, db.journeyDao().getById(id)!!.status)
        assertNull(db.incidentReconciliationDao().get(id)!!.explicitAction)
    }
    @Test fun committedButLostFinalizerAckRecoversAfterDatabaseReopen() = runBlocking {
        loseFinalAck = true
        assertEquals(IncidentReconciliationOutcome.UNAVAILABLE, coordinator().advance(id))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertNull(db.incidentReconciliationDao().provenance(id))
        val persistedClaim = db.incidentReconciliationDao().get(id)!!.claimJson
        assertFalse(coordinator().requestStop(id, owner)); assertNull(db.journeyDao().completeActive(now))
        reopen(); now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals(persistedClaim, db.incidentReconciliationDao().get(id)!!.claimJson)
        assertEquals(1, finalCalls); assertEquals(1, reads)
        assertEquals(terminalProof, Json.decodeFromString<IncidentProvenance>(db.incidentReconciliationDao().provenance(id)!!.provenanceJson))
    }
    @Test fun secondDeviceWithoutClaimRecoversExactOwnerProvenance() = runBlocking {
        terminalProof = IncidentProvenance(claim().copy(admittedAt = null), utc(now), utc(now + 604_800_000),
            "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
        server = RemoteJourneyState(id, owner, JourneyStatus.UNRESOLVED_INCIDENT, now, null)
        assertNull(db.incidentReconciliationDao().get(id))
        assertTrue(coordinator().reconcileServerTerminal(server))
        assertEquals(terminalProof, Json.decodeFromString<IncidentProvenance>(db.incidentReconciliationDao().provenance(id)!!.provenanceJson))
        assertEquals(0, finalCalls); assertNull(db.incidentReconciliationDao().get(id)!!.claimJson)
    }
    @Test fun malformedOrMismatchedProvenanceLeavesActiveHistoryUnchanged() = runBlocking {
        val valid = IncidentProvenance(claim().copy(admittedAt = null), utc(now), utc(now + 604_800_000),
            "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
        server = RemoteJourneyState(id, owner, JourneyStatus.UNRESOLVED_INCIDENT, now, null)
        listOf(valid.copy(accessExpiresAt = utc(now)), valid.copy(claim = valid.claim.copy(admissionId = case)),
            valid.copy(claim = valid.claim.copy(ownerId = case))).forEach {
            terminalProof = it; assertFalse(coordinator().reconcileServerTerminal(server))
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
            assertNull(db.incidentReconciliationDao().provenance(id))
        }
    }
    @Test fun tooEarlyRetainsPauseAndReleasesOnlyTheExactClaim() = runBlocking {
        finalDecision = "TOO_EARLY"
        assertEquals(IncidentReconciliationOutcome.TOO_EARLY, coordinator().advance(id))
        assertEquals("BLOCKED", db.incidentReconciliationDao().get(id)!!.claimState)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertNull(db.journeyDao().observeCollectable().first()); assertNull(db.incidentReconciliationDao().provenance(id))
        assertEquals(1, finalCalls); coordinator().advance(id); assertEquals(1, finalCalls)
        assertNotNull(db.journeyDao().completeActive(now))
    }
    @Test fun blockedOldEpisodeRequiresFreshReadBeforeAReplacementCanFinalize() = runBlocking {
        finalDecision = "BLOCKED"
        assertEquals(IncidentReconciliationOutcome.FINAL_GUARD_BLOCKED, coordinator().advance(id))
        val old = attempts.single()
        now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        currentCase = "40000000-0000-4000-8000-000000000022"; finalDecision = "UNRESOLVED_INCIDENT"
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertNotEquals(old.verificationCaseId, attempts.last().verificationCaseId)
    }
    @Test fun resolvedInconsistentAndEarlyEligibilityCannotCreateClaimsOrResume() = runBlocking {
        listOf(UnresolvedReason.NOT_VERIFYING, UnresolvedReason.INCONSISTENT_STATE, UnresolvedReason.BOUNDARY_NOT_REACHED)
            .forEach { reason -> eligibleReason = reason; now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
                coordinator().advance(id); assertNull(db.incidentReconciliationDao().get(id)!!.claimJson)
                assertNull(db.journeyDao().observeCollectable().first()); assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status) }
        assertEquals(0, finalCalls)
    }
    @Test fun expiredSessionNeverCreatesClaimOrRemoteMutation() = runBlocking {
        authFailure = true
        assertEquals(IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED, coordinator().advance(id))
        assertEquals(0, finalCalls); assertNull(db.incidentReconciliationDao().get(id)!!.claimJson)
        reopen(); assertNull(db.journeyDao().observeCollectable().first())
    }
    @Test fun lostStopAckRetainsIntentAndReconcilesCommittedCancellation() = runBlocking {
        coordinator().requestStop(id, owner); loseStopAck = true
        assertEquals(IncidentReconciliationOutcome.UNAVAILABLE, coordinator().advance(id))
        assertEquals("PENDING", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        reopen(); now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals(1, stopCalls); assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById(id)!!.status)
    }
    @Test fun competingExplicitTerminalIsReconciledWithoutCallingFinalizer() = runBlocking {
        server = RemoteJourneyState(id, owner, JourneyStatus.COMPLETED, null, now)
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById(id)!!.status)
        assertNull(db.incidentReconciliationDao().provenance(id)); assertEquals(0, finalCalls)
    }
    @Test fun olderServerIncidentWinsOverUnacknowledgedLocalCompletion() = runBlocking {
        db.journeyDao().completeActive(now + 1)
        terminalProof = IncidentProvenance(claim().copy(admittedAt = null), utc(now), utc(now + 604_800_000),
            "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
        server = RemoteJourneyState(id, owner, JourneyStatus.UNRESOLVED_INCIDENT, now, null)
        assertTrue(coordinator().reconcileServerTerminal(server))
        assertEquals("SUPERSEDED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(JourneyStatus.UNRESOLVED_INCIDENT, db.journeyDao().getById(id)!!.status)
        assertNull(db.journeyDao().getById(id)!!.completedAt)
    }
    @Test fun acknowledgedLocalTerminalCannotBeRewrittenByAnotherOutcome() = runBlocking {
        db.journeyDao().completeActive(now); coordinator().advance(id)
        assertEquals("ACKNOWLEDGED", db.incidentReconciliationDao().get(id)!!.explicitState)
        val competing = RemoteJourneyState(id, owner, JourneyStatus.CANCELLED, now + 1, null)
        assertFalse(coordinator().reconcileServerTerminal(competing))
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById(id)!!.status)
    }
    @Test fun processDeathAfterTerminalBeforeTeardownRetriesOnlyEffects() = runBlocking {
        failEffects = true; assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertTrue(db.incidentReconciliationDao().get(id)!!.effectsPending)
        reopen(); failEffects = false; coordinator().restoreEffects(); coordinator().restoreEffects()
        assertEquals(1, effects); assertFalse(db.incidentReconciliationDao().get(id)!!.effectsPending)
        assertEquals(1, finalCalls); assertNull(db.journeyDao().observeCollectable().first())
    }
    @Test fun duplicateCallsAndRestorationNeverDoubleFinalizeOrRestartCollection() = runBlocking {
        val c = coordinator(); val first = async { c.advance(id) }; val second = async { c.advance(id) }
        assertEquals(IncidentReconciliationOutcome.TERMINAL, first.await())
        assertEquals(IncidentReconciliationOutcome.TERMINAL, second.await())
        assertEquals(1, finalCalls); assertEquals(1, effects)
        reopen(); assertNull(db.journeyDao().observeCollectable().first())
        assertEquals(1, db.telemetryDao().getAfterSequence(id, 0, 50).size)
        assertEquals("PENDING_AUTHORITY", db.operationalBoundaryDao().get(id)!!.state)
    }
    @Test fun explicitActionCommittedDuringEligibilityReadStillWinsBeforeClaim() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeEligibility = { entered.complete(Unit); release.await() }
        val c = coordinator(); val result = async { c.advance(id) }; entered.await()
        assertNotNull(db.journeyDao().completeActive(now)); release.complete(Unit)
        assertEquals(IncidentReconciliationOutcome.EXPLICIT_PENDING, result.await())
        assertEquals(0, finalCalls); assertNull(db.incidentReconciliationDao().get(id)!!.claimJson)
    }
    @Test fun deathAfterDispatchHandoffPreservesExactRetryIdentity() = runBlocking {
        beforeFinal = { throw kotlinx.coroutines.CancellationException("Simulated process death") }
        try { coordinator().advance(id); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
        val saved = db.incidentReconciliationDao().get(id)!!.claimJson
        reopen(); beforeFinal = {}; finalDecision = "BLOCKED"; currentCase = "40000000-0000-4000-8000-000000000022"
        now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.FINAL_GUARD_BLOCKED, coordinator().advance(id))
        assertEquals(saved, db.incidentReconciliationDao().get(id)!!.claimJson)
        assertEquals(case, attempts.last().verificationCaseId)
        assertNull(db.incidentReconciliationDao().provenance(id))
    }
    @Test fun explicitStopFromMonitoringBlocksFallbackWithoutErasingQueuedAttempts() = runBlocking {
        db.openHelper.writableDatabase.execSQL("UPDATE journey_operational_boundaries SET state='MONITORING', pauseReason=NULL WHERE journeyId=?", arrayOf(id))
        val attemptId = db.fallbackAttemptDao().insert(FallbackAttemptEntity(
            journeyId = id, degradationEpisodeId = 1, envelopeSequence = 1, telemetrySequence = 1,
            observationEventTime = start, eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
            protectedPayloadText = "JC1:synthetic", nonce = ByteArray(12), payloadSha256 = ByteArray(32), allocatedAt = start))
        assertNotNull(db.fallbackAttemptDao().getNextReadyAttempt(id, now))
        assertTrue(coordinator().requestStop(id, owner))
        assertEquals("EXPLICIT_STOP_INTENT", db.operationalBoundaryDao().get(id)!!.pauseReason)
        assertNull(db.fallbackAttemptDao().getNextReadyAttempt(id, now))
        assertEquals(0, db.fallbackAttemptDao().claimForHandoff(attemptId, now))
        assertEquals(FallbackTransportState.ALLOCATED, db.fallbackAttemptDao().getByLocalAttemptId(attemptId)!!.transportState)
        assertFalse(RoomFallbackProvisioningLocalStore(db, db.fallbackAttemptDao()).activeJourneyExists(id))
        reopen(); assertNull(db.journeyDao().observeCollectable().first())
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF, db.unresolvedBoundaryDao().get(id)!!.proof())
    }
    @Test fun heartbeatAlreadyHandedOffFinishesBeforeStopThenNoNewHeartbeatCanDispatch() = runBlocking {
        db.openHelper.writableDatabase.execSQL("UPDATE journey_operational_boundaries SET state='MONITORING', pauseReason=NULL WHERE journeyId=?", arrayOf(id))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var sent = 0
        val heartbeat = HeartbeatCoordinator(RoomHeartbeatLocalStore(db.heartbeatDao()), HeartbeatGateway {
            sent++; entered.complete(Unit); release.await(); HeartbeatServerState(CloudMonitoringPhase.EVIDENCE_FRESH, now, 1)
        }, clock = { now }, terminalHandoffGate = handoffGate)
        val first = async { heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true) }
        entered.await(); val stopRequest = async { coordinator().requestStop(id, owner) }
        assertNull(db.incidentReconciliationDao().get(id)?.explicitAction)
        release.complete(Unit); assertEquals(HeartbeatAttemptResult.Sent, first.await()); assertTrue(stopRequest.await())
        assertEquals(HeartbeatAttemptResult.Skipped, heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true))
        assertEquals(1, sent)
    }
    @Test fun legacyActiveStopUsesIntentGuardWithoutInventingBoundaryAuthority() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM journey_operational_boundaries WHERE journeyId=?", arrayOf(id))
        db.openHelper.writableDatabase.execSQL("DELETE FROM unresolved_boundaries WHERE journeyId=?", arrayOf(id))
        assertTrue(coordinator().requestStop(id, owner))
        assertNull(db.operationalBoundaryDao().get(id)); assertNull(db.unresolvedBoundaryDao().get(id))
        assertNull(db.journeyDao().observeCollectable().first())
        assertNull(db.telemetryDao().insertForActiveJourney(sample()))
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now, 0))
        reopen(); assertNull(db.journeyDao().observeCollectable().first())
    }
    @Test fun alreadyAutomaticTerminalDoesNotBecomeIncidentOrCauseANewFinalizerRequest() = runBlocking {
        server = RemoteJourneyState(id, owner, JourneyStatus.AUTO_ENDED, now, null)
        assertEquals(IncidentReconciliationOutcome.INCONSISTENT, coordinator().advance(id))
        assertEquals(0, finalCalls); assertNull(db.incidentReconciliationDao().provenance(id))
        assertNull(db.journeyDao().observeCollectable().first())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertNull(db.journeyDao().getById(id)!!.endedAt)
        assertTrue(db.probableEndPresentationDao().pendingAutomaticClaims().isEmpty())
    }
    @Test fun differentDeviceCommittedEpisodeSupersedesOldClaimWithoutRelabellingItsIdentity() = runBlocking {
        beforeFinal = { throw kotlinx.coroutines.CancellationException("Interrupted before dispatch") }
        try { coordinator().advance(id); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
        val oldClaim = db.incidentReconciliationDao().get(id)!!.claimJson
        currentCase = "40000000-0000-4000-8000-000000000022"
        terminalProof = IncidentProvenance(claim().copy(admittedAt = null), utc(now), utc(now + 604_800_000),
            "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
        server = RemoteJourneyState(id, owner, JourneyStatus.UNRESOLVED_INCIDENT, now, null)
        reopen(); assertTrue(coordinator().reconcileServerTerminal(server))
        assertEquals("SUPERSEDED", db.incidentReconciliationDao().get(id)!!.claimState)
        assertEquals(oldClaim, db.incidentReconciliationDao().get(id)!!.claimJson)
        val saved = Json.decodeFromString<IncidentProvenance>(db.incidentReconciliationDao().provenance(id)!!.provenanceJson)
        assertEquals(currentCase, saved.claim.verificationCaseId)
    }

    @Test fun preDispatchFailureAfterClaimReleasesOnlyNeverDispatchedReservation() = runBlocking {
        beforeFinalDispatch = { AuthorityRead.Failed(IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED) }
        assertEquals(IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED, coordinator().advance(id))
        val row = db.incidentReconciliationDao().get(id)!!
        assertNotNull(row.claimId); assertEquals("BLOCKED", row.claimState)
        assertEquals("REFUSED", row.claimDispatch); assertEquals(0, finalCalls)
        reopen(); assertTrue(coordinator().requestStop(id, owner))
        assertNull(db.journeyDao().observeCollectable().first())
    }

    @Test fun inconsistentDispatchedClaimRetriesExactIdentityAndFinalRefusalUnblocksComplete() = runBlocking {
        finalOverride = { AuthorityRead.Failed(IncidentReconciliationOutcome.INCONSISTENT) }
        assertEquals(IncidentReconciliationOutcome.INCONSISTENT, coordinator().advance(id))
        val old = db.incidentReconciliationDao().get(id)!!
        assertEquals("MAY_HAVE_DISPATCHED", old.claimDispatch)
        assertNull(db.journeyDao().completeActive(now)); assertFalse(coordinator().requestStop(id, owner))
        reopen(); coordinator().advance(id); assertEquals(1, finalCalls)
        now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        finalOverride = { null }; finalDecision = "BLOCKED"
        assertEquals(IncidentReconciliationOutcome.FINAL_GUARD_BLOCKED, coordinator().advance(id))
        assertEquals(old.claimId, db.incidentReconciliationDao().get(id)!!.claimId)
        assertEquals(attempts.first(), attempts.last()); assertEquals(2, finalCalls)
        assertNotNull(db.journeyDao().completeActive(now))
    }

    @Test fun inconsistentDispatchedClaimRecoversCommittedTruthInsteadOfReleasing() = runBlocking {
        finalOverride = {
            terminalProof = IncidentProvenance(it.copy(admittedAt = null), utc(now), utc(now + 604_800_000),
                "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
            server = RemoteJourneyState(id, owner, JourneyStatus.UNRESOLVED_INCIDENT, now, null)
            AuthorityRead.Failed(IncidentReconciliationOutcome.INCONSISTENT)
        }
        assertEquals(IncidentReconciliationOutcome.INCONSISTENT, coordinator().advance(id))
        reopen(); now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals("COMMITTED", db.incidentReconciliationDao().get(id)!!.claimState)
        assertEquals(1, finalCalls); assertNotNull(db.incidentReconciliationDao().provenance(id))
    }

    @Test fun independentCoordinatorsCannotDispatchSameRevisionTwice() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFinal = { entered.complete(Unit); release.await() }
        val first = async { coordinator().advance(id) }; entered.await()
        coordinator().advance(id); assertEquals(1, finalCalls)
        release.complete(Unit); assertEquals(IncidentReconciliationOutcome.TERMINAL, first.await())
    }

    @Test fun lateOldRefusalCannotReleaseNewClaimInSharedRoom() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val releaseOld = CompletableDeferred<Unit>()
        val enteredNew = CompletableDeferred<Unit>(); val releaseNew = CompletableDeferred<Unit>()
        var calls = 0
        finalOverride = { claim -> when (++calls) {
            1 -> { entered.complete(Unit); releaseOld.await()
                AuthorityRead.Value(IncidentFinalizerResponse("BLOCKED", JourneyStatus.ACTIVE, null,
                    claim.boundaryAt, claim.verificationCaseId, null)) }
            2 -> AuthorityRead.Value(IncidentFinalizerResponse("BLOCKED", JourneyStatus.ACTIVE, null,
                claim.boundaryAt, claim.verificationCaseId, null))
            else -> { enteredNew.complete(Unit); releaseNew.await(); null }
        } }
        val oldResponse = async { coordinator().advance(id) }; entered.await()
        val oldId = db.incidentReconciliationDao().get(id)!!.claimId
        now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.FINAL_GUARD_BLOCKED, coordinator().advance(id))
        now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        val newResponse = async { coordinator().advance(id) }; enteredNew.await()
        val newRow = db.incidentReconciliationDao().get(id)!!
        assertNotEquals(oldId, newRow.claimId)
        releaseOld.complete(Unit); oldResponse.await()
        assertEquals(newRow, db.incidentReconciliationDao().get(id))
        releaseNew.complete(Unit); assertEquals(IncidentReconciliationOutcome.TERMINAL, newResponse.await())
    }

    @Test fun lateFailureCannotRegressAuthoritativeTerminalRecoveredByAnotherCoordinator() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        finalOverride = {
            entered.complete(Unit); release.await()
            AuthorityRead.Failed(IncidentReconciliationOutcome.INCONSISTENT)
        }
        val old = async { coordinator().advance(id) }; entered.await()
        terminalProof = IncidentProvenance(claim().copy(admittedAt = null), utc(now), utc(now + 604_800_000),
            "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
        server = RemoteJourneyState(id, owner, JourneyStatus.UNRESOLVED_INCIDENT, now, null)
        assertTrue(coordinator().reconcileServerTerminal(server))
        val committed = db.incidentReconciliationDao().get(id)!!
        release.complete(Unit); assertEquals(IncidentReconciliationOutcome.TERMINAL, old.await())
        assertEquals(committed, db.incidentReconciliationDao().get(id))
        assertEquals(JourneyStatus.UNRESOLVED_INCIDENT, db.journeyDao().getById(id)!!.status)
    }

    @Test fun accountStopReservationBeforeClaimSurvivesReopenWithoutQueuedSignOut() = runBlocking {
        val reservations = RoomAccountStopReservations(db, handoffGate) { now }
        val op = requireNotNull(reservations.reserve(id, owner))
        assertTrue(reservations.markDispatch(op)); reservations.uncertain(op)
        reopen()
        assertEquals(IncidentReconciliationOutcome.EXPLICIT_PENDING, coordinator().advance(id))
        assertNull(db.incidentReconciliationDao().get(id)!!.claimId)
        assertEquals(0, stopCalls); assertEquals(0, finalCalls)
        assertEquals(op, RoomAccountStopReservations(db, handoffGate).reserve(id, owner))
        server = RemoteJourneyState(id, owner, JourneyStatus.CANCELLED, now, null)
        now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals("ACKNOWLEDGED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(0, stopCalls)
    }

    @Test fun claimFirstRejectsAccountStopReservationBeforeOutboundDispatch() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFinal = { entered.complete(Unit); release.await() }
        val pending = async { coordinator().advance(id) }; entered.await()
        assertNull(RoomAccountStopReservations(db, handoffGate).reserve(id, owner))
        assertEquals(0, stopCalls)
        release.complete(Unit); assertEquals(IncidentReconciliationOutcome.TERMINAL, pending.await())
    }

    @Test fun undispatchedAccountReservationIsSafelyRecoverableAfterReopen() = runBlocking {
        val op = requireNotNull(RoomAccountStopReservations(db, handoffGate).reserve(id, owner))
        reopen(); coordinator().advance(id)
        assertEquals("REFUSED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(op.id, db.incidentReconciliationDao().get(id)!!.explicitActionId)
        assertEquals(0, stopCalls); assertEquals(0, finalCalls)
        assertTrue(coordinator().requestStop(id, owner))
        assertNotEquals(op.id, db.incidentReconciliationDao().get(id)!!.explicitActionId)
    }

    @Test fun offlineFirstAdmissionIsUploadedBeforeExplicitStopWithoutMovingEta() = runBlocking {
        serverMissing = true
        val original = db.journeyDao().getById(id)!!
        assertTrue(coordinator().requestStop(id, owner))
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertEquals(1, stopCalls); assertEquals(0, finalCalls)
        val after = db.journeyDao().getById(id)!!
        assertEquals(original.startedAt, after.startedAt)
        assertEquals(original.expectedArrivalAt, after.expectedArrivalAt)
        assertEquals(JourneyStatus.CANCELLED, after.status)
    }

    @Test fun oldPendingTeardownEffectsCannotStopNewActiveJourneyAfterReopen() = runBlocking {
        failEffects = true; assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertTrue(db.incidentReconciliationDao().get(id)!!.effectsPending)
        val newerId = "20000000-0000-4000-8000-000000000022"
        assertTrue(db.journeyDao().insertIfNoActive(Journey(newerId, "Synthetic B", now + 1_000,
            now, JourneyStatus.ACTIVE, null).toEntity(), BoundaryReceipt(100, 4, "isolated")))
        reopen()
        var target: String? = null
        val teardown = com.journeycontinuity.app.service.ExactJourneyRuntimeTeardown(OwnerAdmissionGate(),
            { db.journeyDao().getActive()?.id }, { target = it; false })
        failEffects = false
        effectOverride = { assertTrue(teardown.stop(it)) }
        repeat(2) { coordinator().restoreEffects() }
        assertNull(target); assertEquals(newerId, db.journeyDao().observeCollectable().first()!!.id)
        assertFalse(db.incidentReconciliationDao().get(id)!!.effectsPending)
        assertEquals(1, effects)
    }

    private fun authoritativeAutoEnd(): AutoEndProvenance {
        val finalized = Instant.ofEpochMilli(now).plusNanos(123_000)
        return AutoEndProvenance("AUTO_ENDED", "30000000-0000-4000-8000-000000000077", "AUTO_ENDED",
            finalized.toString(), finalized.minusSeconds(1).toString(), id, owner,
            "40000000-0000-4000-8000-000000000077", 17, "alabarin-probable-end-v1",
            Instant.ofEpochMilli(start).plusNanos(456_000).toString(), 9,
            finalized.minusSeconds(1_801).toString(), finalized.toString())
    }
    private fun remoteAutoEnd() {
        autoProof = authoritativeAutoEnd()
        server = RemoteJourneyState(id, owner, JourneyStatus.AUTO_ENDED, now, null)
    }

    @Test fun crossDeviceAutomaticEndPersistsExactReceiptWithoutInventingLocalPrompt() = runBlocking {
        remoteAutoEnd()
        val original = db.journeyDao().getById(id)!!
        val boundary = db.unresolvedBoundaryDao().get(id)!!
        assertTrue(coordinator().reconcileServerTerminal(server))
        val receipt = db.incidentReconciliationDao().autoEndProvenance(id)!!
        assertEquals(autoProof, Json.decodeFromString<AutoEndProvenance>(receipt.provenanceJson))
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(id)!!.status)
        assertEquals(now, db.journeyDao().getById(id)!!.endedAt)
        assertNull(db.journeyDao().getById(id)!!.completedAt); assertNull(db.journeyDao().getActive())
        assertEquals(original.startedAt, db.journeyDao().getById(id)!!.startedAt)
        assertEquals(original.expectedArrivalAt, db.journeyDao().getById(id)!!.expectedArrivalAt)
        assertEquals(boundary, db.unresolvedBoundaryDao().get(id))
        assertNull(db.incidentReconciliationDao().get(id)!!.claimId)
        assertNull(db.incidentReconciliationDao().provenance(id))
        assertNull(db.probableEndPresentationDao().byId(autoProof!!.presentationEpisodeId))
        assertTrue(db.probableEndDao().unclosed(id).isEmpty())
        assertEquals(0, finalCalls); assertEquals(0, stopCalls)
        assertEquals(1, db.telemetryDao().getAfterSequence(id, 0, 50).size)
        reopen()
        assertTrue(coordinator().reconcileServerTerminal(server))
        assertEquals(receipt, db.incidentReconciliationDao().autoEndProvenance(id))
        assertEquals(1, effects); assertNull(db.journeyDao().observeCollectable().first())
        assertNull(db.telemetryDao().insertForActiveJourney(sample()))
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now, 0))
    }

    @Test fun unavailableAutomaticOwnerReadRetainsActiveSlotAndTypedPendingOutcome() = runBlocking {
        remoteAutoEnd(); autoReadFailure = IncidentReconciliationOutcome.UNAVAILABLE
        assertEquals(IncidentReconciliationOutcome.UNAVAILABLE, coordinator().advance(id))
        assertNull(db.incidentReconciliationDao().autoEndProvenance(id))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertNull(db.journeyDao().observeCollectable().first())
        reopen(); now += RoomIncidentReconciliationCoordinator.RETRY_MILLIS
        autoReadFailure = null
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        assertNotNull(db.incidentReconciliationDao().autoEndProvenance(id))
    }

    @Test fun automaticReadAuthenticationAndMalformedProvenanceNeverInventTerminalTruth() = runBlocking {
        remoteAutoEnd()
        autoReadFailure = IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED
        assertEquals(IncidentReconciliationOutcome.AUTHENTICATION_REQUIRED, coordinator().advance(id))
        autoReadFailure = null
        val valid = autoProof!!
        listOf(valid.copy(ownerId = case), valid.copy(journeyId = case), valid.copy(finalizedAt = utc(now + 1)),
            valid.copy(notBeforeAt = valid.registeredAt), valid.copy(endedAt = "infinity")).forEach { invalid ->
            autoProof = invalid; assertFalse(coordinator().reconcileServerTerminal(server))
            assertNull(db.incidentReconciliationDao().autoEndProvenance(id))
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        }
    }

    @Test fun automaticProvenanceAndTerminalCommitRollBackTogetherOnWorkflowFailure() = runBlocking {
        remoteAutoEnd()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_terminal_fixture
            BEFORE UPDATE ON journey_terminal_reconciliations WHEN NEW.lastOutcome='TERMINAL'
            BEGIN SELECT RAISE(ABORT, 'Injected atomic write failure'); END""")
        assertFalse(coordinator().reconcileServerTerminal(server))
        assertNull(db.incidentReconciliationDao().autoEndProvenance(id))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertEquals(0, effects)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_terminal_fixture")
        reopen(); assertTrue(coordinator().reconcileServerTerminal(server))
    }

    @Test fun automaticEndSettlesEarlierOfflineStopWithoutSendingACompetingRequest() = runBlocking {
        assertTrue(coordinator().requestStop(id, owner)); val action = db.incidentReconciliationDao().get(id)!!
        remoteAutoEnd(); reopen()
        assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator().advance(id))
        val row = db.incidentReconciliationDao().get(id)!!
        assertEquals(action.explicitActionId, row.explicitActionId); assertEquals("SUPERSEDED", row.explicitState)
        assertEquals(0, stopCalls); assertEquals(0, finalCalls)
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(id)!!.status)
    }

    @Test fun automaticEndSupersedesUnacknowledgedOfflineCompleteButNotAcknowledgedTerminal() = runBlocking {
        assertNotNull(db.journeyDao().completeActive(now)); remoteAutoEnd()
        assertTrue(coordinator().reconcileServerTerminal(server))
        assertEquals("SUPERSEDED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(id)!!.status)
        assertNull(db.journeyDao().getById(id)!!.completedAt)
        server = RemoteJourneyState(id, owner, JourneyStatus.CANCELLED, now + 1, null)
        assertFalse(coordinator().reconcileServerTerminal(server))
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(id)!!.status)
    }

    @Test fun lateAutomaticReadFailureCannotRegressTerminalRecoveredByIndependentCoordinator() = runBlocking {
        remoteAutoEnd()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeAutoRead = { entered.complete(Unit); release.await() }
        val old = async { coordinator().reconcileServerTerminal(server) }; entered.await()
        beforeAutoRead = {}
        assertTrue(coordinator().reconcileServerTerminal(server))
        val committed = db.incidentReconciliationDao().get(id)!!
        autoReadFailure = IncidentReconciliationOutcome.UNAVAILABLE
        release.complete(Unit); assertFalse(old.await())
        assertEquals(committed, db.incidentReconciliationDao().get(id))
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(id)!!.status)
        assertNotNull(db.incidentReconciliationDao().autoEndProvenance(id))
    }

    @Test fun statusChangedDuringAutomaticOwnerReadCannotBindTheOldResponse() = runBlocking {
        remoteAutoEnd()
        val old = server
        beforeAutoRead = { server = RemoteJourneyState(id, owner, JourneyStatus.COMPLETED, null, now) }
        assertFalse(coordinator().reconcileServerTerminal(old))
        assertNull(db.incidentReconciliationDao().autoEndProvenance(id))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        beforeAutoRead = {}; assertTrue(coordinator().reconcileServerTerminal(server))
        assertEquals(JourneyStatus.COMPLETED, db.journeyDao().getById(id)!!.status)
    }

    @Test fun automaticTerminalEffectsSurviveInterruptedTeardownAndDatabaseReopen() = runBlocking {
        remoteAutoEnd(); failEffects = true
        assertTrue(coordinator().reconcileServerTerminal(server))
        assertTrue(db.incidentReconciliationDao().get(id)!!.effectsPending)
        reopen(); failEffects = false; repeat(2) { coordinator().restoreEffects() }
        assertEquals(1, effects); assertFalse(db.incidentReconciliationDao().get(id)!!.effectsPending)
        assertNotNull(db.incidentReconciliationDao().autoEndProvenance(id))
        assertNull(db.journeyDao().observeCollectable().first())
    }

    @Test fun staleActiveSyncRecoversAutomaticOwnerReceiptAndDrainsOnlyExistingEvidence() = runBlocking {
        remoteAutoEnd()
        val c = coordinator()
        val sync = ReliableSyncEngine(RoomLocalSyncStore(db.journeyDao(), db.telemetryDao(), db.syncStateDao()),
            cloud, clock = SyncClock { now }, reconcileAuthoritativeTerminal = c::reconcileServerTerminal,
            reconcileExplicitIntent = c::reconcilePendingExplicit)
        assertEquals(SyncRunResult.Success, sync.synchronize())
        assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(id)!!.status)
        assertNotNull(db.incidentReconciliationDao().autoEndProvenance(id))
        assertEquals(1L, db.syncStateDao().get(id)!!.highestTelemetrySequenceSynced)
        assertEquals(1, db.telemetryDao().getAfterSequence(id, 0, 50).size)
        assertEquals(0, finalCalls); assertEquals(0, stopCalls)
    }

    private suspend fun localAutomaticClaim(presentationId: String) {
        val r = autoProof!!
        val at = java.time.Instant.parse(r.registeredAt).toEpochMilli()
        val candidate = ProbableEndEpisodeEntity(r.candidateEpisodeId, id, r.policyVersion,
            createdAtMillis = at - 1, generation = r.detectorGeneration, evidenceFloorSequence = 0,
            evidenceFloorEventTimeMillis = 0,
            first = com.journeycontinuity.app.domain.probableend.ObservationEvidenceReference(1, at - 240_000, "a".repeat(64)),
            final = com.journeycontinuity.app.domain.probableend.ObservationEvidenceReference(4, at - 60_000, "b".repeat(64)),
            observationCount = 4, finalDistanceMeters = 10.0, finalAccuracyMeters = 10.0,
            destinationSlot = com.journeycontinuity.app.domain.probableend.DestinationEvidenceReference(id).slot)
        db.probableEndDao().insert(candidate)
        db.probableEndPresentationDao().insert(ProbableEndPresentationEntity(presentationId, id,
            candidate.id, r.detectorGeneration, r.policyVersion, java.time.Instant.parse(r.historyBaselineAt).toEpochMilli(),
            r.guardVersion, status = "AUTO_END_CLAIMED", liveSlot = null, createdAtMillis = at - 1,
            presentedAtMillis = at, confirmationDeadlineAtMillis = at + 1_800_000,
            deadlineStatus = "EXPIRED_PENDING_FINALIZATION", deadlineExpiredAtMillis = now,
            serverWindowRegisteredAtMillis = at, serverWindowNotBeforeAtMillis = at + 1_800_000,
            finalizationClaimedAtMillis = now))
    }

    @Test fun existingExactLocalClaimRetainsItsRealSection09ResultHistory() = runBlocking {
        remoteAutoEnd(); localAutomaticClaim(autoProof!!.presentationEpisodeId)
        assertTrue(coordinator().reconcileServerTerminal(server))
        val p = db.probableEndPresentationDao().byId(autoProof!!.presentationEpisodeId)!!
        p.validate(); assertEquals("AUTO_ENDED", p.status)
        assertEquals(now, p.automaticEndedAtMillis); assertNull(p.respondedAtMillis)
        assertNotNull(p.finalizationClaimedAtMillis)
        assertEquals(autoProof!!.presentationEpisodeId, db.probableEndPresentationDao().observePendingAutomaticResult().first()!!.episodeId)
        assertNotNull(db.incidentReconciliationDao().autoEndProvenance(id))
    }

    @Test fun differentLocalClaimCannotBeRelabelledAsTheOtherDevicesPresentation() = runBlocking {
        remoteAutoEnd()
        val localId = "30000000-0000-4000-8000-000000000078"
        localAutomaticClaim(localId)
        assertTrue(coordinator().reconcileServerTerminal(server))
        val p = db.probableEndPresentationDao().byId(localId)!!
        p.validate(); assertEquals("INVALIDATED", p.status)
        assertNull(p.automaticEndedAtMillis); assertNull(p.respondedAtMillis)
        assertNull(db.probableEndPresentationDao().byId(autoProof!!.presentationEpisodeId))
        assertNull(db.probableEndPresentationDao().observePendingAutomaticResult().first())
        assertNotNull(db.incidentReconciliationDao().autoEndProvenance(id))
    }

    @Test fun delayedRuntimeTicketAfterNewAdmissionCannotStopItsServiceGenerationOrStartId() = runBlocking {
        val signals = com.journeycontinuity.app.service.JourneyRuntimeSignals()
        signals.serviceStarted(); signals.startCommand(1); signals.begin(id)
        val oldTicket = requireNotNull(signals.stopTicket(id))
        remoteAutoEnd(); failEffects = true; assertTrue(coordinator().reconcileServerTerminal(server))
        val nextId = "20000000-0000-4000-8000-000000000022"
        assertTrue(db.journeyDao().insertIfNoActive(Journey(nextId, "Synthetic B", now + 1_000,
            now, JourneyStatus.ACTIVE, null).toEntity(), BoundaryReceipt(100, 4, "isolated")))
        signals.startCommand(2)
        val nextGeneration = signals.begin(nextId); signals.listenerRegistered(nextGeneration, nextId)
        reopen(); failEffects = false
        effectOverride = { target ->
            assertEquals(id, target)
            assertTrue(signals.executeStop(oldTicket, { error("Stale Android stopSelfResult must not execute") },
                { error("New Journey's collection must not stop") }))
        }
        repeat(2) { coordinator().restoreEffects() }
        assertEquals(nextId, signals.monitoringJourneyId.value)
        assertEquals(nextId, db.journeyDao().observeCollectable().first()!!.id)
        assertTrue(signals.serviceRunning.value); assertEquals(1, effects)
        signals.serviceStarted(); signals.startCommand(1)
        val restored = signals.begin(nextId); signals.listenerRegistered(restored, nextId)
        assertTrue(signals.executeStop(oldTicket, { error("Old service epoch") }, { error("Restored collection") }))
        assertEquals(nextId, signals.monitoringJourneyId.value)
    }

    /** Actual Room reservation/acknowledgement plus production account, identity and heartbeat lock composition. */
    private inner class SharedRoomStopFixture {
        val admission = OwnerAdmissionGate()
        val handoff = JourneyTerminalHandoffGate()
        var session: TravellerSessionState = TravellerSessionState.Authenticated(owner)
        var remoteState = RemoteJourneyState(id, owner, JourneyStatus.ACTIVE, null, null)
        var sends = 0
        var signedOut = false
        var beforeHeartbeatResponse: suspend () -> Unit = {}
        var afterHeartbeat: suspend (String) -> Unit = {}
        var afterPreparation: suspend () -> Unit = {}
        var afterPermission: suspend () -> Unit = {}
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val authEntered = CompletableDeferred<Unit>()
        val backend = object : TravellerAuthBackend {
            override suspend fun awaitInitialization() = Unit
            override fun sessionState() = session
            override suspend fun signInAnonymously() = error("No authentication writes")
        }
        val ownerStore = object : TravellerIdentityStore {
            override fun expectedTravellerUserId() = owner
            override fun persistExpectedTravellerUserIdIfAbsent(userId: String) = false
        }
        val identity = TravellerIdentityCoordinator(backend, ownerStore, admissionGate = admission)
        val reservations = RoomAccountStopReservations(db, handoff) { now }
        val account = GuardedTravellerActiveJourneySignOutGateway(object : ActiveJourneyStopLocal {
            override suspend fun activeJourney() = db.journeyDao().getActive()?.toDomain()
            override suspend fun journey(journeyId: String) = db.journeyDao().getById(journeyId)?.toDomain()
            override suspend fun persistStopped(journeyId: String, endedAt: Long): Journey? =
                error("Acknowledgement must use actual RoomAccountStopReservations")
            override suspend fun fallbackStopped(journeyId: String) = db.fallbackAttemptDao().countUnsentForJourney(journeyId) == 0
        }, object : ActiveJourneyStopRemote {
            override suspend fun ownerJourneyState(journeyId: String, ownerId: String) = remoteState
            override suspend fun stop(journeyId: String, ownerId: String): MonitoringStopAcknowledgement {
                assertEquals(id, journeyId); assertEquals(owner, ownerId)
                stopEntered.complete(Unit); releaseStop.await()
                remoteState = remoteState.copy(status = JourneyStatus.CANCELLED, endedAt = now)
                return MonitoringStopAcknowledgement(JourneyStatus.CANCELLED, now)
            }
        }, identity, ownerStore, backend,
            AccountLocalSession { signedOut = true; session = TravellerSessionState.NotAuthenticated },
            ActiveJourneyRuntimeStop { true }, object : MonitoringStoppedResultStore {
                var pending: String? = null
                override fun pendingJourneyId() = pending
                override fun savePendingJourneyId(journeyId: String): Boolean { pending = journeyId; return true }
                override fun clearPendingJourneyId(journeyId: String): Boolean { pending = null; return true }
            }, admission, reservations = reservations)
        val heartbeatStore = RoomHeartbeatLocalStore(db.heartbeatDao())
        val heartbeat = HeartbeatCoordinator(object : HeartbeatLocalStore by heartbeatStore {
            override suspend fun canDispatch(allocation: HeartbeatAllocation): Boolean {
                val allowed = heartbeatStore.canDispatch(allocation)
                afterPermission()
                return allowed
            }
        }, object : HeartbeatGateway {
            override suspend fun prepareDispatch(): HeartbeatDispatchCheck {
                authEntered.complete(Unit)
                return prepareHeartbeatDispatch(identity).also { afterPreparation() }
            }
            override suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState {
                sends++; beforeHeartbeatResponse()
                return HeartbeatServerState(CloudMonitoringPhase.EVIDENCE_FRESH, now, heartbeat.sequence)
            }
        }, clock = { now }, terminalHandoffGate = handoff, provisioningObserver = { afterHeartbeat(it) })
    }

    private fun seedMonitoringFixture() {
        // Test-only initial state for the existing online account route; no production Resume is added.
        db.openHelper.writableDatabase.execSQL("UPDATE journey_operational_boundaries SET state='MONITORING', pauseReason=NULL WHERE journeyId=?", arrayOf(id))
    }

    @Test fun actualRoomAccountStopAcknowledgementCannotDeadlockWithHeartbeatAuthentication() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val stop = async(start = CoroutineStart.UNDISPATCHED) { f.account.confirmAndSignOut(id, owner) }
        f.stopEntered.await()
        val heartbeat = async(start = CoroutineStart.UNDISPATCHED) {
            f.heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true)
        }
        f.authEntered.await(); f.releaseStop.complete(Unit)
        withTimeout(5_000) {
            assertTrue(stop.await() is ActiveJourneySignOutResult.SignedOut)
            assertEquals(HeartbeatAttemptResult.Failed, heartbeat.await())
            f.admission.withLock { f.handoff.withLock { } }
        }
        assertTrue(f.signedOut); assertEquals(0, f.sends)
        assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById(id)!!.status)
        assertEquals(now, db.journeyDao().getById(id)!!.endedAt); assertNull(db.journeyDao().getActive())
        assertEquals("ACKNOWLEDGED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(0L, db.heartbeatDao().observe(id).first()!!.lastAllocatedHeartbeatSequence)
        reopen(); assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById(id)!!.status)
        assertEquals(1, db.telemetryDao().getAfterSequence(id, 0, 50).size)
    }

    @Test fun actualRoomStopCanCommitWhilePostHeartbeatProvisioningWaitsForAdmission() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val sent = CompletableDeferred<Unit>(); val releaseSend = CompletableDeferred<Unit>()
        val provision = CompletableDeferred<Unit>()
        f.beforeHeartbeatResponse = { sent.complete(Unit); releaseSend.await() }
        f.afterHeartbeat = { provision.complete(Unit); f.identity.requireAuthenticatedTraveller() }
        val heartbeat = async { f.heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true) }; sent.await()
        // Stop now waits for the existing transport handoff before committing its reservation.
        val stop = async(start = CoroutineStart.UNDISPATCHED) { f.account.confirmAndSignOut(id, owner) }
        releaseSend.complete(Unit); f.stopEntered.await(); provision.await(); f.releaseStop.complete(Unit)
        withTimeout(5_000) {
            assertTrue(stop.await() is ActiveJourneySignOutResult.SignedOut)
            assertEquals(HeartbeatAttemptResult.Sent, heartbeat.await())
            f.admission.withLock { f.handoff.withLock { } }
        }
        assertEquals(1, f.sends); assertTrue(f.signedOut)
        assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById(id)!!.status)
        assertEquals("ACKNOWLEDGED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now + 10_000, 0))
    }

    @Test fun finalHeartbeatPermissionRejectsStaleAllocationPauseAndTerminalSlot() = runBlocking {
        seedMonitoringFixture()
        val allocation = requireNotNull(db.heartbeatDao().allocateForActiveJourney(id, now, 0))
        val store = RoomHeartbeatLocalStore(db.heartbeatDao())
        assertTrue(store.canDispatch(allocation))
        assertFalse(store.canDispatch(allocation.copy(journeyId = owner)))
        assertFalse(store.canDispatch(allocation.copy(clientSentAt = now - 1)))
        val newer = requireNotNull(db.heartbeatDao().allocateForActiveJourney(id, now + 1, 0))
        assertFalse(store.canDispatch(allocation)); assertTrue(store.canDispatch(newer))
        assertTrue(coordinator().requestStop(id, owner))
        assertFalse(store.canDispatch(newer))
        reopen(); assertFalse(RoomHeartbeatLocalStore(db.heartbeatDao()).canDispatch(newer))
        server = RemoteJourneyState(id, owner, JourneyStatus.CANCELLED, now, null)
        assertTrue(coordinator().reconcileServerTerminal(server))
        assertFalse(RoomHeartbeatLocalStore(db.heartbeatDao()).canDispatch(newer))
        assertNull(db.journeyDao().getActive())
    }

    @Test fun preparedHeartbeatCannotPassCommittedAccountReservation() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val prepared = CompletableDeferred<Unit>(); val continueHeartbeat = CompletableDeferred<Unit>()
        f.afterPreparation = { prepared.complete(Unit); continueHeartbeat.await() }
        val heartbeat = async { f.heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true) }
        prepared.await()
        val stop = async { f.account.confirmAndSignOut(id, owner) }; f.stopEntered.await()
        assertEquals("ACCOUNT_RESERVED", db.incidentReconciliationDao().get(id)!!.explicitState)
        continueHeartbeat.complete(Unit)
        withTimeout(5_000) { assertEquals(HeartbeatAttemptResult.Skipped, heartbeat.await()) }
        assertEquals(0, f.sends)
        assertEquals(0L, db.heartbeatDao().observe(id).first()!!.lastAllocatedHeartbeatSequence)
        f.releaseStop.complete(Unit)
        withTimeout(5_000) { assertTrue(stop.await() is ActiveJourneySignOutResult.SignedOut) }
        assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById(id)!!.status)
    }

    @Test fun reservedAndUncertainStopRejectAllocationAndAlreadyAllocatedHeartbeatAfterReopen() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val allocated = requireNotNull(db.heartbeatDao().allocateForActiveJourney(id, now, 0))
        val op = requireNotNull(f.reservations.reserve(id, owner))
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now + 1, 0))
        assertFalse(f.heartbeatStore.canDispatch(allocated))
        assertTrue(f.reservations.markDispatch(op)); f.reservations.uncertain(op)
        f.reservations.releaseUndispatched(op) // A lost acknowledgement is not a refusal.
        reopen()
        assertEquals("ACCOUNT_UNCERTAIN", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(op.id, db.incidentReconciliationDao().get(id)!!.explicitActionId)
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now + 1, 0))
        assertFalse(RoomHeartbeatLocalStore(db.heartbeatDao()).canDispatch(allocated))
        val restored = SharedRoomStopFixture()
        assertEquals(HeartbeatAttemptResult.Skipped,
            restored.heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true))
        assertEquals(0, restored.sends)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertEquals(1, db.telemetryDao().getAfterSequence(id, 0, 50).size)
    }

    @Test fun authoritativeActiveRecoveryReleasesOnlyUndispatchedReservationAndRespectsPause() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val op = requireNotNull(f.reservations.reserve(id, owner))
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now, 0))
        // Existing recovery reads authenticated exact-owner ACTIVE before settling NEVER_DISPATCHED.
        assertEquals(IncidentReconciliationOutcome.EXPLICIT_PENDING, coordinator().advance(id))
        assertEquals("REFUSED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(op.id, db.incidentReconciliationDao().get(id)!!.explicitActionId)
        assertEquals(HeartbeatAttemptResult.Sent,
            f.heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true))
        assertEquals(1, f.sends)
        assertTrue(coordinator().requestStop(id, owner))
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now + 60_000, 0))
        assertFalse(f.heartbeatStore.canDispatch(HeartbeatAllocation(id, 1, now, 0)))
    }

    @Test fun staleReservationReleaseCannotClearNewStopOrTerminalAcknowledgement() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val old = requireNotNull(f.reservations.reserve(id, owner))
        f.reservations.releaseUndispatched(old)
        val current = requireNotNull(f.reservations.reserve(id, owner))
        assertNotEquals(old.id, current.id)
        f.reservations.releaseUndispatched(old); f.reservations.uncertain(old)
        assertEquals(current.id, db.incidentReconciliationDao().get(id)!!.explicitActionId)
        assertEquals("ACCOUNT_RESERVED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now, 0))
        assertTrue(f.reservations.markDispatch(current)); assertTrue(f.reservations.acknowledge(current, now))
        f.reservations.releaseUndispatched(old); f.reservations.releaseUndispatched(current)
        assertEquals("ACKNOWLEDGED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById(id)!!.status)
        assertNull(db.heartbeatDao().allocateForActiveJourney(id, now + 1, 0))
    }

    @Test fun reservationCannotCommitBetweenFinalPermissionAndTransportHandoff() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val checked = CompletableDeferred<Unit>(); val transport = CompletableDeferred<Unit>()
        f.afterPermission = { checked.complete(Unit); transport.await() }
        val heartbeat = async { f.heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true) }
        checked.await()
        val reserve = async(start = CoroutineStart.UNDISPATCHED) {
            f.admission.withLock { f.reservations.reserve(id, owner) }
        }
        assertFalse(reserve.isCompleted)
        assertNull(db.incidentReconciliationDao().get(id))
        transport.complete(Unit)
        withTimeout(5_000) {
            assertEquals(HeartbeatAttemptResult.Sent, heartbeat.await())
            assertNotNull(reserve.await())
        }
        assertEquals(1, f.sends) // Already handed off BEFORE the reservation committed.
        assertEquals("ACCOUNT_RESERVED", db.incidentReconciliationDao().get(id)!!.explicitState)
        assertEquals(HeartbeatAttemptResult.Skipped,
            f.heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true))
        assertEquals(1, f.sends)
    }

    @Test fun cancelledReservationWaitLeavesNoStopAndReleasesAdmission() = runBlocking {
        seedMonitoringFixture()
        val f = SharedRoomStopFixture()
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holding = async { f.handoff.withLock { held.complete(Unit); release.await() } }
        held.await()
        val reserve = async(start = CoroutineStart.UNDISPATCHED) {
            f.admission.withLock { f.reservations.reserve(id, owner) }
        }
        assertFalse(reserve.isCompleted)
        reserve.cancel(); reserve.join(); release.complete(Unit); holding.await()
        assertNull(db.incidentReconciliationDao().get(id))
        withTimeout(5_000) { f.admission.withLock { f.handoff.withLock { } } }
        assertNotNull(f.reservations.reserve(id, owner))
    }
}
