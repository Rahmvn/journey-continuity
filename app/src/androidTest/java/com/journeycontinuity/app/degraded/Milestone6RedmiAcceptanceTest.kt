package com.journeycontinuity.app.degraded

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.auth.SharedPreferencesInstallationIdentityStore
import com.journeycontinuity.app.auth.SharedPreferencesTravellerIdentityStore
import com.journeycontinuity.app.auth.SupabaseTravellerAuthBackend
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.auth.TravellerIdentityStore
import com.journeycontinuity.app.data.local.FALLBACK_ATTEMPT_INVARIANT_CALLBACK
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.JourneyEntity
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.local.MIGRATION_2_3
import com.journeycontinuity.app.data.local.MIGRATION_3_4
import com.journeycontinuity.app.data.local.MIGRATION_4_5
import com.journeycontinuity.app.data.local.MIGRATION_5_6
import com.journeycontinuity.app.data.local.MIGRATION_6_7
import com.journeycontinuity.app.data.local.MIGRATION_7_8
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.heartbeat.HeartbeatAttemptResult
import com.journeycontinuity.app.heartbeat.HeartbeatCoordinator
import com.journeycontinuity.app.heartbeat.RoomHeartbeatLocalStore
import com.journeycontinuity.app.heartbeat.SupabaseHeartbeatGateway
import com.journeycontinuity.app.sync.ReliableSyncEngine
import com.journeycontinuity.app.sync.RoomLocalSyncStore
import com.journeycontinuity.app.sync.SupabaseCloudSyncGateway
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Milestone6RedmiAcceptanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun freshIdentityProvisioningAndHealthyBaseline() = runBlocking {
        val token = requireNotNull(
            InstrumentationRegistry.getArguments().getString(OWNER_TOKEN_ARGUMENT),
        ) { "Controlled owner token argument is required." }
        val installationStore = SharedPreferencesInstallationIdentityStore(context)
        val installationId = installationStore.getOrCreate()
        UUID.fromString(installationId)
        assertEquals(installationId, installationStore.getOrCreate())

        val client = authenticatedClient(token)
        val ownerId = requireNotNull(client.auth.currentSessionOrNull()?.user?.id)
        val productionIdentityStore = SharedPreferencesTravellerIdentityStore(context)
        val establishedOwner = productionIdentityStore.expectedTravellerUserId()
        require(establishedOwner == null || establishedOwner == ownerId) {
            "Controlled acceptance owner does not match the installation's established traveller."
        }
        if (establishedOwner == null) {
            assertTrue(productionIdentityStore.persistExpectedTravellerUserIdIfAbsent(ownerId))
        }
        val identity = TravellerIdentityCoordinator(
            SupabaseTravellerAuthBackend(client),
            productionIdentityStore,
        )
        val database = database()
        val now = System.currentTimeMillis()
        val existingJourney = database.journeyDao().getActive()
        val journeyId = existingJourney?.id ?: UUID.randomUUID().toString()
        if (existingJourney == null) {
            assertTrue(database.journeyDao().insertIfNoActive(
                JourneyEntity(
                    id = journeyId,
                    destination = "Milestone 6 fresh Redmi acceptance",
                    expectedArrivalAt = now + 4 * 60 * 60_000L,
                    startedAt = now,
                    status = JourneyStatus.ACTIVE,
                    completedAt = null,
                    activeSlot = 1,
                ),
            ))
        } else {
            assertEquals("Milestone 6 fresh Redmi acceptance", existingJourney.destination)
        }
        val cloud = SupabaseCloudSyncGateway(client, identity)
        cloud.upsertJourney(requireNotNull(database.journeyDao().getById(journeyId)).toDomain(), ownerId)

        val keyStore = AndroidKeystoreFallbackKeyMaterialStore(context)
        val gateway = SupabaseFallbackProvisioningGateway(
            client = client,
            identityCoordinator = identity,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
        val coordinator = FallbackProvisioningCoordinator(
            localStore = RoomFallbackProvisioningLocalStore(database, database.fallbackAttemptDao()),
            installationIdentityStore = installationStore,
            keyMaterialStore = keyStore,
            gateway = gateway,
        )
        val initialMaterial = gateway.provision(journeyId, installationId)
        try {
            keyStore.provision(initialMaterial.keyId, initialMaterial.installationMasterKey)
            assertFalse(hasUsableFallbackBinding(null, keyStore))
        } finally {
            initialMaterial.installationMasterKey.fill(0)
        }
        assertEquals(FallbackProvisioningResult.Provisioned, coordinator.provisionIfEligible(journeyId))
        val binding = requireNotNull(database.fallbackAttemptDao().getBinding(journeyId))
        assertTrue(keyStore.hasKey(binding.keyId))
        assertTrue(hasUsableFallbackBinding(binding, keyStore))

        val repeatOne = gateway.provision(journeyId, installationId)
        val repeatTwo = gateway.provision(journeyId, installationId)
        try {
            assertEquals(binding.keyId, repeatOne.keyId)
            assertEquals(repeatOne.keyId, repeatTwo.keyId)
            assertArrayEquals(binding.journeyHandle, repeatOne.journeyHandle)
            assertArrayEquals(repeatOne.journeyHandle, repeatTwo.journeyHandle)
            assertTrue(
                keyStore.useKey(binding.keyId) { wrapped ->
                    MessageDigest.isEqual(wrapped, repeatOne.installationMasterKey)
                } == true,
            )
            assertTrue(MessageDigest.isEqual(repeatOne.installationMasterKey, repeatTwo.installationMasterKey))
        } finally {
            repeatOne.installationMasterKey.fill(0)
            repeatTwo.installationMasterKey.fill(0)
        }

        val observation = requireNotNull(
            database.telemetryDao().insertForActiveJourney(
                TelemetrySample(
                    journeyId = journeyId,
                    eventTime = now,
                    latitude = 6.5244,
                    longitude = 3.3792,
                    accuracyMeters = 8.0f,
                    batteryPercent = 80,
                    isCharging = false,
                    connectivity = ConnectivityState.WIFI,
                ),
            ),
        )
        val degraded = coordinator(database, keyStore)
        degraded.activate(journeyId, validatedInternetAvailable = true)
        degraded.telemetryObserved(observation.toDomain())
        assertEquals(com.journeycontinuity.app.sync.SyncRunResult.Success, ReliableSyncEngine(
            RoomLocalSyncStore(database.journeyDao(), database.telemetryDao(), database.syncStateDao()),
            SupabaseCloudSyncGateway(client, identity),
        ).synchronize())
        degraded.timeAdvanced(journeyId)
        val barrier = requireNotNull(database.degradedConnectivityDao().get(journeyId)?.recoveryBacklogSatisfiedAtMillis)
        while (System.currentTimeMillis() <= barrier) kotlinx.coroutines.delay(1)
        val heartbeatStartedAt = System.currentTimeMillis()
        val heartbeat = HeartbeatCoordinator(
            RoomHeartbeatLocalStore(database.heartbeatDao()),
            SupabaseHeartbeatGateway(client, identity),
        ).sendFreshHeartbeat(
            journeyId = journeyId,
            batteryPercent = 80,
            charging = false,
            connectivity = ConnectivityState.WIFI,
            networkUsable = true,
        )
        assertEquals(HeartbeatAttemptResult.Sent, heartbeat)
        degraded.freshHeartbeatSucceeded(journeyId, heartbeatStartedAt)
        assertEquals(
            ConnectivityPhase.HEALTHY,
            requireNotNull(database.degradedConnectivityDao().get(journeyId)).connectivityPhase,
        )

        acceptancePreferences().edit()
            .putString(JOURNEY_ID_KEY, journeyId)
            .putString(INSTALLATION_ID_KEY, installationId)
            .putLong(KEY_ID_KEY, binding.keyId)
            .putString(HANDLE_DIGEST_KEY, sha256Hex(binding.journeyHandle))
            .commit()
            .also(::assertTrue)
        println("JC_ACCEPTANCE installation_id=$installationId journey_id=$journeyId key_id=${binding.keyId}")
        database.close()
        client.close()
    }

    @Test
    fun processRestartRetainsIdentityBindingAndKeystoreCapability() = runBlocking {
        val preferences = acceptancePreferences()
        val journeyId = requireNotNull(preferences.getString(JOURNEY_ID_KEY, null))
        val installationId = requireNotNull(preferences.getString(INSTALLATION_ID_KEY, null))
        assertEquals(installationId, SharedPreferencesInstallationIdentityStore(context).getOrCreate())
        val database = database()
        val binding = requireNotNull(database.fallbackAttemptDao().getBinding(journeyId))
        assertEquals(preferences.getLong(KEY_ID_KEY, -1), binding.keyId)
        assertEquals(preferences.getString(HANDLE_DIGEST_KEY, null), sha256Hex(binding.journeyHandle))
        val keyStore = AndroidKeystoreFallbackKeyMaterialStore(context)
        assertTrue(keyStore.hasKey(binding.keyId))
        assertTrue(hasUsableFallbackBinding(binding, keyStore))
        database.close()
    }

    @Test
    fun validatedInternetLossBecomesInterrupted() = runBlocking {
        val journeyId = requireNotNull(acceptancePreferences().getString(JOURNEY_ID_KEY, null))
        val database = database()
        coordinator(database, AndroidKeystoreFallbackKeyMaterialStore(context))
            .validatedInternetLost(journeyId)
        assertEquals(
            ConnectivityPhase.INTERRUPTED,
            requireNotNull(database.degradedConnectivityDao().get(journeyId)).connectivityPhase,
        )
        database.close()
    }

    @Test
    fun elapsedOfflinePeriodAllocatesOneDurableProductionEnvelope() = runBlocking {
        val journeyId = requireNotNull(acceptancePreferences().getString(JOURNEY_ID_KEY, null))
        var database = database()
        val now = System.currentTimeMillis()
        val observation = requireNotNull(
            database.telemetryDao().insertForActiveJourney(
                TelemetrySample(
                    journeyId = journeyId,
                    eventTime = now,
                    latitude = 6.5245,
                    longitude = 3.3793,
                    accuracyMeters = 9.0f,
                    batteryPercent = 79,
                    isCharging = false,
                    connectivity = ConnectivityState.NONE,
                ),
            ),
        )
        val degraded = coordinator(database, AndroidKeystoreFallbackKeyMaterialStore(context))
        degraded.telemetryObserved(observation.toDomain())
        degraded.timeAdvanced(journeyId)
        val state = requireNotNull(database.degradedConnectivityDao().get(journeyId))
        assertEquals(ConnectivityPhase.DEGRADED, state.connectivityPhase)
        assertEquals(FallbackDisposition.ALLOCATED, state.fallbackDisposition)
        val attempts = database.fallbackAttemptDao().allForJourney(journeyId)
        assertEquals(1, attempts.size)
        val attempt = attempts.single()
        assertEquals(FallbackTransportState.ALLOCATED, attempt.transportState)
        assertFalse(attempt.transportState == FallbackTransportState.HANDED_OFF)
        assertEquals(observation.sequence, attempt.telemetrySequence)
        assertEquals(state.nextFallbackEnvelopeSequence - 1, attempt.envelopeSequence)
        assertEquals(FallbackEnvelopeV1.FRAME_BYTES, decodeFrameBytes(attempt.protectedPayloadText).size)
        assertArrayEquals(
            AesGcmEnvelopeProtector.sha256(attempt.protectedPayloadText.toByteArray(Charsets.UTF_8)),
            attempt.payloadSha256,
        )
        val persistedText = attempt.protectedPayloadText
        val persistedDigest = attempt.payloadSha256.copyOf()
        database.close()

        database = database()
        val reloaded = database.fallbackAttemptDao().allForJourney(journeyId).single()
        assertEquals(persistedText, reloaded.protectedPayloadText)
        assertArrayEquals(persistedDigest, reloaded.payloadSha256)
        acceptancePreferences().edit()
            .putString(ENVELOPE_DIGEST_KEY, sha256Hex(persistedDigest))
            .commit()
            .also(::assertTrue)
        database.close()
    }

    @Test
    fun processRestartRetainsExactAllocatedEnvelope() = runBlocking {
        val preferences = acceptancePreferences()
        val journeyId = requireNotNull(preferences.getString(JOURNEY_ID_KEY, null))
        val expectedDigest = requireNotNull(preferences.getString(ENVELOPE_DIGEST_KEY, null))
        val database = database()
        val attempt = database.fallbackAttemptDao().allForJourney(journeyId).single()
        assertEquals(FallbackTransportState.ALLOCATED, attempt.transportState)
        assertEquals(FallbackEnvelopeV1.FRAME_BYTES, decodeFrameBytes(attempt.protectedPayloadText).size)
        assertArrayEquals(
            AesGcmEnvelopeProtector.sha256(attempt.protectedPayloadText.toByteArray(Charsets.UTF_8)),
            attempt.payloadSha256,
        )
        assertEquals(expectedDigest, sha256Hex(attempt.payloadSha256))
        database.close()
    }

    @Test
    fun restoredInternetSyncsHeartbeatsAndSupersedesObsoleteAttempt() = runBlocking {
        val token = requireNotNull(
            InstrumentationRegistry.getArguments().getString(OWNER_TOKEN_ARGUMENT),
        ) { "Controlled owner token argument is required." }
        val journeyId = requireNotNull(acceptancePreferences().getString(JOURNEY_ID_KEY, null))
        val client = authenticatedClient(token)
        val ownerId = requireNotNull(client.auth.currentSessionOrNull()?.user?.id)
        val identity = TravellerIdentityCoordinator(
            SupabaseTravellerAuthBackend(client),
            FixedIdentityStore(ownerId),
        )
        val database = database()
        val keyStore = AndroidKeystoreFallbackKeyMaterialStore(context)
        val degraded = coordinator(database, keyStore)
        degraded.validatedInternetAvailable(journeyId)
        assertEquals(
            ConnectivityPhase.RECOVERING,
            requireNotNull(database.degradedConnectivityDao().get(journeyId)).connectivityPhase,
        )
        assertEquals(
            com.journeycontinuity.app.sync.SyncRunResult.Success,
            ReliableSyncEngine(
                RoomLocalSyncStore(
                    database.journeyDao(),
                    database.telemetryDao(),
                    database.syncStateDao(),
                ),
                SupabaseCloudSyncGateway(client, identity),
            ).synchronize(),
        )
        degraded.timeAdvanced(journeyId)
        val barrier = requireNotNull(database.degradedConnectivityDao().get(journeyId)?.recoveryBacklogSatisfiedAtMillis)
        while (System.currentTimeMillis() <= barrier) kotlinx.coroutines.delay(1)
        val heartbeatStartedAt = System.currentTimeMillis()
        val heartbeat = HeartbeatCoordinator(
            RoomHeartbeatLocalStore(database.heartbeatDao()),
            SupabaseHeartbeatGateway(client, identity),
        ).sendFreshHeartbeat(
            journeyId = journeyId,
            batteryPercent = 78,
            charging = false,
            connectivity = ConnectivityState.WIFI,
            networkUsable = true,
        )
        assertEquals(HeartbeatAttemptResult.Sent, heartbeat)
        degraded.freshHeartbeatSucceeded(journeyId, heartbeatStartedAt)
        assertEquals(
            ConnectivityPhase.HEALTHY,
            requireNotNull(database.degradedConnectivityDao().get(journeyId)).connectivityPhase,
        )
        assertEquals(
            FallbackTransportState.SUPERSEDED,
            database.fallbackAttemptDao().allForJourney(journeyId).single().transportState,
        )
        assertTrue(
            hasUsableFallbackBinding(
                database.fallbackAttemptDao().getBinding(journeyId),
                keyStore,
            ),
        )
        database.close()
        client.close()
    }

    private suspend fun authenticatedClient(token: String) = createSupabaseClient(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    ) {
        install(Auth) {
            autoLoadFromStorage = false
            autoSaveToStorage = true
            alwaysAutoRefresh = false
        }
        install(Postgrest)
    }.also { client ->
        client.auth.importAuthToken(token, retrieveUser = true, autoRefresh = false)
        val imported = requireNotNull(client.auth.currentSessionOrNull())
        client.auth.importSession(
            imported.copy(
                expiresIn = 1.hours.inWholeSeconds,
                expiresAt = Clock.System.now() + 1.hours,
            ),
            autoRefresh = false,
        )
    }

    private fun database() = Room.databaseBuilder(
        context,
        JourneyDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
    ).addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()

    private fun coordinator(
        database: JourneyDatabase,
        keyStore: FallbackKeyMaterialStore,
    ): DegradedConnectivityCoordinator {
        val policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
        return DegradedConnectivityCoordinator(
            store = RoomDegradedConnectivityStateStore(
                database,
                database.degradedConnectivityDao(),
                database.fallbackAttemptDao(),
                DurableFallbackAttemptAllocator(
                    database.journeyDao(),
                    database.telemetryDao(),
                    database.degradedConnectivityDao(),
                    database.fallbackAttemptDao(),
                    keyStore,
                ),
                policy,
            ),
            latestTelemetryReader = { journeyId ->
                database.telemetryDao().getLatest(journeyId)?.toDomain()
            },
            fallbackCapabilityReader = FallbackCapabilityReader { journeyId ->
                hasUsableFallbackBinding(database.fallbackAttemptDao().getBinding(journeyId), keyStore)
            },
            policy = policy,
        )
    }

    private fun acceptancePreferences() =
        context.getSharedPreferences(ACCEPTANCE_PREFERENCES, Context.MODE_PRIVATE)

    private fun decodeFrameBytes(value: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(value.removePrefix(FallbackEnvelopeV1.TEXT_PREFIX))

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private class FixedIdentityStore(private val ownerId: String) : TravellerIdentityStore {
        override fun expectedTravellerUserId(): String = ownerId
        override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean = userId == ownerId
    }

    private companion object {
        const val OWNER_TOKEN_ARGUMENT = "owner_token"
        const val DATABASE_NAME = "journey-continuity.db"
        const val ACCEPTANCE_PREFERENCES = "milestone-6-redmi-acceptance"
        const val JOURNEY_ID_KEY = "journeyId"
        const val INSTALLATION_ID_KEY = "installationId"
        const val KEY_ID_KEY = "keyId"
        const val HANDLE_DIGEST_KEY = "handleDigest"
        const val ENVELOPE_DIGEST_KEY = "envelopeDigest"
    }
}
