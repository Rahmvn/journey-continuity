package com.journeycontinuity.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import com.journeycontinuity.app.degraded.*
import com.journeycontinuity.app.sync.SyncScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Named disposable databases only; no production startup, credentials, GPS or hosted calls. */
@RunWith(AndroidJUnit4::class)
class ProbableEndDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "probable-end-slice2-state-test.db"
    private val start = 1_700_000_000_000L
    private var now = start + 60_000
    private lateinit var db: JourneyDatabase
    private fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
    private fun store() = RoomProbableEndStore(db, JourneyClock { now })
    private fun journey(id: String = "test") = Journey(id, "Synthetic destination", start + 3_600_000, start,
        JourneyStatus.ACTIVE, null, structuredDestination = StructuredDestination("NG", "ng-kwara", "Kwara",
            "ng-kwara-offa", "Offa", DestinationKind.POINT, 8.1560008, 4.7227435,
            DestinationSelectionSource.MAP_PIN, adminDatasetVersion = "alabarin-2026-10-05-v2",
            localUnitType = AdminLocalUnitType.LGA, boundaryDatasetId = DESTINATION_BOUNDARY_DATASET,
            boundaryDatasetVersion = "alabarin-2026-10-03-v1"))
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); db = open()
        assertTrue(db.journeyDao().insertIfNoActive(journey().toEntity()))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun reopen() { db.close(); db = open() }
    private fun sample(time: Long = now, distance: Double = 0.0, accuracy: Float = 8f, id: String = "test") =
        TelemetrySample(id, time, 8.1560008 + Math.toDegrees(distance / GeographicDistance.EARTH_RADIUS_METERS),
            4.7227435, accuracy, null, null, ConnectivityState.NONE)
    private suspend fun fix(offset: Long, distance: Double = 0.0, accuracy: Float = 8f) {
        now = start + 60_000 + offset
        db.telemetryDao().insertForActiveJourney(sample(distance = distance, accuracy = accuracy))
        assertEquals(LocalProbableEndRunStatus.PROGRESSED, store().consumePending("test").status)
    }
    private suspend fun candidate(): ProbableEndEpisodeEntity {
        listOf(0L, 60_000, 120_000, 180_000).forEach { fix(it) }
        return db.probableEndDao().episodes("test").single()
    }
    private fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    @Test fun accumulationAndCandidateRestoreExactlyAcrossDatabaseReopens() = runBlocking {
        fix(0)
        assertEquals(1, db.probableEndDao().state("test")!!.restore().proximity!!.count)
        for (index in 1..3) {
            val before = db.probableEndDao().state("test")!!
            reopen(); assertEquals(before, db.probableEndDao().state("test"))
            store().resume("test"); assertEquals(before, db.probableEndDao().state("test"))
            fix(index * 60_000L)
            assertEquals(index + 1, db.probableEndDao().state("test")!!.restore().proximity!!.count)
        }
        val episode = db.probableEndDao().episodes("test").single()
        assertEquals(180_000L, episode.evidence().observedSpanMillis)
        reopen(); store().resume("test")
        assertEquals(episode, db.probableEndDao().episodes("test").single())
    }
    @Test fun duplicatePhysicalFixAndStaleReplayCannotAdvanceOrEraseEvidence() = runBlocking {
        fix(0); fix(60_000); fix(120_000)
        val before = db.probableEndDao().state("test")!!
        reopen() // Replay is evaluated by a newly created store over restored state.
        db.telemetryDao().insertForActiveJourney(sample(time = start + 60_000))
        store().resume("test")
        assertEquals(before.proximity, db.probableEndDao().state("test")!!.proximity)
        assertTrue(db.probableEndDao().state("test")!!.consumedSequence > before.consumedSequence)
        reopen(); fix(180_000)
        assertEquals(4, db.probableEndDao().episodes("test").single().observationCount)
        val candidate = db.probableEndDao().episodes("test").single()
        assertNotEquals(candidate.first.fingerprint, candidate.final.fingerprint)
        reopen()
        db.telemetryDao().insertForActiveJourney(sample()) // same event time under a new row
        store().resume("test")
        assertEquals(4, db.probableEndDao().state("test")!!.proximity!!.count)
        assertEquals(1, db.probableEndDao().episodes("test").size)
        db.telemetryDao().insertForActiveJourney(sample(time = start + 60_000))
        store().resume("test")
        assertEquals(candidate, db.probableEndDao().episodes("test").single())
        assertEquals(4, db.probableEndDao().state("test")!!.proximity!!.count)
    }
    @Test fun gapsAndPoorAccuracyAndOutsideFixResetDurably() = runBlocking {
        fix(0); fix(90_001)
        reopen(); assertEquals(1, db.probableEndDao().state("test")!!.proximity!!.count)
        fix(120_000, accuracy = 51f)
        reopen(); assertNull(db.probableEndDao().state("test")!!.proximity)
        fix(180_000); fix(240_000, distance = 100.0)
        reopen(); assertNull(db.probableEndDao().state("test")!!.proximity)
        assertTrue(db.probableEndDao().episodes("test").isEmpty())
    }
    @Test fun reliableDepartureInvalidatesDurablyAndFreshGenerationCanCreateAnotherCandidate() = runBlocking {
        val old = candidate()
        val before = db.probableEndDao().state("test")!!
        assertEquals(LocalProbableEndRunStatus.IDLE, store().suppressCandidate("test", "wrong-episode", now).status)
        assertEquals(before, db.probableEndDao().state("test"))
        fix(240_000, distance = 200.0)
        reopen(); assertEquals(1, db.probableEndDao().state("test")!!.exit!!.count)
        fix(300_000, distance = 200.0)
        reopen()
        assertEquals("INVALIDATED", db.probableEndDao().episodes("test").single().lifecycle)
        assertEquals(6L, db.probableEndDao().state("test")!!.evidenceFloorSequence)
        // Copied observations after the boundary must not revive the old episode.
        listOf(0L, 60_000, 120_000, 180_000).forEach {
            db.telemetryDao().insertForActiveJourney(sample(start + 60_000 + it))
        }
        store().resume("test")
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
        listOf(360_000L, 420_000, 480_000, 540_000).forEach { fix(it) }
        val episodes = db.probableEndDao().episodes("test")
        assertEquals(2, episodes.size); assertNotEquals(old.id, episodes.last().id)
        assertEquals("CANDIDATE", episodes.last().lifecycle)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        assertEquals(old.evidence(), episodes.first().evidence())
    }
    @Test fun suppressionPrimitivePreservesFloorAndRequiresExitBeforeNewReturnDwell() = runBlocking {
        val old = candidate()
        assertEquals(LocalProbableEndRunStatus.PROGRESSED, store().suppressCandidate("test", old.id, now).status)
        reopen()
        listOf(240_000L, 300_000, 360_000, 420_000).forEach { fix(it) }
        assertEquals(1, db.probableEndDao().episodes("test").size)
        assertEquals("SUPPRESSED", db.probableEndDao().episodes("test").single().lifecycle)
        fix(480_000, distance = 200.0); reopen(); fix(540_000, distance = 200.0)
        listOf(600_000L, 660_000, 720_000, 780_000).forEach { fix(it) }
        assertEquals(2, db.probableEndDao().episodes("test").size)
        assertNotEquals(old.id, db.probableEndDao().episodes("test").last().id)
    }
    @Test fun telemetryCommittedBeforeInferenceIsRecoveredExactlyOnce() = runBlocking {
        db.telemetryDao().insertForActiveJourney(sample())
        assertNull(db.probableEndDao().state("test"))
        reopen(); store().resumeActive()
        val state = db.probableEndDao().state("test")!!
        assertEquals(1, state.proximity!!.count)
        store().resumeActive(); assertEquals(state, db.probableEndDao().state("test"))
    }
    @Test fun failedEpisodeInsertRollsBackStateButPreservesTelemetryAndRetryConverges() = runBlocking {
        fix(0); fix(60_000); fix(120_000)
        val before = db.probableEndDao().state("test")!!
        sql("CREATE TRIGGER fail_candidate BEFORE INSERT ON probable_end_episodes BEGIN SELECT RAISE(ABORT, 'synthetic fault'); END")
        now += 60_000; db.telemetryDao().insertForActiveJourney(sample())
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        assertEquals(before, db.probableEndDao().state("test"))
        assertTrue(db.probableEndDao().episodes("test").isEmpty())
        assertEquals(4L, db.telemetryDao().getLatest("test")!!.sequence)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        reopen(); sql("DROP TRIGGER fail_candidate"); store().resume("test"); store().resume("test")
        assertEquals(1, db.probableEndDao().episodes("test").size)
        assertEquals(4, db.probableEndDao().state("test")!!.proximity!!.count)
    }
    @Test fun failedStateWriteRollsBackCandidateInsertToo() = runBlocking {
        fix(0); fix(60_000); fix(120_000)
        val before = db.probableEndDao().state("test")!!
        sql("CREATE TRIGGER fail_state BEFORE UPDATE ON probable_end_detector_states BEGIN SELECT RAISE(ABORT, 'synthetic fault'); END")
        now += 60_000; db.telemetryDao().insertForActiveJourney(sample())
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        assertEquals(before, db.probableEndDao().state("test"))
        assertTrue(db.probableEndDao().episodes("test").isEmpty())
        assertEquals(4L, db.telemetryDao().getLatest("test")!!.sequence)
        sql("DROP TRIGGER fail_state"); reopen(); store().resume("test")
        assertEquals(1, db.probableEndDao().episodes("test").size)
    }
    @Test fun concurrentConsumersCreateExactlyOneCandidate() = runBlocking {
        fix(0); fix(60_000); fix(120_000)
        now += 60_000; db.telemetryDao().insertForActiveJourney(sample())
        val other = open()
        try {
            coroutineScope { (0..7).map { index -> async(Dispatchers.IO) {
                RoomProbableEndStore(if (index % 2 == 0) db else other, JourneyClock { now }).consumePending("test")
            } }.awaitAll() }
            assertEquals(1, db.probableEndDao().episodes("test").size)
            assertEquals(4, db.probableEndDao().state("test")!!.proximity!!.count)
        } finally { other.close() }
    }
    @Test fun corruptStateAndCandidateFailClosedWithNoJourneyMutation() = runBlocking {
        candidate()
        sql("UPDATE probable_end_detector_states SET proximity_count = -1 WHERE journeyId = 'test'")
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        assertEquals("INVALIDATED", db.probableEndDao().episodes("test").single().lifecycle)
        assertNull(db.probableEndDao().state("test")!!.proximity)
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
        db.telemetryDao().insertForActiveJourney(sample())
        assertNotNull(db.telemetryDao().getLatest("test"))
    }
    @Test fun corruptCandidateIsQuarantinedAndCannotChangeJourneyStatus() = runBlocking {
        candidate()
        sql("UPDATE probable_end_episodes SET observationCount = -1 WHERE journeyId = 'test'")
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
        assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()!!.status)
    }
    @Test fun lostOrPartiallyNullStateDoesNotResurrectSuppressedEvidence() = runBlocking {
        val old = candidate(); store().suppressCandidate("test", old.id, now)
        sql("DELETE FROM probable_end_detector_states WHERE journeyId = 'test'")
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        assertTrue(db.probableEndDao().state("test")!!.requiresReliableExit)
        fix(240_000)
        sql("UPDATE probable_end_detector_states SET proximity_firstFingerprint = NULL WHERE journeyId = 'test'")
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        assertTrue(db.probableEndDao().state("test")!!.requiresReliableExit)
        assertEquals(1, db.probableEndDao().episodes("test").size)
    }
    @Test fun clockRegressionResetsAtTailWithoutManufacturingDwell() = runBlocking {
        fix(0); fix(60_000)
        now -= 10_000; reopen()
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        val row = db.probableEndDao().state("test")!!
        assertNull(row.proximity); assertEquals(2L, row.evidenceFloorSequence)
        assertTrue(row.evidenceFloorEventTimeMillis >= start + 120_000)
        assertTrue(db.probableEndDao().episodes("test").isEmpty())
    }
    @Test fun staleUnconsumedFinalFixCannotCreateCandidateAfterRestart() = runBlocking {
        fix(0); fix(60_000); fix(120_000)
        db.telemetryDao().insertForActiveJourney(sample(time = start + 240_000))
        now = start + 330_001; reopen(); store().resume("test")
        assertNull(db.probableEndDao().state("test")!!.proximity)
        assertTrue(db.probableEndDao().episodes("test").isEmpty())
    }
    @Test fun journeyStatesAndTerminalRaceRemainIsolated() = runBlocking {
        val old = candidate()
        db.journeyDao().completeActive(now)
        store().resume("test")
        assertEquals("INVALIDATED", db.probableEndDao().episodes("test").single().lifecycle)
        assertEquals(old.evidence(), db.probableEndDao().episodes("test").single().evidence())
        assertTrue(db.journeyDao().insertIfNoActive(journey("other").toEntity()))
        db.telemetryDao().insertForActiveJourney(sample(id = "other")); store().resume("other")
        assertEquals(1, db.probableEndDao().state("other")!!.proximity!!.count)
        assertTrue(db.probableEndDao().episodes("other").isEmpty())
    }
    private fun repository(observer: suspend (String) -> Unit): RoomJourneyRepository {
        val degradation = DegradedConnectivityCoordinator(object : DegradedConnectivityStateStore {
            override suspend fun updateAtomically(journeyId: String,
                transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?) = null
            override fun observe(journeyId: String): Flow<DegradedConnectivityState?> = flowOf(null)
        }, LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig()))
        return RoomJourneyRepository(db.journeyDao(), db.telemetryDao(), db.syncStateDao(), db.heartbeatDao(),
            SyncScheduler {}, degradation, probableEndObserver = observer)
    }
    @Test fun secondaryExceptionCannotDropTelemetryOrBreakActiveStream() = runBlocking {
        val repository = repository { throw IllegalStateException("synthetic inference failure") }
        assertNotNull(repository.recordTelemetry(sample()))
        assertEquals(JourneyStatus.ACTIVE, repository.activeJourney.first()!!.status)
        assertEquals(1L, db.telemetryDao().getLatest("test")!!.sequence)
        assertTrue(db.syncStateDao().get("test")!!.workRequested)
    }
    @Test fun corruptDestinationOnlyDisablesInferenceAndMonitoringStillStoresEvidence() = runBlocking {
        sql("UPDATE journeys SET destinationLongitude = NULL WHERE id = 'test'")
        val repository = repository { store().resume(it) }
        assertEquals(LocalProbableEndRunStatus.INELIGIBLE, store().consumePending("test").status)
        // The service uses this raw stream; shared domain consumers must still see strict failure.
        val monitoring = db.journeyDao().observeActive().first()!!
        assertEquals(JourneyStatus.ACTIVE, monitoring.status)
        assertEquals("POINT", monitoring.destinationKind)
        try { repository.activeJourney.first(); fail("Shared stream must remain strict") }
        catch (_: IllegalArgumentException) { }
        assertNotNull(repository.recordTelemetry(sample()))
        assertEquals(1L, db.telemetryDao().getLatest("test")!!.sequence)
        assertTrue(db.probableEndDao().episodes("test").isEmpty())
        assertNull(db.journeyDao().getActive()!!.destinationLongitude)
    }

    @Test fun recoveryQuarantinesOnlyExistingPrefixAndCannotPoisonFutureEvidence() = runBlocking {
        candidate()
        // One never-evaluated valid row and one rejected far-future row exist at quarantine.
        now += 60_000
        db.telemetryDao().insertForActiveJourney(sample())
        db.telemetryDao().insertForActiveJourney(sample(time = Long.MAX_VALUE))
        sql("UPDATE probable_end_detector_states SET proximity_count = -1, evidenceFloorEventTimeMillis = 9223372036854775807")
        reopen()
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        val recovered = db.probableEndDao().state("test")!!
        assertEquals(6L, recovered.consumedSequence)
        assertEquals(6L, recovered.evidenceFloorSequence)
        assertEquals(now, recovered.evidenceFloorEventTimeMillis)
        assertTrue(recovered.requiresReliableExit)
        assertEquals("INVALIDATED", db.probableEndDao().episodes("test").single().lifecycle)
        fix(300_000, distance = 200.0); reopen(); fix(360_000, distance = 200.0)
        listOf(420_000L, 480_000, 540_000, 600_000).forEach { fix(it) }
        assertEquals(2, db.probableEndDao().episodes("test").size)
        assertEquals("CANDIDATE", db.probableEndDao().episodes("test").last().lifecycle)
        assertEquals(Long.MAX_VALUE, db.telemetryDao().getBySequence("test", 6)!!.eventTime)
    }

    @Test fun suppressionIgnoresRejectedFutureTimesAndTerminalOrWrongJourneyCallbacks() = runBlocking {
        val episode = candidate()
        db.telemetryDao().insertForActiveJourney(sample(time = Long.MAX_VALUE))
        assertEquals(LocalProbableEndRunStatus.INELIGIBLE,
            store().suppressCandidate("missing", episode.id, now).status)
        assertEquals(LocalProbableEndRunStatus.PROGRESSED, store().suppressCandidate("test", episode.id, now).status)
        assertEquals(now, db.probableEndDao().state("test")!!.evidenceFloorEventTimeMillis)
        fix(240_000, distance = 200.0); fix(300_000, distance = 200.0)
        listOf(360_000L, 420_000, 480_000, 540_000).forEach { fix(it) }
        val next = db.probableEndDao().episodes("test").last()
        db.journeyDao().stopMonitoringActive("test", now)
        assertEquals(LocalProbableEndRunStatus.INELIGIBLE, store().suppressCandidate("test", next.id, now).status)
        store().resume("test")
        assertEquals(JourneyStatus.CANCELLED, db.journeyDao().getById("test")!!.status)
        assertEquals("INVALIDATED", db.probableEndDao().episodes("test").last().lifecycle)
    }

    @Test fun oversizedCursorCannotSilentlyDisableAllFutureEvidence() = runBlocking {
        candidate()
        sql("UPDATE probable_end_detector_states SET consumedSequence = 9223372036854775807")
        reopen()
        assertEquals(LocalProbableEndRunStatus.UNAVAILABLE, store().consumePending("test").status)
        assertEquals(4L, db.probableEndDao().state("test")!!.consumedSequence)
        fix(240_000, distance = 200.0); fix(300_000, distance = 200.0)
        listOf(360_000L, 420_000, 480_000, 540_000).forEach { fix(it) }
        assertEquals(2, db.probableEndDao().episodes("test").size)
        assertEquals("CANDIDATE", db.probableEndDao().episodes("test").last().lifecycle)
    }

    @Test fun recoveryBatchesResumeAtExactCursorAndFinishPartialBatch() = runBlocking {
        repeat(128) { db.telemetryDao().insertForActiveJourney(sample(time = 0)) }
        db.telemetryDao().insertForActiveJourney(sample())
        val first = store().consumePending("test")
        assertEquals(128, first.processed); assertTrue(first.hasMore)
        assertEquals(128L, db.probableEndDao().state("test")!!.consumedSequence)
        reopen()
        val partial = store().consumePending("test")
        assertEquals(1, partial.processed); assertFalse(partial.hasMore)
        assertEquals(129L, db.probableEndDao().state("test")!!.consumedSequence)
        assertEquals(1, db.probableEndDao().state("test")!!.proximity!!.count)
        listOf(60_000L, 120_000, 180_000).forEach { fix(it) }
        assertEquals(1, db.probableEndDao().episodes("test").size)
    }

    @Test fun candidateCreationAndDepartureInsideSameBatchAreAtomic() = runBlocking {
        fix(0); fix(60_000); fix(120_000)
        db.telemetryDao().insertForActiveJourney(sample(time = start + 240_000))
        db.telemetryDao().insertForActiveJourney(sample(time = start + 300_000, distance = 200.0))
        db.telemetryDao().insertForActiveJourney(sample(time = start + 360_000, distance = 200.0))
        now = start + 330_000 // All new observations pass the exact age/skew boundaries.
        assertEquals(3, store().consumePending("test").processed)
        reopen()
        assertEquals("INVALIDATED", db.probableEndDao().episodes("test").single().lifecycle)
        assertEquals(4, db.probableEndDao().episodes("test").single().observationCount)
        assertEquals(6L, db.probableEndDao().state("test")!!.evidenceFloorSequence)
        assertTrue(db.probableEndDao().unclosed("test").isEmpty())
    }
    @Test fun episodeUniquenessAndForeignKeysAreEnforcedWithoutCascadeDeletion() = runBlocking {
        val episode = candidate()
        suspend fun rejects(block: suspend () -> Unit) {
            try { block(); fail("Constraint should reject") } catch (_: android.database.SQLException) { }
        }
        rejects { db.probableEndDao().insert(episode.copy(id = "duplicate-generation", liveSlot = null)) }
        rejects { db.probableEndDao().insert(episode.copy(id = "duplicate-live", generation = 1)) }
        rejects { db.probableEndDao().save(db.probableEndDao().state("test")!!.copy(journeyId = "missing")) }
        rejects { sql("DELETE FROM journeys WHERE id = 'test'") }
        assertEquals(episode, db.probableEndDao().episodes("test").single())
    }
}
