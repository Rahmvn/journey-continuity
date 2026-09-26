package com.journeycontinuity.app.degraded

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.journeycontinuity.app.data.local.FALLBACK_ATTEMPT_INVARIANT_CALLBACK
import com.journeycontinuity.app.data.local.FallbackAttemptEntity
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.JourneyEntity
import com.journeycontinuity.app.domain.JourneyStatus
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** WorkManager's real scheduling/data path with an isolated worker factory; never creates SmsManager. */
@RunWith(AndroidJUnit4::class)
class FallbackWorkManagerFrameworkTest {
    @Test
    fun scheduledUncertaintyAndRetryRespectDurableStateAndTwoClaimBudget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "m6-workmanager-framework-test.db"
        context.deleteDatabase(databaseName)
        var now = 1_000L
        var submissions = 0
        lateinit var coordinator: FallbackHandoffCoordinator
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
            ): androidx.work.ListenableWorker? {
                if (workerClassName != FallbackHandoffWorker::class.java.name) return null
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result {
                        runFallbackHandoffWork(
                            coordinator,
                            inputData.getLong("fallback_attempt_id", -1),
                            inputData.getInt("fallback_handoff_generation", -1),
                        )
                        return Result.success()
                    }
                }
            }
        }
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        val manager = WorkManager.getInstance(context)
        val scheduler = WorkManagerFallbackHandoffScheduler(context)
        fun makeCoordinator(db: JourneyDatabase) = FallbackHandoffCoordinator(
            RoomFallbackHandoffAttemptStore(db.fallbackAttemptDao()),
            object : SmsFallbackConfiguration {
                override fun status(): SmsFallbackStatus = error("Only resolveForSend is used")
                override fun resolveForSend() =
                    SmsTransportResolution.Available(7, SmsFallbackRoute("+15555550123"))
                override fun selectSubscription(subscriptionId: Int) = false
            },
            object : SmsTelephonyGateway {
                override fun divideMessage(subscriptionId: Int, text: String) = listOf(text)
                override fun send(request: SmsHandoffRequest) { submissions++ }
            },
            scheduler, clock = { now }, uncertaintyWindowMillis = 100,
        )
        suspend fun runScheduled(name: String) {
            val info = manager.getWorkInfosForUniqueWork(name).get().single()
            val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            driver.setInitialDelayMet(info.id)
            repeat(100) {
                if (requireNotNull(manager.getWorkInfoById(info.id).get()).state.isFinished) return
                delay(20)
            }
            error("WorkManager did not finish $name")
        }
        var db = Room.databaseBuilder(context, JourneyDatabase::class.java, databaseName)
            .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()
        try {
            db.journeyDao().insertIfNoActive(
                JourneyEntity("test", "Synthetic", 5_000, 100, JourneyStatus.ACTIVE, null, 1),
            )
            val dao = db.fallbackAttemptDao()
            coordinator = makeCoordinator(db)
            val unknownId = dao.insert(validAttempt(1))
            assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, coordinator.handoff(unknownId))
            now += 101
            runScheduled("fallback-uncertain-$unknownId-1")
            assertEquals(FallbackTransportState.UNKNOWN_OUTCOME,
                dao.getByLocalAttemptId(unknownId)!!.transportState)
            assertEquals(1, submissions)

            // Reopen the isolated on-disk Room fixture; the framework work DB remains in-memory.
            db.close()
            db = Room.databaseBuilder(context, JourneyDatabase::class.java, databaseName)
                .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()
            coordinator = makeCoordinator(db)
            val reopenedDao = db.fallbackAttemptDao()
            val reopened = requireNotNull(reopenedDao.getByLocalAttemptId(unknownId))
            assertEquals(1, reopened.transportAttemptCount)
            assertEquals(1, reopened.handoffGeneration)
            assertEquals(validAttempt(1).protectedPayloadText, reopened.protectedPayloadText)
            scheduler.scheduleUncertainCheck(unknownId, 1, 100)
            runScheduled("fallback-uncertain-$unknownId-1")
            scheduler.scheduleRetry(unknownId, 100)
            runScheduled("fallback-retry-$unknownId")
            assertEquals(1, submissions)
            assertEquals(FallbackTransportState.UNKNOWN_OUTCOME,
                reopenedDao.getByLocalAttemptId(unknownId)!!.transportState)

            val retryId = reopenedDao.insert(validAttempt(2))
            assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, coordinator.handoff(retryId))
            assertTrue(coordinator.sentResult(retryId, 1, 1))
            val pending = requireNotNull(reopenedDao.getByLocalAttemptId(retryId))
            assertEquals(FallbackTransportState.RETRY_PENDING, pending.transportState)
            db.close()
            db = Room.databaseBuilder(context, JourneyDatabase::class.java, databaseName)
                .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()
            coordinator = makeCoordinator(db)
            val retryDao = db.fallbackAttemptDao()
            assertEquals(1, requireNotNull(retryDao.getByLocalAttemptId(retryId)).transportAttemptCount)
            now = requireNotNull(pending.nextRetryAt)
            runScheduled("fallback-retry-$retryId")
            val second = requireNotNull(retryDao.getByLocalAttemptId(retryId))
            assertEquals(2, second.transportAttemptCount)
            assertEquals(2, second.handoffGeneration)
            assertEquals(3, submissions)
            assertTrue(coordinator.sentResult(retryId, 2, 1))
            assertEquals(FallbackTransportOutcome.RETRY_EXHAUSTED,
                retryDao.getByLocalAttemptId(retryId)!!.lastTransportOutcome)
            scheduler.scheduleRetry(retryId, 100)
            runScheduled("fallback-retry-$retryId")
            assertEquals(3, submissions)
            assertEquals(FallbackTransportState.PERMANENT_FAILURE,
                retryDao.getByLocalAttemptId(retryId)!!.transportState)
            assertFalse(coordinator.sentResult(retryId, 1, -1))
            assertEquals(2, retryDao.allForJourney("test").size)
            assertNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        } finally {
            db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
            context.deleteDatabase(databaseName)
        }
    }

    private fun validAttempt(sequence: Long): FallbackAttemptEntity {
        val nonce = ByteArray(FallbackEnvelopeV1.NONCE_BYTES) { sequence.toByte() }
        val frame = FallbackEnvelopeV1.ProtectedFrame(
            FallbackEnvelopeV1.Header(FallbackEnvelopeV1.EventType.OBSERVATION, 7,
                ByteArray(FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES) { 4 }, sequence, nonce),
            ByteArray(FallbackEnvelopeV1.BODY_BYTES) { 2 },
            ByteArray(FallbackEnvelopeV1.AUTHENTICATION_TAG_BYTES) { 3 },
        )
        val text = FallbackEnvelopeV1.encode(frame).value
        return FallbackAttemptEntity(
            journeyId = "test", degradationEpisodeId = 1, envelopeSequence = sequence,
            telemetrySequence = sequence, observationEventTime = 100,
            eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
            protectedPayloadText = text, nonce = nonce,
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()),
            allocatedAt = 200,
        )
    }
}
