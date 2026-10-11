package com.journeycontinuity.app.service

import android.content.ComponentName
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BoundaryAcceptanceApplication
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.OperationalBoundaryState
import com.journeycontinuity.app.domain.OperationalBoundaryAssessment
import com.journeycontinuity.app.domain.StartJourneyResult
import com.journeycontinuity.app.data.repository.WorkManagerAuthorityRetryScheduler
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run each named stage in a separate isolated instrumentation process, with an app-process
 * termination between seed and recovery. These tests do not simulate a device reboot. */
@RunWith(AndroidJUnit4::class)
class JourneyProcessRecoveryAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also {
        check(it.packageName == "com.journeycontinuity.app.boundaryacceptance")
        check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty())
    }
    private val app get() = context.applicationContext as BoundaryAcceptanceApplication
    private fun database() = Room.databaseBuilder(context, JourneyDatabase::class.java,
        "journey-continuity.db").build()
    private fun requireStage(name: String) = assumeTrue(
        InstrumentationRegistry.getArguments().getString("recoveryStage") == name)

    private fun recoveryWork() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(JourneyBootRecoveryWorker.UNIQUE_NAME)
        .get(10, TimeUnit.SECONDS)

    private suspend fun awaitRecovery(id: java.util.UUID, expected: WorkInfo.State) =
        withTimeout(70_000) {
            while (recoveryWork().none { it.id == id && it.state == expected }) delay(100)
        }

    private suspend fun awaitProcessEntry() = withTimeout(15_000) {
        while (app.processRecoveryCalls == 0) delay(50)
    }

    private suspend fun seed(db: JourneyDatabase): String {
        awaitProcessEntry()
        db.clearAllTables()
        val result = app.journeyLifecycle.start("Isolated process recovery fixture",
            System.currentTimeMillis() + 3_600_000L)
        assertTrue(result is StartJourneyResult.Started)
        return (result as StartJourneyResult.Started).journey.id
    }

    @Test fun bootReceiverIsManifestDeclaredWithoutExternalExport() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, JourneyBootRecoveryReceiver::class.java), 0)
        assertFalse(receiver.exported)
    }

    @Test fun duplicateBootHandoffsKeepOneDurableJobAndNoActiveJourneyIsInert() = runBlocking {
        awaitProcessEntry()
        val db = database()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            db.clearAllTables()
            app.beforeRecoveryBoundary = { entered.complete(Unit); release.await() }
            val previous = recoveryWork().map { it.id }.toSet()
            val first = async(Dispatchers.IO) {
                JourneyBootRecoveryWorker.enqueue(context).result.get(10, TimeUnit.SECONDS)
            }
            withTimeout(10_000) { entered.await() }
            val second = async(Dispatchers.IO) {
                JourneyBootRecoveryWorker.enqueue(context).result.get(10, TimeUnit.SECONDS)
            }
            first.await(); second.await()
            val added = recoveryWork().filter { it.id !in previous }
            assertEquals(1, added.size)
            assertFalse(added.single().state.isFinished)
            release.complete(Unit)
            awaitRecovery(added.single().id, WorkInfo.State.SUCCEEDED)
            assertNull(db.journeyDao().getActive())
        } finally {
            release.complete(Unit)
            app.beforeRecoveryBoundary = {}
            db.clearAllTables(); db.close()
        }
    }

    @Test fun interruptedAfterPauseRetainsDurableJobUntilAuthorityWakeIsRestored() = runBlocking {
        val db = database()
        val secondRunEntered = CompletableDeferred<Unit>()
        val releaseSecondRun = CompletableDeferred<Unit>()
        val assessments = AtomicInteger()
        try {
            val id = seed(db)
            val anchor = requireNotNull(db.operationalBoundaryDao().get(id)?.anchor())
            db.openHelper.writableDatabase.execSQL(
                "UPDATE journey_operational_boundaries SET anchorBootCount = ? WHERE journeyId = ?",
                arrayOf<Any>((anchor.bootCount ?: 0) + 1, id))
            app.overrideAuthorityGate = true
            app.testInternetUsable = false
            app.afterRecoveryBoundary = {
                if (assessments.incrementAndGet() == 2) {
                    secondRunEntered.complete(Unit)
                    releaseSecondRun.await()
                }
            }
            app.failRecoveryAfterBoundaryOnce.set(true)
            val previous = recoveryWork().map { it.id }.toSet()
            JourneyBootRecoveryWorker.enqueue(context).result.get(10, TimeUnit.SECONDS)
            val newWork = withTimeout(15_000) {
                var candidate: WorkInfo? = null
                while (candidate == null) {
                    candidate = recoveryWork().firstOrNull {
                        it.id !in previous && it.state == WorkInfo.State.ENQUEUED &&
                            it.runAttemptCount >= 1
                    }
                    if (candidate == null) delay(100)
                }
                candidate
            }
            assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
                db.operationalBoundaryDao().get(id)?.state)
            assertEquals(id, db.journeyDao().getActive()?.id)
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()?.status)
            assertNull(JourneyForegroundService.monitoringJourneyId.value)
            withTimeout(60_000) { secondRunEntered.await() }
            val manager = WorkManager.getInstance(context)
            manager.cancelAllWorkByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
                .result.get(10, TimeUnit.SECONDS)
            assertTrue(manager.getWorkInfosByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
                .get(10, TimeUnit.SECONDS).none { !it.state.isFinished })
            releaseSecondRun.complete(Unit)
            awaitRecovery(newWork.id, WorkInfo.State.SUCCEEDED)
            assertTrue(manager.getWorkInfosByTag(
                WorkManagerAuthorityRetryScheduler.journeyTag(id)).get(10, TimeUnit.SECONDS)
                .any { !it.state.isFinished &&
                    "journey-authority-deadline-immediate" in it.tags })
        } finally {
            releaseSecondRun.complete(Unit)
            app.afterRecoveryBoundary = {}
            app.failRecoveryAfterBoundaryOnce.set(false)
            app.overrideAuthorityGate = false
            db.clearAllTables(); db.close()
        }
    }

    @Test fun interruptedBeforePauseRetriesWithoutLosingExactJourney() = runBlocking {
        val db = database()
        try {
            val id = seed(db)
            val anchor = requireNotNull(db.operationalBoundaryDao().get(id)?.anchor())
            db.openHelper.writableDatabase.execSQL(
                "UPDATE journey_operational_boundaries SET anchorBootCount = ? WHERE journeyId = ?",
                arrayOf<Any>((anchor.bootCount ?: 0) + 1, id))
            app.failRecoveryBeforeBoundaryOnce.set(true)
            val previous = recoveryWork().map { it.id }.toSet()
            JourneyBootRecoveryWorker.enqueue(context).result.get(10, TimeUnit.SECONDS)
            val newWork = withTimeout(15_000) {
                var candidate: WorkInfo? = null
                while (candidate == null) {
                    candidate = recoveryWork().firstOrNull {
                        it.id !in previous && it.state == WorkInfo.State.ENQUEUED &&
                            it.runAttemptCount >= 1
                    }
                    if (candidate == null) delay(100)
                }
                candidate
            }
            assertEquals(OperationalBoundaryState.MONITORING.name,
                db.operationalBoundaryDao().get(id)?.state)
            awaitRecovery(newWork.id, WorkInfo.State.SUCCEEDED)
            assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
                db.operationalBoundaryDao().get(id)?.state)
            assertEquals(id, db.journeyDao().getActive()?.id)
        } finally {
            app.failRecoveryBeforeBoundaryOnce.set(false)
            db.clearAllTables(); db.close()
        }
    }

    @Test fun seedRecoveryJobInterruptedAfterPause() = runBlocking {
        requireStage("seedInterruptedAfterPause")
        val db = database()
        val entered = CompletableDeferred<Unit>()
        try {
            val id = seed(db)
            val anchor = requireNotNull(db.operationalBoundaryDao().get(id)?.anchor())
            db.openHelper.writableDatabase.execSQL(
                "UPDATE journey_operational_boundaries SET anchorBootCount = ? WHERE journeyId = ?",
                arrayOf<Any>((anchor.bootCount ?: 0) + 1, id))
            app.afterRecoveryBoundary = { entered.complete(Unit); awaitCancellation() }
            val previous = recoveryWork().map { it.id }.toSet()
            JourneyBootRecoveryWorker.enqueue(context).result.get(10, TimeUnit.SECONDS)
            withTimeout(10_000) { entered.await() }
            val running = recoveryWork().single { it.id !in previous }
            assertEquals(WorkInfo.State.RUNNING, running.state)
            assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
                db.operationalBoundaryDao().get(id)?.state)
            assertEquals(id, db.journeyDao().getActive()?.id)
            val manager = WorkManager.getInstance(context)
            manager.cancelAllWorkByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
                .result.get(10, TimeUnit.SECONDS)
            assertTrue(manager.getWorkInfosByTag(WorkManagerAuthorityRetryScheduler.journeyTag(id))
                .get(10, TimeUnit.SECONDS).none { !it.state.isFinished })
            assertTrue(context.getSharedPreferences("isolated-recovery-stage", 0).edit()
                .putString("journey", id).putString("work", running.id.toString()).commit())
        } finally { db.close() }
    }

    @Test fun sameRecoveryJobResumesAfterIsolatedForceStop() = runBlocking {
        requireStage("recoverInterruptedAfterPause")
        val saved = context.getSharedPreferences("isolated-recovery-stage", 0)
        val id = requireNotNull(saved.getString("journey", null))
        val workId = java.util.UUID.fromString(requireNotNull(saved.getString("work", null)))
        val db = database()
        try {
            awaitRecovery(workId, WorkInfo.State.SUCCEEDED)
            assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
                db.operationalBoundaryDao().get(id)?.state)
            assertEquals(id, db.journeyDao().getActive()?.id)
            assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(
                WorkManagerAuthorityRetryScheduler.journeyTag(id)).get(10, TimeUnit.SECONDS)
                .any { "journey-authority-deadline-immediate" in it.tags })
            assertNull(JourneyForegroundService.monitoringJourneyId.value)
        } finally {
            saved.edit().clear().commit()
            db.clearAllTables(); db.close()
        }
    }

    @Test fun bootRecoveryEntryPausesAndKeepsAnAuthorityWake() = runBlocking {
        requireStage("bootEntry")
        val db = database()
        try {
            val id = seed(db)
            val anchor = requireNotNull(db.operationalBoundaryDao().get(id)?.anchor())
            db.openHelper.writableDatabase.execSQL(
                "UPDATE journey_operational_boundaries SET anchorBootCount = ? WHERE journeyId = ?",
                arrayOf<Any>((anchor.bootCount ?: 0) + 1, id))
            app.recoverAfterBoot()
            assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
                db.operationalBoundaryDao().get(id)?.state)
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getActive()?.status)
            assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(
                WorkManagerAuthorityRetryScheduler.journeyTag(id)).get(10, TimeUnit.SECONDS)
                .any { "journey-authority-deadline-immediate" in it.tags })
            assertNull(JourneyForegroundService.monitoringJourneyId.value)
        } finally { db.clearAllTables(); db.close() }
    }

    @Test fun seedSameBootJourney() = runBlocking {
        requireStage("seedSameBoot")
        val db = database()
        try {
            val id = seed(db)
            assertEquals(OperationalBoundaryState.MONITORING.name,
                db.operationalBoundaryDao().get(id)?.state)
            assertTrue(requireNotNull(db.operationalBoundaryDao().get(id)?.anchor())
                .sameBoot(app.operationalBoundaryReceipt()))
        } finally { db.close() }
    }

    @Test fun recoverSameBootJourneyAfterProcessTermination() = runBlocking {
        requireStage("recoverSameBoot")
        val db = database()
        try {
            try {
                awaitProcessEntry()
                val active = requireNotNull(db.journeyDao().getActive())
                assertEquals(JourneyStatus.ACTIVE, active.status)
                assertEquals(OperationalBoundaryState.MONITORING.name,
                    db.operationalBoundaryDao().get(active.id)?.state)
                assertTrue(requireNotNull(db.operationalBoundaryDao().get(active.id)?.anchor())
                    .sameBoot(app.operationalBoundaryReceipt()))
                assertNull(JourneyForegroundService.monitoringJourneyId.value)
            } finally { db.clearAllTables() }
        } finally { db.close() }
    }

    @Test fun seedJourneyAlreadyPastPrivacyCutoff() = runBlocking {
        requireStage("seedPaused")
        val db = database()
        try {
            val id = seed(db)
            val admission = requireNotNull(db.unresolvedBoundaryDao().get(id)).admission()
            val anchor = requireNotNull(db.operationalBoundaryDao().get(id)?.anchor())
            val due = anchor.copy(elapsedRealtimeMillis = anchor.elapsedRealtimeMillis +
                admission.boundaryMillis - admission.admittedAtMillis)
            assertEquals(OperationalBoundaryAssessment.PAUSED,
                app.operationalBoundaryCoordinator.evaluate(id, due))
            assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
                db.operationalBoundaryDao().get(id)?.state)
        } finally { db.close() }
    }

    @Test fun processEntryCannotResumeAnAlreadyPausedJourney() = runBlocking {
        requireStage("recoverPaused")
        val db = database()
        try {
            try {
                awaitProcessEntry()
                val active = requireNotNull(db.journeyDao().getActive())
                assertEquals(JourneyStatus.ACTIVE, active.status)
                assertEquals(OperationalBoundaryState.PENDING_AUTHORITY.name,
                    db.operationalBoundaryDao().get(active.id)?.state)
                assertFalse(app.recoverOperationalBoundaryOnEntry())
                assertNull(JourneyForegroundService.monitoringJourneyId.value)
            } finally { db.clearAllTables() }
        } finally { db.close() }
    }

    @Test fun seedChangedBootEvidence() = runBlocking {
        requireStage("seedChangedBoot")
        val db = database()
        try {
            val id = seed(db)
            val anchor = requireNotNull(db.operationalBoundaryDao().get(id)?.anchor())
            db.openHelper.writableDatabase.execSQL(
                "UPDATE journey_operational_boundaries SET anchorBootCount = ? WHERE journeyId = ?",
                arrayOf<Any>((anchor.bootCount ?: 0) + 1, id))
            assertEquals(OperationalBoundaryState.MONITORING.name,
                db.operationalBoundaryDao().get(id)?.state)
        } finally { db.close() }
    }

    @Test fun processEntryPausesChangedBootEvidenceBeforeServiceStart() = runBlocking {
        requireStage("recoverChangedBoot")
        val db = database()
        try {
            try {
                awaitProcessEntry()
                val active = requireNotNull(db.journeyDao().getActive())
                withTimeout(15_000) {
                    while (db.operationalBoundaryDao().get(active.id)?.state !=
                        OperationalBoundaryState.PENDING_AUTHORITY.name) delay(50)
                }
                assertEquals(JourneyStatus.ACTIVE, active.status)
                assertEquals("LOST_OR_INVALID_BOOT_ANCHOR",
                    db.operationalBoundaryDao().get(active.id)?.pauseReason)
                assertNull(JourneyForegroundService.monitoringJourneyId.value)
                withTimeout(15_000) {
                    while (WorkManager.getInstance(context).getWorkInfosByTag(
                            WorkManagerAuthorityRetryScheduler.journeyTag(active.id))
                            .get(10, TimeUnit.SECONDS).none { work ->
                                "journey-authority-deadline-immediate" in work.tags }) delay(100)
                }
                assertFalse(app.recoverOperationalBoundaryOnEntry())
            } finally { db.clearAllTables() }
        } finally { db.close() }
    }
}
