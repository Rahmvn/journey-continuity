package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.RoomUnresolvedBoundaryStore
import com.journeycontinuity.app.data.unresolved.UnresolvedBoundaryGateway
import com.journeycontinuity.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnresolvedBoundaryDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "unresolved-boundary-foundation.db"
    private val id = "20000000-0000-4000-8000-000000000010"
    private val owner = "10000000-0000-4000-8000-000000000010"
    private val start = 1_790_000_000_000L
    private var receipt = BoundaryReceipt(1000, 1, "isolated-install")
    private lateinit var db: JourneyDatabase
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); db = open()
        assertTrue(db.journeyDao().insertIfNoActive(Journey(id, "Synthetic destination", start + 1000,
            start, JourneyStatus.ACTIVE, null).toEntity()))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun reopen() { db.close(); db = open() }
    private suspend fun observation(at: Long, decision: UnresolvedEligibility = UnresolvedEligibility.NOT_ELIGIBLE,
        reason: UnresolvedReason = UnresolvedReason.NOT_VERIFYING): UnresolvedObservation {
        val a = db.unresolvedBoundaryDao().get(id)!!.admission()
        return UnresolvedObservation(id, owner, a.admissionId, a.policyVersion, a.admittedAtMillis,
            a.etaMillis, a.boundaryMillis, start, at, decision, reason, null, null, null, 0, start)
    }
    private fun store(value: UnresolvedObservation?) = RoomUnresolvedBoundaryStore(db,
        UnresolvedBoundaryGateway { value }, { receipt })

    @Test fun admissionAndSameBootReopenPreserveExactBoundaryAndIdentity() = runBlocking {
        val before = db.unresolvedBoundaryDao().get(id)!!
        assertEquals(start + 1000 + 86_400_000, before.boundaryMillis)
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF, before.proof())
        assertFalse(before.hasSameBootReceipt(receipt))
        assertNull(before.eligibility)
        reopen()
        assertEquals(before, db.unresolvedBoundaryDao().get(id))
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
    }
    @Test fun earlierSampleAndArbitraryElapsedAdvanceNeverProveBoundary() = runBlocking {
        val row = db.unresolvedBoundaryDao().get(id)!!
        assertTrue(store(observation(row.boundaryMillis - 1)).refresh(id))
        receipt = BoundaryReceipt(Long.MAX_VALUE, 1, "isolated-install")
        reopen()
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF, db.unresolvedBoundaryDao().get(id)!!.proof())
        assertTrue(db.unresolvedBoundaryDao().get(id)!!.hasSameBootReceipt(receipt))
        assertNull(db.unresolvedBoundaryDao().get(id)!!.utcUncertaintyMillis)
        assertEquals(1, db.journeyDao().getActive()!!.activeSlot)
    }
    @Test fun establishedHistoricalProofSurvivesRebootAndLaterResolvedRead() = runBlocking {
        val boundary = db.unresolvedBoundaryDao().get(id)!!.boundaryMillis
        assertTrue(store(observation(boundary)).refresh(id))
        val first = db.unresolvedBoundaryDao().get(id)!!.firstBoundaryProofAtMillis
        receipt = BoundaryReceipt(10, 2, "isolated-install") // simulated reboot, not actual OS reboot.
        reopen()
        assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF, db.unresolvedBoundaryDao().get(id)!!.proof())
        assertFalse(db.unresolvedBoundaryDao().get(id)!!.hasSameBootReceipt(receipt))
        assertTrue(store(observation(boundary + 1)).refresh(id))
        assertEquals(first, db.unresolvedBoundaryDao().get(id)!!.firstBoundaryProofAtMillis)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertNull(db.journeyDao().getActive()!!.endedAt)
    }
    @Test fun unavailableAndWrongIdentityCannotCreateProof() = runBlocking {
        val original = db.unresolvedBoundaryDao().get(id)!!
        assertFalse(store(null).refresh(id))
        assertFalse(store(observation(original.boundaryMillis).copy(admissionId = "wrong")).refresh(id))
        assertFalse(store(observation(original.boundaryMillis).copy(journeyId = "another")).refresh(id))
        assertEquals(original, db.unresolvedBoundaryDao().get(id))
    }
    @Test fun episodeResolutionSupersedesObservationNotHistoricalTimeProof() = runBlocking {
        val boundary = db.unresolvedBoundaryDao().get(id)!!.boundaryMillis
        val eligible = observation(boundary, UnresolvedEligibility.ELIGIBLE, UnresolvedReason.CURRENT_UNRESOLVED)
            .copy(verificationCaseId = "40000000-0000-4000-8000-000000000010", openingEventId = 1,
                verifyingStartedAtMillis = boundary - 1000, guardVersion = 1)
        assertTrue(store(eligible).refresh(id))
        assertTrue(store(observation(boundary + 1)).refresh(id))
        val resolved = db.unresolvedBoundaryDao().get(id)!!
        assertNull(resolved.verificationCaseId)
        assertEquals("NOT_ELIGIBLE", resolved.eligibility)
        assertEquals(BoundaryProof.BOUNDARY_REACHED_WITH_PROOF, resolved.proof())
    }
    @Test fun lateResponseCannotOverwriteNewerExactRevision() = runBlocking {
        val old = observation(start + 1000)
        val newer = observation(start + 2000)
        val delayed = RoomUnresolvedBoundaryStore(db, UnresolvedBoundaryGateway {
            assertTrue(store(newer).refresh(id)); old
        }, { receipt })
        assertFalse(delayed.refresh(id))
        assertEquals(start + 2000, db.unresolvedBoundaryDao().get(id)!!.serverObservedAtMillis)
    }
    @Test fun oversizeAdmissionRollsBackEveryRelatedWrite() = runBlocking {
        assertNotNull(db.journeyDao().completeActive(start + 1))
        val other = "20000000-0000-4000-8000-000000000011"
        assertTrue(runCatching { db.journeyDao().insertIfNoActive(Journey(other, "Synthetic",
            start + 259_200_001, start, JourneyStatus.ACTIVE, null).toEntity()) }.isFailure)
        assertNull(db.journeyDao().getById(other))
        assertNull(db.unresolvedBoundaryDao().get(other))
        assertNull(db.journeyDao().getActive())
    }
}
