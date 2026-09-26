package com.journeycontinuity.app.degraded

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.local.FALLBACK_ATTEMPT_INVARIANT_CALLBACK
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.local.MIGRATION_2_3
import com.journeycontinuity.app.data.local.MIGRATION_3_4
import com.journeycontinuity.app.data.local.MIGRATION_4_5
import com.journeycontinuity.app.data.local.MIGRATION_5_6
import com.journeycontinuity.app.data.local.MIGRATION_6_7
import com.journeycontinuity.app.data.local.MIGRATION_7_8
import com.journeycontinuity.app.data.local.MIGRATION_8_9
import com.journeycontinuity.app.data.local.toDomain
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Staged physical acceptance probes. Never claims or sends an SMS. */
@RunWith(AndroidJUnit4::class)
class Milestone6TelephonyFailureMatrixTest {
    private suspend fun JourneyDatabase.use(block: suspend (JourneyDatabase) -> Unit) {
        try { block(this) } finally { close() }
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun database() = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db")
        .addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        )
        .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK)
        .build()

    private fun attemptFingerprint(database: JourneyDatabase, journeyId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        database.openHelper.readableDatabase.query(
            "SELECT * FROM fallback_attempts WHERE journeyId = ? ORDER BY envelopeSequence",
            arrayOf(journeyId),
        ).use { cursor ->
            while (cursor.moveToNext()) repeat(cursor.columnCount) { index ->
                val bytes = when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                    android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                    else -> cursor.getString(index).toByteArray()
                }
                digest.update("${cursor.getType(index)}:${bytes.size}:".toByteArray())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun testOnlyConfiguredRoute(): SmsFallbackConfiguration = object : SmsFallbackConfiguration {
        private val actual = AndroidSmsFallbackConfiguration(context, UnconfiguredSmsFallbackRouteProvider)
        override fun status(): SmsFallbackStatus {
            val snapshot = actual.status()
            return evaluateSmsFallbackStatus(
                snapshot.sendPermissionGranted, snapshot.phoneStatePermissionGranted,
                snapshot.telephonyMessagingSupported, snapshot.activeSubscriptions,
                snapshot.selectedSubscriptionId, destinationConfigured = true,
            )
        }
        override fun resolveForSend(): SmsTransportResolution {
            val snapshot = status()
            if (!snapshot.ready) return SmsTransportResolution.Unavailable(
                snapshot.unavailableReason ?: "SMS transport unavailable",
            )
            // Test-only placeholder. The fake gateway below cannot reach SmsManager.
            return SmsTransportResolution.Available(
                requireNotNull(snapshot.selectedSubscriptionId), SmsFallbackRoute("+" + "1".repeat(8)),
            )
        }
        override fun selectSubscription(subscriptionId: Int): Boolean = false
    }

    private class StopBeforeClaimGateway : SmsTelephonyGateway {
        var divisions = 0
        var observedSubscription: Int? = null
        var observedText: String? = null
        override fun divideMessage(subscriptionId: Int, text: String): List<String> {
            divisions += 1
            observedSubscription = subscriptionId
            observedText = text
            throw SecurityException("Test boundary before claim; no carrier handoff")
        }
        override fun send(request: SmsHandoffRequest): Unit = error("Carrier send is forbidden in this test")
    }

    /** Stage after controlled offline allocation, before the selected SIM is disabled. */
    @Test
    fun captureCaseDBaselineWithoutSending() = runBlocking {
        val configuration = AndroidSmsFallbackConfiguration(context, UnconfiguredSmsFallbackRouteProvider)
        val status = configuration.status()
        assertEquals(2, status.activeSubscriptions.size)
        assertNotNull(status.selectedSubscriptionId)
        assertTrue(status.activeSubscriptions.any { it.subscriptionId == status.selectedSubscriptionId })
        assertFalse(status.destinationConfigured)
        database().use { database ->
            val journey = requireNotNull(database.journeyDao().getActive())
            val state = requireNotNull(database.degradedConnectivityDao().get(journey.id))
            val attempts = database.fallbackAttemptDao().allForJourney(journey.id)
            val pending = attempts.last()
            assertEquals(ConnectivityPhase.DEGRADED, state.connectivityPhase)
            assertEquals(FallbackTransportState.ALLOCATED, pending.transportState)
            assertEquals(0, pending.handoffGeneration)
            assertEquals(0, pending.transportAttemptCount)
            assertEquals(null, pending.handoffStartedAt)
            val saved = context.getSharedPreferences("m6-case-d-evidence", Context.MODE_PRIVATE).edit()
                .putString("journey", journey.id)
                .putInt("selected", requireNotNull(status.selectedSubscriptionId))
                .putLong("attempt", pending.localAttemptId)
                .putInt("count", attempts.size)
                .putLong("nextEnvelope", state.nextFallbackEnvelopeSequence)
                .putLong("cloudSuccess", state.lastAuthenticatedCloudSuccessAtMillis ?: -1)
                .putString("attemptFingerprint", attemptFingerprint(database, journey.id))
                .commit()
            assertTrue(saved)
            Log.i(TAG, "case_d_baseline phase=${state.connectivityPhase} selected_active=true " +
                "attempt=${pending.localAttemptId} count=${attempts.size} " +
                "next_envelope=${state.nextFallbackEnvelopeSequence} no_sms=true")
        }
    }

    /** Stage only while HyperOS has disabled the selected SIM; never invokes SmsManager. */
    @Test
    fun selectedSimUnavailableFailsClosedWithoutSending() = runBlocking {
        val evidence = context.getSharedPreferences("m6-case-d-evidence", Context.MODE_PRIVATE)
        val selected = evidence.getInt("selected", -1)
        assertTrue(selected >= 0)
        val configuration = AndroidSmsFallbackConfiguration(context, UnconfiguredSmsFallbackRouteProvider)
        val status = configuration.status()
        assertEquals(selected, status.selectedSubscriptionId)
        assertEquals(1, status.activeSubscriptions.size)
        assertFalse(status.activeSubscriptions.any { it.subscriptionId == selected })
        assertEquals("The selected SIM is no longer active; reselect it.", status.unavailableReason)
        assertFalse(status.ready)
        assertTrue(configuration.resolveForSend() is SmsTransportResolution.Unavailable)
        database().use { database ->
            val journeyId = requireNotNull(evidence.getString("journey", null))
            val policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
            val keys = AndroidKeystoreFallbackKeyMaterialStore(context)
            val coordinator = DegradedConnectivityCoordinator(
                RoomDegradedConnectivityStateStore(
                    database, database.degradedConnectivityDao(), database.fallbackAttemptDao(),
                    DurableFallbackAttemptAllocator(
                        database.journeyDao(), database.telemetryDao(), database.degradedConnectivityDao(),
                        database.fallbackAttemptDao(), keys,
                    ), policy,
                ),
                latestTelemetryReader = { id -> database.telemetryDao().getLatest(id)?.toDomain() },
                fallbackCapabilityReader = { id ->
                    hasUsableFallbackBinding(database.fallbackAttemptDao().getBinding(id), keys)
                },
                policy = policy,
            )
            repeat(3) { coordinator.timeAdvanced(journeyId) }
            database.telemetryDao().getLatest(journeyId)?.let { observation ->
                coordinator.telemetryObserved(observation.toDomain())
            }
            val state = requireNotNull(database.degradedConnectivityDao().get(journeyId))
            val attempts = database.fallbackAttemptDao().allForJourney(journeyId)
            val pending = attempts.last()
            val fakeGateway = StopBeforeClaimGateway()
            val handoff = FallbackHandoffCoordinator(
                RoomFallbackHandoffAttemptStore(database.fallbackAttemptDao()),
                testOnlyConfiguredRoute(), fakeGateway,
            )
            assertTrue(handoff.processNextReady(journeyId) is FallbackHandoffResult.Unavailable)
            assertEquals(0, fakeGateway.divisions)
            assertEquals(ConnectivityPhase.DEGRADED, state.connectivityPhase)
            assertEquals(evidence.getInt("count", -1), attempts.size)
            assertEquals(evidence.getLong("attempt", -1), pending.localAttemptId)
            assertEquals(FallbackTransportState.ALLOCATED, pending.transportState)
            assertEquals(0, pending.handoffGeneration)
            assertEquals(0, pending.transportAttemptCount)
            assertEquals(null, pending.handoffStartedAt)
            assertEquals(evidence.getLong("nextEnvelope", -1), state.nextFallbackEnvelopeSequence)
            assertEquals(evidence.getLong("cloudSuccess", -1), state.lastAuthenticatedCloudSuccessAtMillis ?: -1)
            assertEquals(evidence.getString("attemptFingerprint", null), attemptFingerprint(database, journeyId))
            Log.i(TAG, "case_d_unavailable selected_active=false other_sim_active=true " +
                "attempt=${pending.localAttemptId} count=${attempts.size} " +
                "next_envelope=${state.nextFallbackEnvelopeSequence} exact_row_unchanged=true no_sms=true")
        }
    }

    /** Real restored subscription, production Room row, fake pre-claim telephony boundary. */
    @Test
    fun restoredSimMakesSameAttemptHandoffEligibleWithoutCarrierSend() = runBlocking {
        val evidence = context.getSharedPreferences("m6-case-d-evidence", Context.MODE_PRIVATE)
        val selected = evidence.getInt("selected", -1)
        val configured = testOnlyConfiguredRoute()
        val status = configured.status()
        assertEquals(selected, status.selectedSubscriptionId)
        assertEquals(2, status.activeSubscriptions.size)
        assertTrue(status.activeSubscriptions.any { it.subscriptionId == selected })
        assertTrue(status.ready)
        assertTrue(configured.resolveForSend() is SmsTransportResolution.Available)
        database().use { database ->
            val journeyId = requireNotNull(evidence.getString("journey", null))
            val pending = requireNotNull(
                RoomFallbackHandoffAttemptStore(database.fallbackAttemptDao())
                    .nextReady(journeyId, System.currentTimeMillis()),
            )
            assertEquals(evidence.getLong("attempt", -1), pending.localAttemptId)
            assertEquals(FallbackTransportState.ALLOCATED, pending.transportState)
            assertEquals(0, pending.handoffGeneration)
            assertEquals(0, pending.transportAttemptCount)
            val fakeGateway = StopBeforeClaimGateway()
            val handoff = FallbackHandoffCoordinator(
                RoomFallbackHandoffAttemptStore(database.fallbackAttemptDao()), configured, fakeGateway,
            )
            assertTrue(handoff.processNextReady(journeyId) is FallbackHandoffResult.Unavailable)
            assertEquals(1, fakeGateway.divisions)
            assertEquals(selected, fakeGateway.observedSubscription)
            assertEquals(pending.protectedPayloadText, fakeGateway.observedText)
            val state = requireNotNull(database.degradedConnectivityDao().get(journeyId))
            assertEquals(evidence.getInt("count", -1), database.fallbackAttemptDao().allForJourney(journeyId).size)
            assertEquals(evidence.getLong("nextEnvelope", -1), state.nextFallbackEnvelopeSequence)
            assertEquals(evidence.getString("attemptFingerprint", null), attemptFingerprint(database, journeyId))
            Log.i(TAG, "case_g_restored selected_active=true same_attempt=${pending.localAttemptId} " +
                "preclaim_eligible=true exact_payload_reused=true no_sms=true")
        }
    }

    @Test
    fun restoredSelectedSimRetainsPendingAttemptWithoutSending() = runBlocking {
        val evidence = context.getSharedPreferences("m6-case-d-evidence", Context.MODE_PRIVATE)
        val selected = evidence.getInt("selected", -1)
        val status = AndroidSmsFallbackConfiguration(context, UnconfiguredSmsFallbackRouteProvider).status()
        assertEquals(selected, status.selectedSubscriptionId)
        assertEquals(2, status.activeSubscriptions.size)
        assertTrue(status.activeSubscriptions.any { it.subscriptionId == selected })
        database().use { database ->
            val journeyId = requireNotNull(evidence.getString("journey", null))
            val state = requireNotNull(database.degradedConnectivityDao().get(journeyId))
            val attempts = database.fallbackAttemptDao().allForJourney(journeyId)
            assertEquals(ConnectivityPhase.DEGRADED, state.connectivityPhase)
            assertEquals(evidence.getInt("count", -1), attempts.size)
            assertEquals(evidence.getLong("nextEnvelope", -1), state.nextFallbackEnvelopeSequence)
            assertEquals(evidence.getString("attemptFingerprint", null), attemptFingerprint(database, journeyId))
            Log.i(TAG, "case_d_restored selected_active=true pending_attempt_preserved=true " +
                "attempt=${attempts.last().localAttemptId} next_envelope=${state.nextFallbackEnvelopeSequence} no_sms=true")
        }
    }

    @Test
    fun snapshotRoomAndSmsCapabilityWithoutSending() = runBlocking {
        val database = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            )
            .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK)
            .build()
        try {
            val journey = requireNotNull(database.journeyDao().getActive())
            val state = requireNotNull(database.degradedConnectivityDao().get(journey.id))
            val attempts = database.fallbackAttemptDao().allForJourney(journey.id)
            val latest = database.telemetryDao().getLatest(journey.id)
            val sync = database.syncStateDao().get(journey.id)
            val syncError = sync?.lastError.orEmpty()
            val status = AndroidSmsFallbackConfiguration(context, UnconfiguredSmsFallbackRouteProvider).status()
            val selectedActive = status.selectedSubscriptionId != null &&
                status.activeSubscriptions.any { it.subscriptionId == status.selectedSubscriptionId }
            assertTrue(status.sendPermissionGranted)
            assertTrue(status.phoneStatePermissionGranted)
            assertFalse(status.destinationConfigured)
            assertFalse(status.ready)
            assertEquals(FallbackTransportState.HANDED_OFF, attempts.single { it.localAttemptId == 2L }.transportState)
            Log.i(TAG, "phase=${state.connectivityPhase} disposition=${state.fallbackDisposition} " +
                "validated=${state.validatedInternetAvailable} episode=${state.degradationEpisodeId} " +
                "next_envelope=${state.nextFallbackEnvelopeSequence} latest_telemetry=${latest?.sequence} " +
                "last_cloud_success=${state.lastAuthenticatedCloudSuccessAtMillis} " +
                "last_covered_telemetry=${state.lastCoveredTelemetrySequence} " +
                "attempts=${attempts.size} ledger=${attempts.joinToString(",") {
                    "${it.localAttemptId}:${it.envelopeSequence}:${it.telemetrySequence}:${it.transportState}"
                }}")
            Log.i(TAG, "sync_checkpoint=${sync?.highestTelemetrySequenceSynced} " +
                "sync_phase=${sync?.phase} last_successful_sync=${sync?.lastSuccessfulSyncAt} " +
                "sync_blocked=${sync?.permanentlyBlocked} work_requested=${sync?.workRequested} " +
                "sync_error_category=${when {
                    syncError.isEmpty() -> "NONE"
                    syncError.contains("authorization", ignoreCase = true) -> "AUTHORIZATION"
                    syncError.contains("authentication", ignoreCase = true) -> "AUTHENTICATION"
                    syncError.contains("DNS", ignoreCase = true) -> "DNS"
                    syncError.contains("network", ignoreCase = true) -> "NETWORK"
                    syncError.contains("unexpected", ignoreCase = true) -> "UNEXPECTED"
                    else -> "OTHER"
                }}")
            Log.i(TAG, "sms_send_granted=${status.sendPermissionGranted} " +
                "phone_state_granted=${status.phoneStatePermissionGranted} " +
                "active_subscriptions=${status.activeSubscriptions.size} " +
                "selected_present=${status.selectedSubscriptionId != null} " +
                "selected_active=$selectedActive route_configured=${status.destinationConfigured} " +
                "ready=${status.ready}")
            attempts.lastOrNull()?.let { attempt ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(attempt.protectedPayloadText.toByteArray(Charsets.UTF_8))
                val nonceFingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(attempt.nonce).joinToString("") { "%02x".format(it) }
                assertTrue(MessageDigest.isEqual(attempt.payloadSha256, digest))
                Log.i(TAG, "latest_attempt_id=${attempt.localAttemptId} " +
                    "latest_envelope=${attempt.envelopeSequence} " +
                    "latest_state=${attempt.transportState} length=${attempt.protectedPayloadText.length} " +
                    "digest=${digest.joinToString("") { "%02x".format(it) }} " +
                    "nonce_fingerprint=$nonceFingerprint generation=${attempt.handoffGeneration} " +
                    "transport_attempts=${attempt.transportAttemptCount} " +
                    "handoff_started=${attempt.handoffStartedAt != null} " +
                    "full_ledger_fingerprint=${attemptFingerprint(database, journey.id)}")
            }
            assertNotNull(database.fallbackAttemptDao().getBinding(journey.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun dualSimSelectionFailsClosedWithoutChangingStoredSelection() {
        val configuration = AndroidSmsFallbackConfiguration(context, UnconfiguredSmsFallbackRouteProvider)
        val before = configuration.status()
        assertEquals(2, before.activeSubscriptions.size)
        assertNotNull(before.selectedSubscriptionId)
        assertFalse(configuration.selectSubscription(Int.MAX_VALUE))
        assertEquals(before.selectedSubscriptionId, configuration.status().selectedSubscriptionId)
        assertFalse(evaluateSmsFallbackStatus(
            true, true, true, before.activeSubscriptions, null, true,
        ).ready)
        assertFalse(evaluateSmsFallbackStatus(
            true, true, true, before.activeSubscriptions, Int.MAX_VALUE, true,
        ).ready)
        Log.i(TAG, "dual_sim_explicit_selection=true invalid_selection_rejected=true " +
            "stored_selection_unchanged=true no_default_fallback=true")
    }

    private companion object {
        const val TAG = "JC_M6_TELEPHONY_MATRIX"
    }
}
