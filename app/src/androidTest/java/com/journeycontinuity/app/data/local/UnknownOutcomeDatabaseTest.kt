package com.journeycontinuity.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.degraded.FallbackEnvelopeV1
import com.journeycontinuity.app.degraded.FallbackHandoffCoordinator
import com.journeycontinuity.app.degraded.FallbackHandoffResult
import com.journeycontinuity.app.degraded.FallbackHandoffScheduler
import com.journeycontinuity.app.degraded.FallbackTransportOutcome
import com.journeycontinuity.app.degraded.FallbackTransportState
import com.journeycontinuity.app.degraded.RoomFallbackHandoffAttemptStore
import com.journeycontinuity.app.degraded.SmsFallbackConfiguration
import com.journeycontinuity.app.degraded.SmsFallbackRoute
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.degraded.SmsHandoffRequest
import com.journeycontinuity.app.degraded.SmsTelephonyGateway
import com.journeycontinuity.app.degraded.SmsTransportResolution
import com.journeycontinuity.app.degraded.runFallbackHandoffWork
import com.journeycontinuity.app.domain.JourneyStatus
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Isolated databases only; never opens the installed Journey or invokes telephony. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UnknownOutcomeDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private inline fun <T> JourneyDatabase.useDatabase(block: (JourneyDatabase) -> T): T =
        try { block(this) } finally { close() }

    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), JourneyDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationConvertsLegacyUnknownRetryAndPreservesHandedOffEvidence() {
        val name = "unknown-outcome-v8-v9-test.db"
        try {
            val original = migrations.createDatabase(name, 8).apply {
                execSQL("""INSERT INTO journeys VALUES
                    ('test', 'Synthetic', 5000, 1000, 'ACTIVE', NULL, 1)""")
                insertV8Attempt(this, 11, "RETRY_PENDING", 1, "UNKNOWN_OUTCOME", 9000, 1100)
                insertV8Attempt(this, 12, "HANDED_OFF", 1, "ANDROID_HANDOFF_SUCCEEDED", null, 1200)
                insertV8Attempt(this, 13, "RETRY_PENDING", 2, "RETRYABLE_FAILURE", 9000, 1300)
                insertV8Attempt(this, 14, "RETRY_PENDING", 1, "RETRYABLE_FAILURE", 9000, 1400)
                insertV8Attempt(this, 15, "ALLOCATED", 0, null, null, 1500)
                insertV8Attempt(this, 16, "SUPERSEDED", 0, null, null, 1600)
                insertV8Attempt(this, 17, "PERMANENT_FAILURE", 1, "PERMANENT_FAILURE", null, 1700)
            }
            val preservedBefore = (12..17).associateWith { fingerprint(original, it) }
            val transformedPayloadsBefore = listOf(11, 13).associateWith {
                fingerprint(original, it, IMMUTABLE_AND_COUNT_COLUMNS)
            }
            original.close()

            migrations.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9).use { migrated ->
                // Legitimate confirmed retry, ALLOCATED, and terminal history must be byte-for-byte unchanged.
                listOf(12, 14, 15, 16, 17).forEach { id ->
                    assertEquals(preservedBefore.getValue(id), fingerprint(migrated, id))
                }
                listOf(11, 13).forEach { id ->
                    assertEquals(transformedPayloadsBefore.getValue(id),
                        fingerprint(migrated, id, IMMUTABLE_AND_COUNT_COLUMNS))
                }
                migrated.query("""SELECT transportState, transportAttemptCount, handoffGeneration,
                    nextRetryAt, lastTransportOutcome, uncertainSince, protectedPayloadText
                    FROM fallback_attempts WHERE localAttemptId = 11""").use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("UNKNOWN_OUTCOME", row.getString(0))
                    assertEquals(1, row.getInt(1))
                    assertEquals(1, row.getInt(2))
                    assertTrue(row.isNull(3))
                    assertEquals("UNKNOWN_OUTCOME", row.getString(4))
                    assertEquals(1100L, row.getLong(5))
                    assertEquals(SYNTHETIC_JC1, row.getString(6))
                }
                migrated.query("""SELECT transportState, transportAttemptCount, lastTransportOutcome
                    FROM fallback_attempts WHERE localAttemptId = 13""").use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("PERMANENT_FAILURE", row.getString(0))
                    assertEquals(2, row.getInt(1))
                    assertEquals("RETRY_EXHAUSTED", row.getString(2))
                }
                migrated.query("""SELECT transportState, transportAttemptCount, nextRetryAt,
                    lastTransportOutcome FROM fallback_attempts WHERE localAttemptId = 14""").use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("RETRY_PENDING", row.getString(0))
                    assertEquals(1, row.getInt(1))
                    assertEquals(9000L, row.getLong(2))
                    assertEquals("RETRYABLE_FAILURE", row.getString(3))
                }
            }
            Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build().useDatabase { db ->
                    runBlocking {
                        val dao = db.fallbackAttemptDao()
                        assertEquals(FallbackTransportState.UNKNOWN_OUTCOME,
                            dao.getByLocalAttemptId(11)!!.transportState)
                        assertEquals(14L, dao.getNextReadyAttempt("test", Long.MAX_VALUE)!!.localAttemptId)
                        assertEquals(0, dao.claimForHandoff(11, Long.MAX_VALUE))
                        assertTrue(noSendCoordinator(dao).handoff(11) is FallbackHandoffResult.NotReady)
                        assertEquals(1, dao.claimForHandoff(14, 9_000))
                        assertEquals(2, dao.getByLocalAttemptId(14)!!.transportAttemptCount)
                        assertEquals(0, dao.claimForHandoff(14, Long.MAX_VALUE))
                        assertTrue(dao.insert(attempt()) > 17L)
                    }
                }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun unknownSurvivesReopenRecoveryAndClosureAndAcceptsLateMatchingSuccess() = runBlocking {
        val name = "unknown-outcome-reopen-test.db"
        fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name)
            .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()
        try {
            open().useDatabase { db ->
                db.journeyDao().insertIfNoActive(journey())
                val id = db.fallbackAttemptDao().insert(attempt())
                assertEquals(1, db.fallbackAttemptDao().claimForHandoff(id, 1_000))
                assertEquals(1, db.fallbackAttemptDao().markUnknownOutcome(id, 1, 1_000, 2_000))
                assertEquals(0, db.fallbackAttemptDao().markUnknownOutcome(id, 1, 1_000, 2_001))
            }
            open().useDatabase { db ->
                val dao = db.fallbackAttemptDao()
                val pending = dao.allForJourney("test").single()
                assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, pending.transportState)
                assertEquals(FallbackTransportOutcome.UNKNOWN_OUTCOME, pending.lastTransportOutcome)
                assertEquals(1, pending.transportAttemptCount)
                assertEquals(1, pending.handoffGeneration)
                assertNull(pending.nextRetryAt)
                assertNull(dao.getNextReadyAttempt("test", Long.MAX_VALUE))
                assertEquals(0, dao.claimForHandoff(pending.localAttemptId, Long.MAX_VALUE))
                val resumedWorker = noSendCoordinator(dao)
                repeat(3) {
                    assertFalse(resumedWorker.recoverUncertain(pending.localAttemptId, 1))
                    runFallbackHandoffWork(resumedWorker, pending.localAttemptId, 1)
                    runFallbackHandoffWork(resumedWorker, pending.localAttemptId, -1)
                    assertTrue(resumedWorker.handoff(pending.localAttemptId) is FallbackHandoffResult.NotReady)
                }
                assertEquals(0, dao.supersedeUnsent("test", 3_000))
                assertNotNull(db.journeyDao().completeActive(3_100))
                assertEquals(0, dao.supersedeUnsent("test", 3_200))
                assertEquals(FallbackTransportState.UNKNOWN_OUTCOME,
                    dao.getByLocalAttemptId(pending.localAttemptId)!!.transportState)
                assertEquals(1, dao.markHandedOff(pending.localAttemptId, 1, 3_300, -1))
                assertEquals(0, dao.markHandedOff(pending.localAttemptId, 1, 3_301, -1))
                assertEquals(0, dao.supersedeUnsent("test", 3_400))
                val handedOff = dao.getByLocalAttemptId(pending.localAttemptId)!!
                assertEquals(FallbackTransportState.HANDED_OFF, handedOff.transportState)
                assertEquals(pending.protectedPayloadText, handedOff.protectedPayloadText)
                assertArrayEquals(pending.nonce, handedOff.nonce)
                assertArrayEquals(pending.payloadSha256, handedOff.payloadSha256)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun lateConfirmedFailureUsesOnlyRemainingClaimThenExhaustsBudget() = runBlocking {
        val name = "confirmed-retry-budget-reopen-test.db"
        fun open() = Room.databaseBuilder(context, JourneyDatabase::class.java, name)
            .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()
        try {
            var id = -1L
            open().useDatabase { db ->
                db.journeyDao().insertIfNoActive(journey())
                val dao = db.fallbackAttemptDao()
                id = dao.insert(attempt())
                assertEquals(1, dao.claimForHandoff(id, 1_000))
                assertEquals(1, dao.markUnknownOutcome(id, 1, 1_000, 2_000))
                assertEquals(0, dao.markRetryPending(id, 1, 2_100,
                    FallbackTransportOutcome.UNKNOWN_OUTCOME, 1))
                assertEquals(1, dao.markRetryPending(id, 1, 2_100,
                    FallbackTransportOutcome.RETRYABLE_FAILURE, 1))
            }
            open().useDatabase { db ->
                val dao = db.fallbackAttemptDao()
                val pending = dao.getByLocalAttemptId(id)!!
                assertEquals(1, pending.transportAttemptCount)
                assertEquals(1, pending.handoffGeneration)
                assertEquals(FallbackTransportState.RETRY_PENDING, pending.transportState)
                assertEquals(0, dao.claimForHandoff(id, 2_099))
                assertEquals(1, dao.claimForHandoff(id, 2_100))
            }
            open().useDatabase { db ->
                val dao = db.fallbackAttemptDao()
                assertEquals(2, dao.getByLocalAttemptId(id)!!.transportAttemptCount)
                assertEquals(0, dao.markRetryPending(id, 2, 2_200,
                    FallbackTransportOutcome.RETRYABLE_FAILURE, 1))
                assertEquals(1, dao.markRetryExhausted(id, 2, 2_200, 1))
                assertEquals(0, dao.markRetryExhausted(id, 2, 2_201, 1))
                assertNull(dao.getNextReadyAttempt("test", Long.MAX_VALUE))
                assertEquals(0, dao.claimForHandoff(id, Long.MAX_VALUE))
                val exhausted = dao.getByLocalAttemptId(id)!!
                assertEquals(2, exhausted.transportAttemptCount)
                assertEquals(2, exhausted.handoffGeneration)
                assertEquals(FallbackTransportState.PERMANENT_FAILURE, exhausted.transportState)
                assertEquals(FallbackTransportOutcome.RETRY_EXHAUSTED, exhausted.lastTransportOutcome)
                assertEquals(SYNTHETIC_JC1, exhausted.protectedPayloadText)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun noSendGatewayProvesTimeoutLateCallbacksAndFiniteClaimsOnDevice() = runBlocking {
        val name = "unknown-outcome-no-send-device-test.db"
        var now = 1_000L
        val submissions = mutableListOf<SmsHandoffRequest>()
        var retrySchedules = 0
        val scheduler = object : FallbackHandoffScheduler {
            override fun scheduleUncertainCheck(localAttemptId: Long, generation: Int, delayMillis: Long) = Unit
            override fun scheduleRetry(localAttemptId: Long, delayMillis: Long) { retrySchedules++ }
        }
        try {
            Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build().useDatabase { db ->
                    db.journeyDao().insertIfNoActive(journey())
                    val dao = db.fallbackAttemptDao()
                    val gateway = object : SmsTelephonyGateway {
                        override fun divideMessage(subscriptionId: Int, text: String) = listOf(text)
                        override fun send(request: SmsHandoffRequest) { submissions += request }
                    }
                    val configuration = object : SmsFallbackConfiguration {
                        override fun status(): SmsFallbackStatus = error("Only resolveForSend is used")
                        override fun resolveForSend() =
                            SmsTransportResolution.Available(7, SmsFallbackRoute("+15555550123"))
                        override fun selectSubscription(subscriptionId: Int) = false
                    }
                    val coordinator = FallbackHandoffCoordinator(
                        RoomFallbackHandoffAttemptStore(dao), configuration, gateway, scheduler,
                        clock = { now }, uncertaintyWindowMillis = 100,
                    )

                    val success = validAttempt(1)
                    val successId = dao.insert(success)
                    assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, coordinator.handoff(successId))
                    assertEquals(FallbackTransportState.HANDOFF_IN_PROGRESS,
                        dao.getByLocalAttemptId(successId)!!.transportState)
                    now += 101
                    assertTrue(coordinator.recoverUncertain(successId, 1))
                    repeat(3) {
                        assertFalse(coordinator.recoverUncertain(successId, 1))
                        runFallbackHandoffWork(coordinator, successId, 1)
                        runFallbackHandoffWork(coordinator, successId, -1)
                        assertTrue(coordinator.handoff(successId) is FallbackHandoffResult.NotReady)
                    }
                    val unknown = dao.getByLocalAttemptId(successId)!!
                    assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, unknown.transportState)
                    assertEquals(1, unknown.transportAttemptCount)
                    assertEquals(1, unknown.handoffGeneration)
                    assertEquals(success.protectedPayloadText, unknown.protectedPayloadText)
                    assertArrayEquals(success.payloadSha256, unknown.payloadSha256)
                    assertEquals(1, submissions.size)
                    assertEquals(0, retrySchedules)
                    assertFalse(coordinator.sentResult(successId, 0, -1))
                    assertTrue(coordinator.sentResult(successId, 1, -1))
                    assertFalse(coordinator.sentResult(successId, 1, -1))
                    assertEquals(FallbackTransportState.HANDED_OFF,
                        dao.getByLocalAttemptId(successId)!!.transportState)

                    val retryId = dao.insert(validAttempt(2))
                    assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, coordinator.handoff(retryId))
                    now += 101
                    assertTrue(coordinator.recoverUncertain(retryId, 1))
                    assertTrue(coordinator.sentResult(retryId, 1, 1))
                    val pending = dao.getByLocalAttemptId(retryId)!!
                    assertEquals(FallbackTransportState.RETRY_PENDING, pending.transportState)
                    assertEquals(1, retrySchedules)
                    now = requireNotNull(pending.nextRetryAt)
                    assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, coordinator.handoff(retryId))
                    assertEquals(2, dao.getByLocalAttemptId(retryId)!!.transportAttemptCount)
                    assertEquals(2, dao.getByLocalAttemptId(retryId)!!.handoffGeneration)
                    assertEquals(submissions[1].exactPersistedText, submissions[2].exactPersistedText)
                    now += 101
                    assertTrue(coordinator.recoverUncertain(retryId, 2))
                    assertEquals(FallbackTransportState.UNKNOWN_OUTCOME,
                        dao.getByLocalAttemptId(retryId)!!.transportState)
                    assertFalse(coordinator.sentResult(retryId, 1, -1))
                    assertTrue(coordinator.handoff(retryId) is FallbackHandoffResult.NotReady)
                    assertEquals(3, submissions.size)
                    assertEquals(1, retrySchedules)

                    val exhaustedId = dao.insert(validAttempt(3))
                    coordinator.handoff(exhaustedId)
                    assertTrue(coordinator.sentResult(exhaustedId, 1, 1))
                    now = requireNotNull(dao.getByLocalAttemptId(exhaustedId)!!.nextRetryAt)
                    coordinator.handoff(exhaustedId)
                    assertTrue(coordinator.sentResult(exhaustedId, 2, 1))
                    assertEquals(FallbackTransportState.PERMANENT_FAILURE,
                        dao.getByLocalAttemptId(exhaustedId)!!.transportState)
                    assertEquals(FallbackTransportOutcome.RETRY_EXHAUSTED,
                        dao.getByLocalAttemptId(exhaustedId)!!.lastTransportOutcome)
                    assertTrue(coordinator.handoff(exhaustedId) is FallbackHandoffResult.NotReady)

                    val permanentId = dao.insert(validAttempt(4))
                    coordinator.handoff(permanentId)
                    now += 101
                    assertTrue(coordinator.recoverUncertain(permanentId, 1))
                    assertTrue(coordinator.sentResult(permanentId, 1, 6))
                    assertEquals(FallbackTransportState.PERMANENT_FAILURE,
                        dao.getByLocalAttemptId(permanentId)!!.transportState)
                    assertEquals(4, dao.allForJourney("test").size)
                    assertEquals(6, submissions.size)
                }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun persistUnknownForSeparateInstrumentationProcess() = runBlocking {
        val name = PROCESS_RESTART_DATABASE
        context.deleteDatabase(name)
        Room.databaseBuilder(context, JourneyDatabase::class.java, name)
            .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build().useDatabase { db ->
                db.journeyDao().insertIfNoActive(journey())
                val dao = db.fallbackAttemptDao()
                val id = dao.insert(validAttempt(1))
                var now = 1_000L
                var submissions = 0
                val coordinator = FallbackHandoffCoordinator(
                    RoomFallbackHandoffAttemptStore(dao),
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
                    clock = { now }, uncertaintyWindowMillis = 100,
                )
                assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, coordinator.handoff(id))
                now += 101
                assertTrue(coordinator.recoverUncertain(id, 1))
                assertEquals(1, submissions)
                assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, dao.getByLocalAttemptId(id)!!.transportState)
            }
    }

    @Test
    fun verifyUnknownAfterSeparateInstrumentationProcess() = runBlocking {
        val name = PROCESS_RESTART_DATABASE
        try {
            Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build().useDatabase { db ->
                    val dao = db.fallbackAttemptDao()
                    val attempt = dao.allForJourney("test").single()
                    assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, attempt.transportState)
                    assertEquals(1, attempt.transportAttemptCount)
                    assertEquals(1, attempt.handoffGeneration)
                    assertEquals(validAttempt(1).protectedPayloadText, attempt.protectedPayloadText)
                    assertArrayEquals(validAttempt(1).payloadSha256, attempt.payloadSha256)
                    assertNull(dao.getNextReadyAttempt("test", Long.MAX_VALUE))
                    val resumed = noSendCoordinator(dao)
                    repeat(3) {
                        assertFalse(resumed.recoverUncertain(attempt.localAttemptId, 1))
                        runFallbackHandoffWork(resumed, attempt.localAttemptId, 1)
                        runFallbackHandoffWork(resumed, attempt.localAttemptId, -1)
                        assertTrue(resumed.handoff(attempt.localAttemptId) is FallbackHandoffResult.NotReady)
                    }
                    assertEquals(1, dao.allForJourney("test").size)
                    assertEquals(1, dao.getByLocalAttemptId(attempt.localAttemptId)!!.handoffGeneration)
                }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun journey() = JourneyEntity("test", "Synthetic", 5_000, 100, JourneyStatus.ACTIVE, null, 1)

    private fun noSendCoordinator(dao: FallbackAttemptDao) = FallbackHandoffCoordinator(
        RoomFallbackHandoffAttemptStore(dao),
        object : SmsFallbackConfiguration {
            override fun status(): SmsFallbackStatus = error("Unknown must not resolve transport")
            override fun resolveForSend(): SmsTransportResolution = error("Unknown must not resolve transport")
            override fun selectSubscription(subscriptionId: Int) = false
        },
        object : SmsTelephonyGateway {
            override fun divideMessage(subscriptionId: Int, text: String): List<String> =
                error("Unknown must not divide or resend")
            override fun send(request: SmsHandoffRequest): Unit = error("No carrier send")
        },
        clock = { Long.MAX_VALUE },
    )

    private fun attempt() = FallbackAttemptEntity(
        journeyId = "test", degradationEpisodeId = 1, envelopeSequence = 1,
        telemetrySequence = 1, observationEventTime = 100, eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
        protectedPayloadText = SYNTHETIC_JC1, nonce = ByteArray(12),
        payloadSha256 = MessageDigest.getInstance("SHA-256").digest(SYNTHETIC_JC1.toByteArray()),
        allocatedAt = 200,
    )

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

    private fun insertV8Attempt(db: SupportSQLiteDatabase, id: Int, state: String, count: Int,
        outcome: String?, nextRetry: Int?, uncertainAt: Int) {
        val nonce = "%02x".format(id).repeat(12)
        val terminalAt = if (state in listOf("HANDED_OFF", "SUPERSEDED", "PERMANENT_FAILURE")) "300" else "NULL"
        val storedOutcome = outcome?.let { "'$it'" } ?: "NULL"
        db.execSQL("""INSERT INTO fallback_attempts (
            localAttemptId, journeyId, degradationEpisodeId, envelopeSequence,
            telemetrySequence, observationEventTime, eventType, protectedPayloadText,
            nonce, payloadSha256, allocatedAt, transportState, transportAttemptCount,
            lastAttemptAt, terminalAt, handoffGeneration, handoffStartedAt, nextRetryAt,
            lastTransportOutcome, lastTransportResultCode, uncertainSince)
            VALUES ($id, 'test', 1, $id, $id, 100, 'OBSERVATION', '$SYNTHETIC_JC1',
                X'$nonce', zeroblob(32), 200, '$state', $count, 250,
                $terminalAt, $count, 250,
                ${nextRetry ?: "NULL"}, $storedOutcome, NULL, $uncertainAt)""")
    }

    private fun fingerprint(db: SupportSQLiteDatabase, id: Int, columns: String = "*"): String {
        val digest = MessageDigest.getInstance("SHA-256")
        db.query("SELECT $columns FROM fallback_attempts WHERE localAttemptId = $id").use { row ->
            assertTrue(row.moveToFirst())
            repeat(row.columnCount) { index ->
                val bytes = when (row.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                    android.database.Cursor.FIELD_TYPE_BLOB -> row.getBlob(index)
                    else -> row.getString(index).toByteArray()
                }
                digest.update("${row.getType(index)}:${bytes.size}:".toByteArray())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SYNTHETIC_JC1 = "JC1.synthetic-test-frame"
        const val PROCESS_RESTART_DATABASE = "unknown-outcome-process-restart-test.db"
        const val IMMUTABLE_AND_COUNT_COLUMNS =
            "protectedPayloadText, nonce, envelopeSequence, payloadSha256, transportAttemptCount, handoffGeneration"
    }
}
