package com.journeycontinuity.app.degraded

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.SmsManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.data.local.FALLBACK_ATTEMPT_INVARIANT_CALLBACK
import com.journeycontinuity.app.data.local.FallbackAttemptEntity
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.JourneyEntity
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.local.MIGRATION_2_3
import com.journeycontinuity.app.data.local.MIGRATION_3_4
import com.journeycontinuity.app.data.local.MIGRATION_4_5
import com.journeycontinuity.app.data.local.MIGRATION_5_6
import com.journeycontinuity.app.data.local.MIGRATION_6_7
import com.journeycontinuity.app.data.local.MIGRATION_7_8
import com.journeycontinuity.app.data.local.MIGRATION_8_9
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.telemetry.DeviceContextReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only in the opt-in separate-UID package. No configured route or SmsManager submission. */
@RunWith(AndroidJUnit4::class)
class Milestone6ProductionPathAcceptanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
            check(it.packageName == ISOLATED_PACKAGE) { "Refusing production-package acceptance fixture" }
            check(it.applicationContext is JourneyContinuityApplication)
            check(!requireNotNull((it.applicationContext as JourneyContinuityApplication)
                .smsFallbackConfiguration.status().destinationConfigured))
        }

    private fun database(name: String = MAIN_DATABASE): JourneyDatabase = Room.databaseBuilder(
        context, JourneyDatabase::class.java, name,
    ).addMigrations(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
    ).addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()

    @Test
    fun productionBatteryReaderFeedsDurableDegradedRuntime() = runBlocking {
        // Only the Android sticky-broadcast boundary is controlled. The production reader,
        // Room store, coordinator, and policy are unmodified.
        val batteryContext = object : ContextWrapper(context) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? =
                if (receiver == null && filter?.hasAction(Intent.ACTION_BATTERY_CHANGED) == true) {
                    Intent(Intent.ACTION_BATTERY_CHANGED)
                        .putExtra(BatteryManager.EXTRA_LEVEL, 1)
                        .putExtra(BatteryManager.EXTRA_SCALE, 100)
                        .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                } else super.registerReceiver(receiver, filter)
        }
        val observed = DeviceContextReader(batteryContext).battery()
        assertEquals(1, observed.percent)
        assertEquals(false, observed.isCharging)
        val name = "m6-low-battery-production-reader.db"
        context.deleteDatabase(name)
        var db = database(name)
        var now = 1_000L
        val journeyId = "m6-battery-isolated"
        try {
            db.journeyDao().insertIfNoActive(JourneyEntity(
                journeyId, "Synthetic", 99_000, now, JourneyStatus.ACTIVE, null, 1,
            ))
            val policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
            fun coordinator() = DegradedConnectivityCoordinator(
                RoomDegradedConnectivityStateStore(
                    db, db.degradedConnectivityDao(), db.fallbackAttemptDao(),
                    DurableFallbackAttemptAllocator(
                        db.journeyDao(), db.telemetryDao(), db.degradedConnectivityDao(),
                        db.fallbackAttemptDao(), AndroidKeystoreFallbackKeyMaterialStore(context),
                    ), policy,
                ),
                latestTelemetryReader = { id -> db.telemetryDao().getLatest(id)?.toDomain() },
                fallbackCapabilityReader = FallbackCapabilityReader { false },
                policy = policy,
                clock = { now },
            )
            val runtime = coordinator()
            runtime.activate(journeyId, validatedInternetAvailable = false)
            val observation = requireNotNull(db.telemetryDao().insertForActiveJourney(TelemetrySample(
                journeyId, now, 0.0, 0.0, 5f, observed.percent, observed.isCharging,
                ConnectivityState.NONE,
            )))
            runtime.telemetryObserved(observation.toDomain())
            now += DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS + 1
            repeat(10) { runtime.timeAdvanced(journeyId) }
            val state = requireNotNull(db.degradedConnectivityDao().get(journeyId)).toDomain()
            assertEquals(1, state.latestBatteryPercent)
            assertEquals(ConnectivityPhase.DEGRADED, state.connectivityPhase)
            assertTrue(db.fallbackAttemptDao().allForJourney(journeyId).isEmpty())
            assertEquals(1, db.telemetryDao().getLatest(journeyId)?.batteryPercent)
            db.close()
            db = database(name)
            val restored = requireNotNull(db.degradedConnectivityDao().get(journeyId)).toDomain()
            assertEquals(1, restored.latestBatteryPercent)
            assertEquals(ConnectivityPhase.DEGRADED, restored.connectivityPhase)
            assertTrue(db.fallbackAttemptDao().allForJourney(journeyId).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun productionWorkerRunsThroughRealApplicationAndWorkManagerWithoutSend() = runBlocking {
        val app = context.applicationContext as JourneyContinuityApplication
        assertFalse(app.smsFallbackConfiguration.status().destinationConfigured)
        val db = database()
        try {
            ensureFixtureJourney(db)
            val dao = db.fallbackAttemptDao()
            val base = freshSequenceBase()
            val priorCount = dao.allForJourney(JOURNEY_ID).size
            val unknownId = dao.insert(attempt(base + 1).copy(
                transportState = FallbackTransportState.HANDOFF_IN_PROGRESS,
                transportAttemptCount = 1, handoffGeneration = 1,
                handoffStartedAt = System.currentTimeMillis() - 16 * 60_000L,
            ))
            val scheduler = WorkManagerFallbackHandoffScheduler(context)
            scheduler.scheduleUncertainCheck(unknownId, 1, 0)
            awaitFinished("fallback-uncertain-$unknownId-1")
            val unknown = requireNotNull(dao.getByLocalAttemptId(unknownId))
            assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, unknown.transportState)
            assertEquals(1, unknown.transportAttemptCount)
            assertEquals(1, unknown.handoffGeneration)
            repeat(3) {
                scheduler.scheduleRetry(unknownId, 0)
                awaitFinished("fallback-retry-$unknownId")
            }
            val afterRepeatedWork = requireNotNull(dao.getByLocalAttemptId(unknownId))
            assertEquals(unknown.transportState, afterRepeatedWork.transportState)
            assertEquals(unknown.transportAttemptCount, afterRepeatedWork.transportAttemptCount)
            assertEquals(unknown.handoffGeneration, afterRepeatedWork.handoffGeneration)
            assertEquals(unknown.protectedPayloadText, afterRepeatedWork.protectedPayloadText)

            val retryId = dao.insert(attempt(base + 2).copy(
                transportState = FallbackTransportState.RETRY_PENDING,
                transportAttemptCount = 1, handoffGeneration = 1,
                handoffStartedAt = System.currentTimeMillis() - 10_000L,
                nextRetryAt = System.currentTimeMillis() - 1,
                lastTransportOutcome = FallbackTransportOutcome.RETRYABLE_FAILURE,
            ))
            scheduler.scheduleRetry(retryId, 0)
            awaitFinished("fallback-retry-$retryId")
            val retry = requireNotNull(dao.getByLocalAttemptId(retryId))
            assertEquals(FallbackTransportState.RETRY_PENDING, retry.transportState)
            assertEquals(1, retry.transportAttemptCount)
            assertEquals(1, retry.handoffGeneration)
            assertEquals(priorCount + 2, dao.allForJourney(JOURNEY_ID).size)
            assertNotNull(WorkManager.getInstance(context))
        } finally { db.close() }
    }

    @Test
    fun productionReceiverCorrelatesResultsWithoutCarrierSubmission() = runBlocking {
        val db = database()
        try {
            ensureFixtureJourney(db)
            val dao = db.fallbackAttemptDao()
            val base = freshSequenceBase()
            val priorCount = dao.allForJourney(JOURNEY_ID).size
            suspend fun seeded(sequence: Long, count: Int = 1, state: FallbackTransportState =
                FallbackTransportState.HANDOFF_IN_PROGRESS): Long = dao.insert(attempt(sequence).copy(
                    transportState = state, transportAttemptCount = count,
                    handoffGeneration = count, handoffStartedAt = System.currentTimeMillis() - 1_000L,
                    uncertainSince = if (state == FallbackTransportState.UNKNOWN_OUTCOME)
                        System.currentTimeMillis() else null,
                ))
            val success = seeded(base + 1)
            broadcastResult(success, 1, Activity.RESULT_OK)
            assertEquals(FallbackTransportState.HANDED_OFF,
                awaitState(db, success, FallbackTransportState.HANDED_OFF).transportState)
            val accepted = requireNotNull(dao.getByLocalAttemptId(success))
            broadcastResult(success, 1, Activity.RESULT_OK)
            delay(300)
            val duplicate = requireNotNull(dao.getByLocalAttemptId(success))
            assertEquals(accepted.transportState, duplicate.transportState)
            assertEquals(accepted.terminalAt, duplicate.terminalAt)
            assertEquals(accepted.transportAttemptCount, duplicate.transportAttemptCount)
            assertEquals(accepted.protectedPayloadText, duplicate.protectedPayloadText)

            val retry = seeded(base + 2)
            broadcastResult(retry, 1, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
            val retryRow = awaitState(db, retry, FallbackTransportState.RETRY_PENDING)
            assertEquals(1, retryRow.transportAttemptCount)
            assertEquals(1, retryRow.handoffGeneration)
            val permanent = seeded(base + 3)
            broadcastResult(permanent, 1, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED)
            awaitState(db, permanent, FallbackTransportState.PERMANENT_FAILURE)

            val newer = seeded(base + 4, count = 2)
            broadcastResult(newer, 1, Activity.RESULT_OK)
            delay(300)
            assertEquals(FallbackTransportState.HANDOFF_IN_PROGRESS,
                dao.getByLocalAttemptId(newer)?.transportState)
            broadcastResult(newer, 2, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
            val exhausted = awaitState(db, newer, FallbackTransportState.PERMANENT_FAILURE)
            assertEquals(FallbackTransportOutcome.RETRY_EXHAUSTED, exhausted.lastTransportOutcome)

            val late = seeded(base + 5, state = FallbackTransportState.UNKNOWN_OUTCOME)
            broadcastResult(late, 1, Activity.RESULT_OK)
            awaitState(db, late, FallbackTransportState.HANDED_OFF)
            assertEquals(priorCount + 5, dao.allForJourney(JOURNEY_ID).size)
        } finally { db.close() }
    }

    /** First instrumentation process; verify after a separate ADB process termination/run. */
    @Test
    fun preparePostRestartReceiverFixture() = runBlocking {
        val db = database()
        try {
            ensureFixtureJourney(db)
            val dao = db.fallbackAttemptDao()
            val sequence = freshSequenceBase() + 1
            val id = dao.insert(attempt(sequence).copy(
                transportState = FallbackTransportState.UNKNOWN_OUTCOME,
                transportAttemptCount = 1, handoffGeneration = 1,
                handoffStartedAt = System.currentTimeMillis() - 16 * 60_000L,
                uncertainSince = System.currentTimeMillis(),
            ))
            val retryId = dao.insert(attempt(sequence + 1).copy(
                transportState = FallbackTransportState.RETRY_PENDING,
                transportAttemptCount = 1, handoffGeneration = 1,
                handoffStartedAt = System.currentTimeMillis() - 16 * 60_000L,
                nextRetryAt = System.currentTimeMillis() - 1,
                lastTransportOutcome = FallbackTransportOutcome.RETRYABLE_FAILURE,
            ))
            check(context.getSharedPreferences("m6-restart-fixture", Context.MODE_PRIVATE)
                .edit().putLong("attempt-id", id)
                .putLong("retry-attempt-id", retryId)
                .putInt("pre-restart-pid", android.os.Process.myPid()).commit())
            WorkManagerFallbackHandoffScheduler(context).scheduleRetry(id, 60_000)
            repeat(100) {
                if (WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork("fallback-retry-$id").get().isNotEmpty()
                ) return@runBlocking
                delay(20)
            }
            error("Production WorkManager did not persist the restart fixture")
            Unit
        } finally { db.close() }
    }

    @Test
    fun reconcilePostRestartReceiverFixture() = runBlocking {
        val db = database()
        try {
            val dao = db.fallbackAttemptDao()
            val id = context.getSharedPreferences("m6-restart-fixture", Context.MODE_PRIVATE)
                .getLong("attempt-id", -1)
            val previousPid = context.getSharedPreferences("m6-restart-fixture", Context.MODE_PRIVATE)
                .getInt("pre-restart-pid", -1)
            assertTrue(previousPid > 0 && previousPid != android.os.Process.myPid())
            val before = requireNotNull(dao.getByLocalAttemptId(id))
            assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, before.transportState)
            assertEquals(1, before.transportAttemptCount)
            assertEquals(1, before.handoffGeneration)
            val retryId = context.getSharedPreferences("m6-restart-fixture", Context.MODE_PRIVATE)
                .getLong("retry-attempt-id", -1)
            val retryBefore = requireNotNull(dao.getByLocalAttemptId(retryId))
            assertEquals(FallbackTransportState.RETRY_PENDING, retryBefore.transportState)
            assertEquals(1, retryBefore.transportAttemptCount)
            assertEquals(1, retryBefore.handoffGeneration)
            assertTrue(WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("fallback-retry-$id").get().isNotEmpty())
            // The instrumentation runner force-stops its target between process runs;
            // explicitly re-enqueue after restart rather than assuming an OS deadline.
            WorkManagerFallbackHandoffScheduler(context).scheduleRetry(id, 0)
            awaitFinished("fallback-retry-$id")
            assertEquals(before.protectedPayloadText,
                dao.getByLocalAttemptId(id)?.protectedPayloadText)
            assertEquals(FallbackTransportState.UNKNOWN_OUTCOME,
                dao.getByLocalAttemptId(id)?.transportState)
            WorkManagerFallbackHandoffScheduler(context).scheduleRetry(retryId, 0)
            awaitFinished("fallback-retry-$retryId")
            val retryAfter = requireNotNull(dao.getByLocalAttemptId(retryId))
            assertEquals(FallbackTransportState.RETRY_PENDING, retryAfter.transportState)
            assertEquals(1, retryAfter.transportAttemptCount)
            assertEquals(1, retryAfter.handoffGeneration)
            assertEquals(retryBefore.protectedPayloadText, retryAfter.protectedPayloadText)
            broadcastResult(before.localAttemptId, 1, Activity.RESULT_OK)
            val after = awaitState(db, before.localAttemptId, FallbackTransportState.HANDED_OFF)
            assertEquals(before.protectedPayloadText, after.protectedPayloadText)
            assertEquals(1, after.transportAttemptCount)
            assertEquals(1, after.handoffGeneration)
        } finally { db.close() }
    }

    private suspend fun ensureFixtureJourney(db: JourneyDatabase) {
        if (db.journeyDao().getById(JOURNEY_ID) == null) db.journeyDao().insertIfNoActive(
            JourneyEntity(JOURNEY_ID, "Synthetic", 5_000, 100, JourneyStatus.COMPLETED, 200, null),
        )
    }

    private fun attempt(sequence: Long): FallbackAttemptEntity {
        val nonce = ByteArray(FallbackEnvelopeV1.NONCE_BYTES) { index ->
            (sequence ushr (8 * (index % 8))).toByte()
        }
        val frame = FallbackEnvelopeV1.ProtectedFrame(
            FallbackEnvelopeV1.Header(FallbackEnvelopeV1.EventType.OBSERVATION, 7,
                ByteArray(FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES) { 4 }, sequence, nonce),
            ByteArray(FallbackEnvelopeV1.BODY_BYTES) { 2 },
            ByteArray(FallbackEnvelopeV1.AUTHENTICATION_TAG_BYTES) { 3 },
        )
        val payload = FallbackEnvelopeV1.encode(frame).value
        return FallbackAttemptEntity(
            journeyId = JOURNEY_ID, degradationEpisodeId = 1,
            envelopeSequence = sequence, telemetrySequence = sequence,
            observationEventTime = 100, eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
            protectedPayloadText = payload, nonce = nonce,
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()),
            allocatedAt = 200,
        )
    }

    private fun broadcastResult(attemptId: Long, generation: Int, resultCode: Int) {
        // Exercise the same immutable PendingIntent factory passed to SmsManager in production.
        // Sending a PendingIntent with a controlled result code is not a carrier submission.
        sentPendingIntent(context, attemptId, generation).send(resultCode)
    }

    private suspend fun awaitState(
        db: JourneyDatabase, attemptId: Long, expected: FallbackTransportState,
    ): FallbackAttemptEntity {
        repeat(100) {
            val attempt = requireNotNull(db.fallbackAttemptDao().getByLocalAttemptId(attemptId))
            if (attempt.transportState == expected) return attempt
            delay(50)
        }
        error("Receiver did not reach $expected; actual=" +
            db.fallbackAttemptDao().getByLocalAttemptId(attemptId)?.transportState)
    }

    private suspend fun awaitFinished(uniqueName: String): WorkInfo {
        val manager = WorkManager.getInstance(context)
        repeat(200) {
            val work = manager.getWorkInfosForUniqueWork(uniqueName).get(5, TimeUnit.SECONDS)
                .singleOrNull()
            if (work?.state?.isFinished == true) {
                assertEquals(WorkInfo.State.SUCCEEDED, work.state)
                return work
            }
            delay(100)
        }
        error("Production WorkManager did not finish $uniqueName")
    }

    private fun freshSequenceBase(): Long = System.currentTimeMillis() / 1_000L

    companion object {
        private const val ISOLATED_PACKAGE = "com.journeycontinuity.app.m6isolation"
        private const val MAIN_DATABASE = "journey-continuity.db"
        private const val JOURNEY_ID = "m6-production-path-isolated"
    }
}
