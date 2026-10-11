package com.journeycontinuity.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.journeycontinuity.app.BoundaryAcceptanceApplication
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.auth.TravellerSessionState
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.JourneyTerminalReconciliationEntity
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.CloudSyncGateway
import com.journeycontinuity.app.sync.SyncFailureKind
import com.journeycontinuity.app.sync.RemoteJourneyState
import com.journeycontinuity.app.auth.ActiveJourneyStopRemote
import com.journeycontinuity.app.auth.MonitoringStopAcknowledgement
import com.journeycontinuity.app.data.unresolved.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Credential-free isolated UID; exercises the production Application and CoroutineWorker entry points. */
@RunWith(AndroidJUnit4::class)
class AuthorityRetryRuntimeAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as BoundaryAcceptanceApplication
    private val id = "20000000-0000-4000-8000-0000000000b1"
    private val owner = "10000000-0000-4000-8000-0000000000b1"

    @Before fun setup() {
        check(context.packageName == "com.journeycontinuity.app.boundaryacceptance")
        check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty())
        app.interceptAuthorityWakes = true
        app.authorityWakeCount = 0
        app.authorityWorkerCalls = 0
        app.authorityWorkerResult = null
        app.authorityWorkerFailure = null
        app.authorityWorkerBeforeResult = {}
        app.overrideAuthorityGate = false
    }

    @After fun cleanup() {
        app.authorityWorkerResult = null
        app.authorityWorkerFailure = null
        app.authorityWorkerBeforeResult = {}
        app.overrideAuthorityGate = false
        app.interceptAuthorityWakes = false
    }

    private fun worker(token: String) = TestListenableWorkerBuilder<AuthorityRetryWorker>(context)
        .setInputData(Data.Builder().putString(WorkManagerAuthorityRetryScheduler.JOURNEY_ID, id)
            .putString(WorkManagerAuthorityRetryScheduler.RETRY_AT_MILLIS, token).build())
        .build()

    private suspend fun seedPaused(db: JourneyDatabase, retryAt: Long) {
        db.clearAllTables()
        val now = System.currentTimeMillis()
        assertTrue(db.journeyDao().insertIfNoActive(
            Journey(id, "Isolated retry fixture", now + 3_600_000, now,
                JourneyStatus.ACTIVE, null).toEntity(), BoundaryReceipt(1_000, 4, "isolated")))
        val boundary = requireNotNull(db.unresolvedBoundaryDao().get(id))
        val operational = requireNotNull(db.operationalBoundaryDao().get(id))
        assertEquals(1, db.operationalBoundaryDao().pauseExact(id, boundary.admissionId,
            operational.revision, "APPROXIMATE_MONOTONIC_CAP", "INSUFFICIENT_PROOF", 2_000))
        db.incidentReconciliationDao().insert(JourneyTerminalReconciliationEntity(id,
            lastOutcome = "UNAVAILABLE", retryAtMillis = retryAt))
    }

    private fun activeAuthorityWork(manager: WorkManager) =
        manager.getWorkInfosByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
            .get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }

    private suspend fun awaitActiveCount(manager: WorkManager, count: Int) {
        withTimeout(5_000) {
            while (activeAuthorityWork(manager).size != count) delay(50)
        }
    }

    @Test fun obsoleteWakeFinishesAndSuccessiveDeadlinesKeepOneActiveJob() = runBlocking {
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        val manager = WorkManager.getInstance(context)
        val scheduler = WorkManagerAuthorityRetryScheduler(context, db, manager)
        var now = System.currentTimeMillis() + 120_000
        try {
            seedPaused(db, now)
            scheduler.schedule(id, now)
            awaitActiveCount(manager, 1)
            val first = activeAuthorityWork(manager).single().id
            val oldToken = WorkManagerAuthorityRetryScheduler.deadlineToken(now)
            val unavailableCloud = object : CloudSyncGateway {
                override suspend fun authenticatedOwnerId(): String =
                    throw CloudSyncException(SyncFailureKind.TRANSIENT, "Simulated offline authority")
                override suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState? = null
                override suspend fun upsertJourney(journey: Journey, ownerId: String) = Unit
                override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) = Unit
            }
            val unusedAuthority = object : IncidentAuthorityGateway {
                override suspend fun eligibility(journeyId: String): AuthorityRead<IncidentEligibility> = error("unused")
                override suspend fun finalize(claim: IncidentClaim, beforeDispatch: suspend () -> Boolean):
                    AuthorityRead<IncidentFinalizerResponse> = error("unused")
                override suspend fun provenance(journeyId: String): AuthorityRead<IncidentProvenance> = error("unused")
            }
            val unusedStop = object : ActiveJourneyStopRemote {
                override suspend fun stop(journeyId: String, ownerId: String): MonitoringStopAcknowledgement = error("unused")
                override suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState? = error("unused")
            }
            val reconciler = RoomIncidentReconciliationCoordinator(db, unusedAuthority, unavailableCloud,
                unusedStop, clock = JourneyClock { now }, requestRetry = scheduler::schedule,
                localOwner = { owner })
            // Foreground wins at T1. The real coordinator persists T2 and enqueues B.
            reconciler.advancePending()
            val secondDeadline = requireNotNull(db.incidentReconciliationDao().get(id)?.retryAtMillis)
            assertTrue(secondDeadline > now)
            awaitActiveCount(manager, 1)
            assertNotEquals(first, activeAuthorityWork(manager).single().id)
            assertEquals(AuthorityWakeResult.OBSOLETE_WORK,
                app.reconcilePendingAuthorityNow(id, oldToken))
            assertEquals(ListenableWorker.Result.success().javaClass, worker(oldToken).doWork().javaClass)
            assertEquals(1, activeAuthorityWork(manager).size)
            assertTrue(app.scheduledAuthorityWakeStillCurrent(id,
                WorkManagerAuthorityRetryScheduler.deadlineToken(secondDeadline)))

            // Repeated failures produce fresh deadlines without retaining A or B.
            repeat(3) {
                now = requireNotNull(db.incidentReconciliationDao().get(id)?.retryAtMillis)
                reconciler.advancePending()
                awaitActiveCount(manager, 1)
                val current = requireNotNull(db.incidentReconciliationDao().get(id)?.retryAtMillis)
                assertEquals(WorkManagerAuthorityRetryScheduler.deadlineToken(current),
                    activeAuthorityWork(manager).single().tags.first {
                        it.startsWith("journey-authority-deadline-")
                    }.removePrefix("journey-authority-deadline-"))
                val both = listOf(async { scheduler.schedule(id, current) },
                    async { scheduler.schedule(id, current) })
                both.forEach { it.await() }
                assertEquals(1, activeAuthorityWork(manager).size)
            }
            val reopened = Room.databaseBuilder(context, JourneyDatabase::class.java,
                "journey-continuity.db").build()
            try {
                val current = requireNotNull(reopened.incidentReconciliationDao().get(id)?.retryAtMillis)
                WorkManagerAuthorityRetryScheduler(context, reopened, manager).schedule(id, current)
                assertEquals(1, activeAuthorityWork(manager).size)
            } finally { reopened.close() }
        } finally {
            manager.cancelAllWorkByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
                .result.get(10, TimeUnit.SECONDS)
            db.clearAllTables(); db.close()
        }
    }

    @Test fun runningOldWakeCannotRetryOrCancelItsSuccessor() = runBlocking {
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        val manager = WorkManager.getInstance(context)
        val scheduler = WorkManagerAuthorityRetryScheduler(context, db, manager)
        val firstDeadline = System.currentTimeMillis() + 120_000
        val secondDeadline = firstDeadline + 300_000
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            seedPaused(db, firstDeadline)
            scheduler.schedule(id, firstDeadline)
            app.authorityWorkerResult = AuthorityWakeResult.BEFORE_DUE
            app.authorityWorkerBeforeResult = { entered.complete(Unit); release.await() }
            val runningOld = async(start = CoroutineStart.UNDISPATCHED) {
                worker(WorkManagerAuthorityRetryScheduler.deadlineToken(firstDeadline)).doWork()
            }
            withTimeout(5_000) { entered.await() }
            db.incidentReconciliationDao().update(requireNotNull(db.incidentReconciliationDao().get(id))
                .copy(retryAtMillis = secondDeadline))
            scheduler.schedule(id, secondDeadline)
            awaitActiveCount(manager, 1)
            val successor = activeAuthorityWork(manager).single().id
            release.complete(Unit)
            assertEquals(ListenableWorker.Result.success().javaClass, runningOld.await().javaClass)
            assertEquals(successor, activeAuthorityWork(manager).single().id)
        } finally {
            release.complete(Unit)
            manager.cancelAllWorkByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
                .result.get(10, TimeUnit.SECONDS)
            db.clearAllTables(); db.close()
        }
    }

    @Test fun competingTerminalRetiresAllScheduledWakesWithoutChangingItsDurableState() = runBlocking {
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        val manager = WorkManager.getInstance(context)
        val scheduler = WorkManagerAuthorityRetryScheduler(context, db, manager)
        val retryAt = System.currentTimeMillis() + 120_000
        try {
            seedPaused(db, retryAt)
            scheduler.schedule(id, retryAt)
            awaitActiveCount(manager, 1)
            db.incidentReconciliationDao().update(requireNotNull(db.incidentReconciliationDao().get(id))
                .copy(lastOutcome = "COMPETING_TERMINAL", retryAtMillis = retryAt + 300_000))
            // An application observer emission calls this scheduler cleanup.
            scheduler.retireObsolete(id)
            awaitActiveCount(manager, 0)
            assertEquals("COMPETING_TERMINAL", db.incidentReconciliationDao().get(id)?.lastOutcome)
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()?.status)
            assertEquals(ListenableWorker.Result.success().javaClass,
                worker(WorkManagerAuthorityRetryScheduler.deadlineToken(retryAt)).doWork().javaClass)
            assertEquals(0, activeAuthorityWork(manager).size)

            // A later retryable event may own fresh work; simulate the terminal Room
            // state verified by the coordinator tests and retire its wake.
            val later = retryAt + 600_000
            db.incidentReconciliationDao().update(requireNotNull(db.incidentReconciliationDao().get(id))
                .copy(lastOutcome = "UNAVAILABLE", retryAtMillis = later))
            scheduler.schedule(id, later)
            awaitActiveCount(manager, 1)
            assertTrue(db.journeyDao().reconcileAuthoritativeTerminalInTransaction(id,
                JourneyStatus.CANCELLED, null, System.currentTimeMillis(), false))
            scheduler.retireObsolete(id)
            awaitActiveCount(manager, 0)
            assertTrue(db.incidentReconciliationDao().pendingAuthority().isEmpty())
        } finally {
            manager.cancelAllWorkByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
                .result.get(10, TimeUnit.SECONDS)
            db.clearAllTables(); db.close()
        }
    }

    @Test fun uniqueWorkSchedulerKeepsOneWakeAndCancelsIt() = runBlocking {
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        val journeyId = id
        val retryAt = System.currentTimeMillis() + 120_000
        val name = "${WorkManagerAuthorityRetryScheduler.journeyTag(journeyId)}-$retryAt"
        val manager = WorkManager.getInstance(context)
        try {
            seedPaused(db, retryAt)
            val scheduler = WorkManagerAuthorityRetryScheduler(context, db, manager)
            scheduler.schedule(journeyId, retryAt)
            scheduler.schedule(journeyId, retryAt)
            assertEquals(1, manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                .count { !it.state.isFinished })
        } finally {
            manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
            db.clearAllTables(); db.close()
        }
    }

    @Test fun realWorkerRetainsEarlyAndOfflineWakeAndDoesNotSwallowCancellation() = runBlocking {
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        val retryAt = System.currentTimeMillis() + 120_000
        val token = WorkManagerAuthorityRetryScheduler.deadlineToken(retryAt)
        try {
        seedPaused(db, retryAt)
        app.authorityWorkerResult = AuthorityWakeResult.BEFORE_DUE
        assertTrue(worker(token).applicationContext.javaClass.name,
            worker(token).applicationContext.applicationContext is BoundaryAcceptanceApplication)
        assertEquals(AuthorityWakeResult.BEFORE_DUE, app.reconcilePendingAuthorityNow(id))
        assertEquals(ListenableWorker.Result.retry().javaClass, worker(token).doWork().javaClass)
        assertEquals(2, app.authorityWorkerCalls)
        app.authorityWorkerResult = AuthorityWakeResult.OFFLINE
        assertEquals(ListenableWorker.Result.retry().javaClass, worker(token).doWork().javaClass)
        app.authorityWorkerResult = AuthorityWakeResult.AUTHENTICATION_REQUIRED
        assertEquals(ListenableWorker.Result.success().javaClass, worker(token).doWork().javaClass)
        app.authorityWorkerResult = AuthorityWakeResult.ATTEMPTED
        assertEquals(ListenableWorker.Result.success().javaClass, worker(token).doWork().javaClass)
        app.authorityWorkerResult = AuthorityWakeResult.NO_PENDING
        assertEquals(ListenableWorker.Result.success().javaClass, worker(token).doWork().javaClass)
        assertEquals(6, app.authorityWorkerCalls)
        app.authorityWorkerFailure = CancellationException("worker interrupted")
        try { worker(token).doWork(); fail("Cancellation must escape") }
        catch (_: CancellationException) { }
        app.authorityWorkerFailure = java.io.IOException("Transient worker read failure")
        assertEquals(ListenableWorker.Result.retry().javaClass, worker(token).doWork().javaClass)
        } finally { db.clearAllTables(); db.close() }
    }

    @Test fun applicationGateKeepsOwnerAndDeadlineBeforeAnyRemoteAttempt() = runBlocking {
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        try {
            db.clearAllTables()
            val now = System.currentTimeMillis()
            assertTrue(db.journeyDao().insertIfNoActive(
                Journey(id, "Isolated retry fixture", now + 3_600_000, now,
                    JourneyStatus.ACTIVE, null).toEntity(), BoundaryReceipt(1_000, 4, "isolated")))
            val boundary = requireNotNull(db.unresolvedBoundaryDao().get(id))
            val operational = requireNotNull(db.operationalBoundaryDao().get(id))
            assertEquals(1, db.operationalBoundaryDao().pauseExact(id, boundary.admissionId,
                operational.revision, "APPROXIMATE_MONOTONIC_CAP", "INSUFFICIENT_PROOF", 2_000))
            db.incidentReconciliationDao().insert(JourneyTerminalReconciliationEntity(id,
                lastOutcome = "UNAVAILABLE", retryAtMillis = now + 120_000))
            app.overrideAuthorityGate = true
            app.testInternetUsable = true
            app.testBoundOwner = owner
            app.testSessionOwner = owner
            assertEquals(AuthorityWakeResult.BEFORE_DUE, app.reconcilePendingAuthorityNow(id))
            assertEquals("UNAVAILABLE", db.incidentReconciliationDao().get(id)?.lastOutcome)
            db.incidentReconciliationDao().update(requireNotNull(db.incidentReconciliationDao().get(id))
                .copy(retryAtMillis = null))
            app.testInternetUsable = false
            assertEquals(AuthorityWakeResult.OFFLINE, app.reconcilePendingAuthorityNow(id))
            app.testInternetUsable = true
            app.testBoundOwner = null
            assertEquals(AuthorityWakeResult.AUTHENTICATION_REQUIRED, app.reconcilePendingAuthorityNow(id))
            app.testBoundOwner = owner
            app.testSessionOwner = "10000000-0000-4000-8000-0000000000b2"
            assertEquals(AuthorityWakeResult.AUTHENTICATION_REQUIRED, app.reconcilePendingAuthorityNow(id))
            assertNull(db.incidentReconciliationDao().get(id)?.claimId)
            assertEquals("UNAVAILABLE", db.incidentReconciliationDao().get(id)?.lastOutcome)
            app.testSessionOwner = owner
            assertEquals(AuthorityWakeResult.ATTEMPTED, app.reconcilePendingAuthorityNow(id))
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()?.status)
            assertNull(db.incidentReconciliationDao().get(id)?.claimId)
        } finally {
            db.clearAllTables()
            db.close()
        }
    }

    @Test fun foregroundConnectivityAndAuthProductionHooksWakeOnlyWhenApplicable() {
        app.authorityWakeCount = 0
        app.wakeForAuthorityForeground()
        assertEquals(1, app.authorityWakeCount)
        app.authorityWakeCount = 0
        app.wakeForAuthorityConnectivity(true, false)
        app.wakeForAuthorityConnectivity(false, true)
        assertEquals(0, app.authorityWakeCount)
        app.wakeForAuthorityConnectivity(true, true)
        assertEquals(1, app.authorityWakeCount)
        app.wakeForAuthoritySession(TravellerSessionState.NotAuthenticated, owner)
        app.wakeForAuthoritySession(TravellerSessionState.Authenticated(owner, true), owner)
        app.wakeForAuthoritySession(TravellerSessionState.Authenticated("wrong-owner"), owner)
        assertEquals(1, app.authorityWakeCount)
        app.wakeForAuthoritySession(TravellerSessionState.Authenticated(owner), owner)
        assertEquals(2, app.authorityWakeCount)
    }
}
