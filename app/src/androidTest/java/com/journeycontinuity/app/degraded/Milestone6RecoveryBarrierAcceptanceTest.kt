package com.journeycontinuity.app.degraded

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.auth.*
import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.heartbeat.*
import com.journeycontinuity.app.sync.*
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly selected stages on the existing installation. No SMS transport is constructed.
 * Run each stage in a separate instrumentation process to exercise durable restart boundaries.
 */
@RunWith(AndroidJUnit4::class)
class Milestone6RecoveryBarrierAcceptanceTest {
    private suspend fun JourneyDatabase.use(block: suspend (JourneyDatabase) -> Unit) {
        try { block(this) } finally { close() }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = context.getSharedPreferences("m6-recovery-barrier-evidence", Context.MODE_PRIVATE)
    private fun database() = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
        .addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()

    private fun coordinator(db: JourneyDatabase): DegradedConnectivityCoordinator {
        val keys = AndroidKeystoreFallbackKeyMaterialStore(context)
        val policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
        return DegradedConnectivityCoordinator(
            RoomDegradedConnectivityStateStore(db, db.degradedConnectivityDao(), db.fallbackAttemptDao(),
                DurableFallbackAttemptAllocator(db.journeyDao(), db.telemetryDao(),
                    db.degradedConnectivityDao(), db.fallbackAttemptDao(), keys), policy),
            latestTelemetryReader = { db.telemetryDao().getLatest(it)?.toDomain() },
            fallbackCapabilityReader = { hasUsableFallbackBinding(db.fallbackAttemptDao().getBinding(it), keys) },
            policy = policy,
        )
    }

    private fun validated(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    // Hash every column, including payload, timestamps, callback state and handoff generation.
    private fun fingerprint(db: JourneyDatabase, table: String, where: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        db.openHelper.readableDatabase.query("SELECT * FROM $table WHERE $where ORDER BY rowid").use { cursor ->
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

    private fun assertHistory(db: JourneyDatabase) {
        assertEquals(evidence.getString("attempt9", null), fingerprint(db, "fallback_attempts", "localAttemptId = 9"))
        assertEquals(evidence.getString("handedOff", null), fingerprint(db, "fallback_attempts", "transportState = 'HANDED_OFF'"))
        assertEquals(evidence.getString("binding", null), fingerprint(db, "journey_fallback_bindings", "1 = 1"))
    }

    @Test fun snapshotBeforeControlledEpisode() = runBlocking {
        database().use { db ->
            val id = requireNotNull(db.journeyDao().getActive()).id
            val attempt9 = requireNotNull(db.fallbackAttemptDao().getByLocalAttemptId(9))
            assertEquals(FallbackTransportState.SUPERSEDED, attempt9.transportState)
            assertNull(attempt9.handoffStartedAt)
            assertEquals(7L, attempt9.degradationEpisodeId)
            assertEquals(1, db.fallbackAttemptDao().allForJourney(id).count { it.degradationEpisodeId == 7L })
            val sync = requireNotNull(db.syncStateDao().get(id))
            assertTrue(evidence.edit().clear()
                .putString("journey", id)
                .putString("attempt9", fingerprint(db, "fallback_attempts", "localAttemptId = 9"))
                .putString("handedOff", fingerprint(db, "fallback_attempts", "transportState = 'HANDED_OFF'"))
                .putString("binding", fingerprint(db, "journey_fallback_bindings", "1 = 1"))
                .putLong("checkpointBefore", sync.highestTelemetrySequenceSynced)
                .putInt("attemptsBefore", db.fallbackAttemptDao().allForJourney(id).size).commit())
            Log.i(TAG, "baseline checkpoint=${sync.highestTelemetrySequenceSynced} blocked=${sync.permanentlyBlocked} " +
                "latest=${db.telemetryDao().getLatest(id)?.sequence} phase=${db.degradedConnectivityDao().get(id)?.connectivityPhase} attempt9_immutable=true")
        }
    }

    @Test fun controlledOfflineEpisodeWithoutSms() = runBlocking {
        assertFalse("Disable device data and Wi-Fi before this stage", validated())
        database().use { db ->
            val id = requireNotNull(evidence.getString("journey", null))
            assertHistory(db)
            val coordinator = coordinator(db)
            coordinator.activate(id, false)
            coordinator.validatedInternetLost(id)
            val deadline = System.currentTimeMillis() + DegradedConnectivityLabConfiguration.DEGRADATION_AFTER_MILLIS + 5_000
            while (db.degradedConnectivityDao().get(id)?.connectivityPhase != ConnectivityPhase.DEGRADED) {
                assertTrue(System.currentTimeMillis() < deadline)
                assertFalse(validated())
                delay(1_000)
                coordinator.timeAdvanced(id)
            }
            val attempts = db.fallbackAttemptDao().allForJourney(id)
            assertEquals(evidence.getInt("attemptsBefore", -1) + 1, attempts.size)
            val attempt = attempts.last()
            assertEquals(FallbackTransportState.ALLOCATED, attempt.transportState)
            assertNull(attempt.handoffStartedAt)
            assertTrue(evidence.edit().putLong("newAttempt", attempt.localAttemptId).putInt("attemptCount", attempts.size).commit())
            assertHistory(db)
            Log.i(TAG, "phase=DEGRADED new_attempt=${attempt.localAttemptId} no_sms=true")
        }
    }

    @Test fun captureTargetAndRejectEarlyAuthenticatedHeartbeat() = runBlocking {
        assertTrue(validated())
        database().use { db ->
            val id = requireNotNull(evidence.getString("journey", null))
            val coordinator = coordinator(db)
            coordinator.activate(id, true)
            val state = requireNotNull(db.degradedConnectivityDao().get(id))
            val target = requireNotNull(state.recoveryTargetTelemetrySequence)
            assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)
            assertTrue(requireNotNull(db.syncStateDao().get(id)).highestTelemetrySequenceSynced < target)
            assertNull(state.recoveryBacklogSatisfiedAtMillis)
            assertTrue(evidence.edit().putLong("target", target).commit())
            withOwner(db) { gateway, heartbeat ->
                assertTrue(gateway.verifyJourneyOwner(id, gateway.authenticatedOwnerId()))
                val started = System.currentTimeMillis()
                assertEquals(HeartbeatAttemptResult.Sent, heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true))
                coordinator.freshHeartbeatSucceeded(id, started)
            }
            assertEquals(ConnectivityPhase.RECOVERING, db.degradedConnectivityDao().get(id)?.connectivityPhase)
            assertUnsentAndNoReplay(db, id)
            Log.i(TAG, "phase=RECOVERING target=$target early_authenticated_heartbeat_rejected=true")
        }
    }

    @Test fun synchronizeThroughPersistedTargetAfterProcessRestart() = runBlocking {
        assertTrue(validated())
        database().use { db ->
            val id = requireNotNull(evidence.getString("journey", null))
            val target = evidence.getLong("target", -1)
            val coordinator = coordinator(db)
            coordinator.activate(id, true)
            assertEquals(target, db.degradedConnectivityDao().get(id)?.recoveryTargetTelemetrySequence)
            assertUnsentAndNoReplay(db, id)
            withOwner(db) { gateway, _ ->
                assertEquals(SyncRunResult.Success, ReliableSyncEngine(
                    RoomLocalSyncStore(db.journeyDao(), db.telemetryDao(), db.syncStateDao()), gateway,
                    logger = AndroidSyncDiagnosticLogger,
                ).synchronize())
            }
            val sync = requireNotNull(db.syncStateDao().get(id))
            assertTrue(sync.highestTelemetrySequenceSynced >= target)
            assertFalse(sync.permanentlyBlocked)
            assertNotNull(sync.legacyAuthorizationFailure)
            assertNotNull(sync.legacyAuthorizationRecoveredAtMillis)
            coordinator.timeAdvanced(id)
            val state = requireNotNull(db.degradedConnectivityDao().get(id))
            assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)
            val barrier = requireNotNull(state.recoveryBacklogSatisfiedAtMillis)
            assertTrue(evidence.edit().putLong("barrier", barrier).commit())
            assertUnsentAndNoReplay(db, id)
            Log.i(TAG, "phase=RECOVERING target=$target checkpoint=${sync.highestTelemetrySequenceSynced} barrier=$barrier legacy_reactivated=true")
        }
    }

    @Test fun freshHeartbeatAfterBarrierAndProcessRestartCompletesRecovery() = runBlocking {
        assertTrue(validated())
        database().use { db ->
            val id = requireNotNull(evidence.getString("journey", null))
            val coordinator = coordinator(db)
            coordinator.activate(id, true)
            val state = requireNotNull(db.degradedConnectivityDao().get(id))
            assertEquals(evidence.getLong("target", -1), state.recoveryTargetTelemetrySequence)
            assertEquals(evidence.getLong("barrier", -1), state.recoveryBacklogSatisfiedAtMillis)
            assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)
            assertUnsentAndNoReplay(db, id)
            val started = System.currentTimeMillis()
            assertTrue(started > requireNotNull(state.recoveryBacklogSatisfiedAtMillis))
            withOwner(db) { _, heartbeat ->
                assertEquals(HeartbeatAttemptResult.Sent, heartbeat.sendFreshHeartbeat(id, null, null, ConnectivityState.WIFI, true))
                coordinator.freshHeartbeatSucceeded(id, started)
            }
            assertEquals(ConnectivityPhase.HEALTHY, db.degradedConnectivityDao().get(id)?.connectivityPhase)
            assertEquals(FallbackTransportState.SUPERSEDED,
                db.fallbackAttemptDao().getByLocalAttemptId(evidence.getLong("newAttempt", -1))?.transportState)
            assertHistory(db)
            assertEquals(evidence.getInt("attemptCount", -1), db.fallbackAttemptDao().allForJourney(id).size)
            Log.i(TAG, "phase=HEALTHY heartbeat_started=$started barrier=${state.recoveryBacklogSatisfiedAtMillis} " +
                "checkpoint=${db.syncStateDao().get(id)?.highestTelemetrySequenceSynced} target=${state.recoveryTargetTelemetrySequence} " +
                "attempt9_unchanged=true handed_off_unchanged=true binding_unchanged=true no_sms=true")
        }
    }

