package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.data.unresolved.*
import com.journeycontinuity.app.sync.*
import com.journeycontinuity.app.ui.TravellerAuthorityViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import com.journeycontinuity.app.domain.probableend.*

@RunWith(AndroidJUnit4::class)
class TravellerAuthorityUiDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "traveller-authority-ui-test.db"
    private val id = "20000000-0000-4000-8000-000000000201"
    private val owner = "10000000-0000-4000-8000-000000000201"
    private val at = 1_790_000_000_000L
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()

    @Test fun olderIncidentThenNewerAutomaticResultAcknowledgesInOrder() = coexistence(true)

    @Test fun olderAutomaticThenNewerIncidentResultAcknowledgesInOrder() = coexistence(false)

    @Test fun equalTerminalTimesUseDurableJourneyIdTieBreak() = coexistence(true, true)

    private fun coexistence(automaticNewer: Boolean, tied: Boolean = false) = runBlocking {
        context.deleteDatabase(name)
        var db = open()
        val autoId = "20000000-0000-4000-8000-000000000202"
        val finalAt = at + 90_000_000
        try {
            db.journeyDao().insertIfNoActive(JourneyEntity(id, "Ilorin", at + 3_600_000,
                at, JourneyStatus.ACTIVE, null, 1))
            val boundary = db.unresolvedBoundaryDao().get(id)!!
            val incidentAt = finalAt + if (!automaticNewer && !tied) 10_000 else 0
            val episodeAt = Instant.ofEpochMilli(boundary.boundaryMillis - 1_000).toString()
            val proof = IncidentProvenance(IncidentClaim(id, owner, boundary.admissionId,
                boundary.policyVersion, null, Instant.ofEpochMilli(boundary.etaMillis).toString(),
                Instant.ofEpochMilli(boundary.boundaryMillis).toString(),
                "40000000-0000-4000-8000-000000000201", 1, episodeAt, 1, episodeAt),
                Instant.ofEpochMilli(incidentAt).toString(),
                Instant.ofEpochMilli(incidentAt + 604_800_000).toString(), "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
            proof.validate()
            db.incidentReconciliationDao().insertProvenance(JourneyIncidentFinalizationEntity(id, Json.encodeToString(proof)))
            assertTrue(db.journeyDao().reconcileAuthoritativeTerminalInTransaction(id,
                JourneyStatus.UNRESOLVED_INCIDENT, null, incidentAt, false))
            db.journeyDao().insertIfNoActive(JourneyEntity(autoId, "Offa", at + 3_600_000,
                at, JourneyStatus.ACTIVE, null, 1))
            val automaticAt = finalAt + if (automaticNewer && !tied) 10_000 else 0
            val registeredAt = automaticAt - 1_800_000
            val candidateId = "30000000-0000-4000-8000-000000000201"
            val presentationId = "30000000-0000-4000-8000-000000000202"
            db.probableEndDao().insert(ProbableEndEpisodeEntity(candidateId, autoId, ProbableEndPolicy.VERSION,
                createdAtMillis = registeredAt, generation = 0, evidenceFloorSequence = 0,
                evidenceFloorEventTimeMillis = 0,
                first = ObservationEvidenceReference(1, registeredAt - 240_000, "a".repeat(64)),
                final = ObservationEvidenceReference(5, registeredAt, "b".repeat(64)), observationCount = 5,
                finalDistanceMeters = 10.0, finalAccuracyMeters = 8.0,
                destinationSlot = DestinationEvidenceReference(autoId).slot).also { it.evidence() })
            val presentation = ProbableEndPresentationEntity(presentationId, autoId, candidateId, 0,
                ProbableEndPolicy.VERSION, at, 0, status = "AUTO_END_CLAIMED", liveSlot = null,
                createdAtMillis = registeredAt, presentedAtMillis = registeredAt,
                confirmationDeadlineAtMillis = automaticAt, deadlineStatus = "EXPIRED_PENDING_FINALIZATION",
                deadlineExpiredAtMillis = automaticAt, serverWindowRegisteredAtMillis = registeredAt,
                serverWindowNotBeforeAtMillis = automaticAt, finalizationClaimedAtMillis = automaticAt)
            presentation.validate()
            db.probableEndPresentationDao().insert(presentation)
            assertEquals(1, db.probableEndPresentationDao().recordAutomaticEnd(presentationId, automaticAt))
            assertTrue(db.journeyDao().autoEndExactInTransaction(autoId, automaticAt))
            val autoProof = AutoEndProvenance("AUTO_ENDED", presentationId, "AUTO_ENDED",
                Instant.ofEpochMilli(automaticAt).toString(), Instant.ofEpochMilli(automaticAt).toString(),
                autoId, owner, candidateId, 0, ProbableEndPolicy.VERSION, Instant.ofEpochMilli(at).toString(),
                0, Instant.ofEpochMilli(registeredAt).toString(), Instant.ofEpochMilli(automaticAt).toString())
            autoProof.validate()
            db.incidentReconciliationDao().insertAutoEndProvenance(JourneyAutoEndFinalizationEntity(autoId,
                Json.encodeToString(autoProof)))
            val expectedFirst = if (automaticNewer && !tied) autoId else id
            repeat(2) { index ->
                db.close(); db = open()
                val rows = db.travellerAuthorityDao().observe().first()
                val selected = requireNotNull(selectTravellerTerminalResult(rows))
                assertEquals(if (index == 0) expectedFirst else if (expectedFirst == id) autoId else id,
                    selected.journeyId)
                assertEquals(selected, selectTravellerTerminalResult(rows.reversed()))
                if (selected.unresolved != null) {
                    val result = selected.unresolved
                    assertEquals(1, db.travellerAuthorityDao().acknowledge(result.journeyId,
                        result.provenanceJson, selected.endedAt.toEpochMilli(), finalAt + 20_000))
                } else {
                    assertEquals(1, db.probableEndPresentationDao().acknowledgeAutomaticResult(
                        requireNotNull(selected.automatic).episodeId, finalAt + 20_000))
                }
            }
            db.close(); db = open()
            assertNull(selectTravellerTerminalResult(db.travellerAuthorityDao().observe().first()))
            assertEquals(Json.encodeToString(proof), db.incidentReconciliationDao().provenance(id)?.provenanceJson)
            assertEquals(Json.encodeToString(autoProof), db.incidentReconciliationDao().autoEndProvenance(autoId)?.provenanceJson)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun pendingPauseRetainsSlotAcrossReopenAndNeverBecomesResult() = runBlocking {
        context.deleteDatabase(name)
        var db = open()
        try {
            assertTrue(db.journeyDao().insertIfNoActive(JourneyEntity(id, "Ilorin", at + 3_600_000,
                at, JourneyStatus.ACTIVE, null, 1)))
            val op = db.operationalBoundaryDao().get(id)!!
            assertEquals(1, db.operationalBoundaryDao().pauseExact(id, op.admissionId, op.revision,
                "LOST_OR_INVALID_BOOT_ANCHOR", "INSUFFICIENT_PROOF", null))
            db.close(); db = open()
            val row = db.travellerAuthorityDao().observe().first().single()
            assertTrue(row.isPendingAuthority())
            assertNull(row.unresolvedResult())
            assertEquals(id, db.journeyDao().getActive()?.id)
            assertFalse(db.travellerAuthorityDao().acknowledge(id, "unconfirmed", at, at + 1) == 1)
            assertTrue(pendingAuthorityCopy(row, offline = true).authority.contains("Offline"))
            assertTrue(pendingAuthorityCopy(row).timing.contains("could not be verified"))
            var finalizerCalls = 0
            val actions = TravellerAuthorityActions(db, object : CloudSyncGateway {
                override suspend fun authenticatedOwnerId() = owner
                override suspend fun ownerJourneyState(journeyId: String, ownerId: String) =
                    RemoteJourneyState(id, owner, JourneyStatus.ACTIVE, null, null)
                override suspend fun upsertJourney(journey: Journey, ownerId: String) = Unit
                override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) = Unit
            }, object : IncidentAuthorityGateway {
                override suspend fun eligibility(journeyId: String) = AuthorityRead.Value(
                    IncidentEligibility(UnresolvedEligibility.NOT_ELIGIBLE, UnresolvedReason.BOUNDARY_NOT_REACHED, null))
                override suspend fun finalize(claim: IncidentClaim, beforeDispatch: suspend () -> Boolean):
                    AuthorityRead<IncidentFinalizerResponse> {
                    finalizerCalls++
                    error("Checking status must never finalize")
                }
                override suspend fun provenance(journeyId: String): AuthorityRead<IncidentProvenance> =
                    error("No terminal result")
            }, { false }, { _, _ -> false }, { owner }, {}, {})
            assertEquals("TOO_EARLY", actions.check(id))
            assertEquals(0, finalizerCalls)
            assertTrue(db.travellerAuthorityDao().get(id)!!.isPendingAuthority())
            var disconnected = true
            val presentation = TravellerAuthorityViewModel(actions) { disconnected }
            presentation.markNetworkSettingsOpened()
            disconnected = false
            presentation.refreshConnectivity()
            assertFalse(presentation.state.value.offline)
            assertTrue(presentation.state.value.returnedFromNetworkSettings)
            assertEquals(0, finalizerCalls)
            var retryCalls = 0
            val retry = TravellerAuthorityActions(db, object : CloudSyncGateway {
                override suspend fun authenticatedOwnerId() = owner
                override suspend fun ownerJourneyState(journeyId: String, ownerId: String) = null
                override suspend fun upsertJourney(journey: Journey, ownerId: String) = Unit
                override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) = Unit
            }, object : IncidentAuthorityGateway {
                override suspend fun eligibility(journeyId: String) = error("Not used")
                override suspend fun finalize(claim: IncidentClaim, beforeDispatch: suspend () -> Boolean) =
                    error("Not used")
                override suspend fun provenance(journeyId: String): AuthorityRead<IncidentProvenance> =
                    error("Not used")
            }, { false }, { _, _ -> false }, { owner }, {}, {}, clock = { at + 1 },
                retryReconciliation = { exact ->
                    assertEquals(id, exact)
                    retryCalls++
                    IncidentReconciliationOutcome.TOO_EARLY
                })
            assertEquals("OBSOLETE", retry.retryFailed(id))
            val failed = JourneyTerminalReconciliationEntity(id, lastOutcome = "UNAVAILABLE", retryAtMillis = at)
            db.incidentReconciliationDao().insert(failed)
            assertEquals("TOO_EARLY", retry.retryFailed(id))
            assertEquals(1, retryCalls)
            db.incidentReconciliationDao().update(failed.copy(retryAtMillis = at + 5_000))
            assertEquals("OBSOLETE", retry.retryFailed(id))
            assertEquals(1, retryCalls)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun exactIncidentReceiptAcknowledgesOnceAndSurvivesReopen() = runBlocking {
        context.deleteDatabase(name)
        var db = open()
        try {
            assertTrue(db.journeyDao().insertIfNoActive(JourneyEntity(id, "Ilorin", at + 3_600_000,
                at, JourneyStatus.ACTIVE, null, 1)))
            val boundary = db.unresolvedBoundaryDao().get(id)!!
            val finalAt = Instant.ofEpochMilli(boundary.boundaryMillis + 20_000)
            val episodeAt = Instant.ofEpochMilli(boundary.boundaryMillis + 10_000)
            val proof = IncidentProvenance(IncidentClaim(id, owner, boundary.admissionId,
                boundary.policyVersion, null, Instant.ofEpochMilli(boundary.etaMillis).toString(),
                Instant.ofEpochMilli(boundary.boundaryMillis).toString(),
                "40000000-0000-4000-8000-000000000201", 1, episodeAt.toString(), 1,
                episodeAt.toString()), finalAt.toString(), finalAt.plusSeconds(604_800).toString(),
                "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
            proof.validate()
            val json = Json.encodeToString(proof)
            db.incidentReconciliationDao().insertProvenance(JourneyIncidentFinalizationEntity(id, json))
            assertNull(db.travellerAuthorityDao().get(id)!!.unresolvedResult())
            db.openHelper.writableDatabase.execSQL("""UPDATE journeys SET status='UNRESOLVED_INCIDENT',
                activeSlot=NULL, endedAt=${finalAt.toEpochMilli()} WHERE id='$id'""")
            db.close(); db = open()
            val result = db.travellerAuthorityDao().observe().first().single().unresolvedResult()!!
            assertEquals(finalAt.toString(), result.finalizedAt)
            assertEquals(0, db.travellerAuthorityDao().acknowledge(id, "other receipt", finalAt.toEpochMilli(), at + 1))
            assertEquals(0, db.travellerAuthorityDao().acknowledge(id, json, finalAt.toEpochMilli() + 1, at + 1))
            assertEquals(1, db.travellerAuthorityDao().acknowledge(id, json, finalAt.toEpochMilli(), at + 1))
            assertEquals(0, db.travellerAuthorityDao().acknowledge(id, json, finalAt.toEpochMilli(), at + 2))
            db.close(); db = open()
            assertNull(db.travellerAuthorityDao().get(id)!!.unresolvedResult())
            assertEquals(at + 1, db.incidentReconciliationDao().provenance(id)?.resultAcknowledgedAtMillis)
            assertEquals(json, db.incidentReconciliationDao().provenance(id)?.provenanceJson)
            assertEquals(JourneyStatus.UNRESOLVED_INCIDENT, db.journeyDao().getById(id)?.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