    @Test fun verifyProductionServiceCompletedRecovery() = runBlocking {
        database().use { db ->
            val id = requireNotNull(evidence.getString("journey", null))
            val state = requireNotNull(db.degradedConnectivityDao().get(id))
            val barrier = evidence.getLong("barrier", -1)
            val target = evidence.getLong("target", -1)
            assertEquals(target, state.recoveryTargetTelemetrySequence)
            assertEquals(barrier, state.recoveryBacklogSatisfiedAtMillis)
            assertEquals(ConnectivityPhase.HEALTHY, state.connectivityPhase)
            val heartbeat = requireNotNull(db.heartbeatDao().observe(id).first())
            assertTrue(requireNotNull(heartbeat.lastHeartbeatAttemptAt) > barrier)
            assertTrue(requireNotNull(heartbeat.lastSuccessfulHeartbeatAt) > barrier)
            assertEquals(heartbeat.lastAllocatedHeartbeatSequence, heartbeat.latestCloudHeartbeatSequence)
            val sync = requireNotNull(db.syncStateDao().get(id))
            assertTrue(sync.highestTelemetrySequenceSynced >= target)
            assertFalse(sync.permanentlyBlocked)
            assertEquals(FallbackTransportState.SUPERSEDED,
                db.fallbackAttemptDao().getByLocalAttemptId(evidence.getLong("newAttempt", -1))?.transportState)
            assertEquals(evidence.getInt("attemptCount", -1), db.fallbackAttemptDao().allForJourney(id).size)
            assertHistory(db)
            val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
                install(Auth)
                install(Postgrest)
            }
            try {
                TravellerIdentityCoordinator(SupabaseTravellerAuthBackend(client), SharedPreferencesTravellerIdentityStore(context))
                    .requireAuthenticatedTraveller()
                var after = 0L
                var verified = 0
                while (after < target) {
                    val local = db.telemetryDao().getAfterSequence(id, after, 500).filter { it.sequence <= target }
                    assertTrue(local.isNotEmpty())
                    val through = local.last().sequence
                    val hosted = client.from("telemetry_observations").select(Columns.list("sequence")) {
                        filter {
                            eq("journey_id", id)
                            and { gt("sequence", after); lte("sequence", through) }
                        }
                    }.decodeList<HostedSequence>().map { it.sequence }.sorted()
                    assertTrue("Hosted range $after..$through must match all ${local.size} local observations",
                        local.map { it.sequence } == hosted)
                    verified += hosted.size
                    after = through
                }
                Log.i(TAG, "production_service_recovery=HEALTHY target=$target checkpoint=${sync.highestTelemetrySequenceSynced} " +
                    "barrier=$barrier heartbeat_started=${heartbeat.lastHeartbeatAttemptAt} " +
                    "hosted_sequences_verified=$verified missing=0 immutable_history=true no_sms=true")
            } finally { client.close() }
        }
    }

    @Serializable private data class HostedSequence(val sequence: Long)

    private suspend fun assertUnsentAndNoReplay(db: JourneyDatabase, id: String) {
        assertHistory(db)
        assertEquals(evidence.getInt("attemptCount", -1), db.fallbackAttemptDao().allForJourney(id).size)
        assertEquals(FallbackTransportState.ALLOCATED,
            db.fallbackAttemptDao().getByLocalAttemptId(evidence.getLong("newAttempt", -1))?.transportState)
    }

    private suspend fun withOwner(db: JourneyDatabase, block: suspend (SupabaseCloudSyncGateway, HeartbeatCoordinator) -> Unit) {
        val store = SharedPreferencesTravellerIdentityStore(context)
        requireNotNull(store.expectedTravellerUserId()) // Never bootstrap a new anonymous identity here.
        val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            install(Auth)
            install(Postgrest)
        }
        try {
            val identity = TravellerIdentityCoordinator(SupabaseTravellerAuthBackend(client), store)
            identity.requireAuthenticatedTraveller()
            block(SupabaseCloudSyncGateway(client, identity), HeartbeatCoordinator(
                RoomHeartbeatLocalStore(db.heartbeatDao()), SupabaseHeartbeatGateway(client, identity)))
        } finally { client.close() }
    }

    private companion object { const val TAG = "JC_M6_RECOVERY_BARRIER" }
}
