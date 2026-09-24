package com.journeycontinuity.app.degraded

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.auth.SharedPreferencesTravellerIdentityStore
import com.journeycontinuity.app.auth.SupabaseTravellerAuthBackend
import com.journeycontinuity.app.auth.TravellerAuthBackend
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.auth.TravellerIdentityStore
import com.journeycontinuity.app.auth.TravellerSessionState
import com.journeycontinuity.app.data.local.FALLBACK_ATTEMPT_INVARIANT_CALLBACK
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.local.MIGRATION_2_3
import com.journeycontinuity.app.data.local.MIGRATION_3_4
import com.journeycontinuity.app.data.local.MIGRATION_4_5
import com.journeycontinuity.app.data.local.MIGRATION_5_6
import com.journeycontinuity.app.data.local.MIGRATION_6_7
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.domain.ConnectivityState
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
import io.github.jan.supabase.postgrest.from
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Milestone6SmsCarrierAcceptanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roomV7ProvisionedJourneyPreflight() = runBlocking {
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive()) {
            "A legitimately hosted-provisioned active Journey is required."
        }
        assertEquals(7, database.openHelper.readableDatabase.version)
        val binding = requireNotNull(database.fallbackAttemptDao().getBinding(activeJourney.id))
        assertEquals(FallbackBindingStatus.PROVISIONED, binding.status)
        assertTrue(AndroidKeystoreFallbackKeyMaterialStore(context).hasKey(binding.keyId))
        val state = requireNotNull(database.degradedConnectivityDao().get(activeJourney.id))
        val attempts = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        evidence(
            "JC_SMS_PREFLIGHT room_version=7 phase=${state.connectivityPhase} " +
                "binding=ACTIVE attempts=${attempts.size} " +
                "allocated=${attempts.count { it.transportState == FallbackTransportState.ALLOCATED }} " +
                "handed_off=${attempts.count { it.transportState == FallbackTransportState.HANDED_OFF }}",
        )
        database.close()
    }

    @Test
    fun recoveryStateSnapshot() = runBlocking {
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive())
        val state = requireNotNull(database.degradedConnectivityDao().get(activeJourney.id))
        val attempts = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        val heartbeat = database.heartbeatDao().observe(activeJourney.id).first()

        evidence(
            "JC_SMS_RECOVERY_SNAPSHOT phase=${state.connectivityPhase} " +
                "handed_off=${attempts.count { it.transportState == FallbackTransportState.HANDED_OFF }} " +
                "allocated=${attempts.count { it.transportState == FallbackTransportState.ALLOCATED }} " +
                "heartbeat_error=${heartbeat?.lastError ?: "none"} " +
                "attempt_ledger=${attempts.joinToString(",") { "${it.localAttemptId}:${it.envelopeSequence}:${it.transportState}" }}",
        )
        database.close()
    }

    @Test
    fun acceptanceHistorySnapshot() = runBlocking {
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive())
        val state = requireNotNull(database.degradedConnectivityDao().get(activeJourney.id)).toDomain()
        val attempts = database.fallbackAttemptDao().allForJourney(activeJourney.id)

        evidence(
            "JC_SMS_POLICY_STATE phase=${state.connectivityPhase} fallback=${state.fallbackDisposition} " +
                "validated=${state.validatedInternetAvailable} episode=${state.degradationEpisodeId} " +
                "last_episode=${state.lastDegradationEpisodeId} interruption_at=${state.interruptionStartedAtMillis} " +
                "recovery_at=${state.recoveryStartedAtMillis} last_cloud_success=${state.lastAuthenticatedCloudSuccessAtMillis} " +
                "next_envelope=${state.nextFallbackEnvelopeSequence} last_attempt_at=${state.lastFallbackAttemptAtMillis} " +
                "last_attempt_episode=${state.lastFallbackAttemptEpisodeId} " +
                "last_covered_telemetry=${state.lastCoveredTelemetrySequence} " +
                "rate_window_at=${state.rateWindowStartedAtMillis} rate_count=${state.fallbackAttemptsInRateWindow} " +
                "latest_telemetry=${state.latestTelemetrySequence} transport_available=${state.transportAvailable}",
        )
        attempts.filter { it.envelopeSequence in 2L..4L }.forEach { attempt ->
            evidence(
                "JC_SMS_ATTEMPT id=${attempt.localAttemptId} episode=${attempt.degradationEpisodeId} " +
                    "envelope=${attempt.envelopeSequence} telemetry=${attempt.telemetrySequence} " +
                    "allocated_at=${attempt.allocatedAt} state=${attempt.transportState} " +
                    "handoff_started_at=${attempt.handoffStartedAt} terminal_at=${attempt.terminalAt} " +
                    "attempt_count=${attempt.transportAttemptCount}",
            )
        }
        database.close()
    }

    @Test
    fun reconcileVerifiedAcceptanceOwnerSession() = runBlocking {
        val encodedRefreshToken = requireNotNull(
            InstrumentationRegistry.getArguments().getString(OWNER_REFRESH_TOKEN_ARGUMENT),
        ) { "The Base64-encoded controlled owner refresh token is required." }
        val refreshToken = String(
            Base64.decode(encodedRefreshToken, Base64.NO_WRAP),
            Charsets.UTF_8,
        )
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive())
        val bindingBefore = requireNotNull(database.fallbackAttemptDao().getBinding(activeJourney.id))
        val attemptsBefore = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        val client = createSupabaseClient(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth) {
                autoLoadFromStorage = false
                autoSaveToStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
        }
        val refreshedSession = client.auth.refreshSession(refreshToken)
        client.auth.importSession(refreshedSession, autoRefresh = true)
        val ownerId = requireNotNull(client.auth.currentSessionOrNull()?.user?.id)
        val visibleJourney = client.from("journeys").select {
            filter { eq("id", activeJourney.id) }
        }.decodeList<HostedJourneyProof>()
        assertEquals(1, visibleJourney.size)
        assertEquals(activeJourney.id, visibleJourney.single().id)
        client.auth.sessionManager.saveSession(requireNotNull(client.auth.currentSessionOrNull()))

        val identityPreferences = context.getSharedPreferences(
            PRODUCTION_IDENTITY_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        assertTrue(identityPreferences.edit().putString(PRODUCTION_IDENTITY_KEY, ownerId).commit())
        assertEquals(ownerId, SharedPreferencesTravellerIdentityStore(context).expectedTravellerUserId())
        val bindingAfter = requireNotNull(database.fallbackAttemptDao().getBinding(activeJourney.id))
        assertEquals(bindingBefore.journeyId, bindingAfter.journeyId)
        assertEquals(bindingBefore.keyId, bindingAfter.keyId)
        assertEquals(bindingBefore.status, bindingAfter.status)
        assertTrue(MessageDigest.isEqual(bindingBefore.journeyHandle, bindingAfter.journeyHandle))
        val attemptsAfter = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        assertEquals(attemptsBefore.size, attemptsAfter.size)
        attemptsBefore.zip(attemptsAfter).forEach { (before, after) ->
            assertEquals(before.localAttemptId, after.localAttemptId)
            assertEquals(before.envelopeSequence, after.envelopeSequence)
            assertEquals(before.telemetrySequence, after.telemetrySequence)
            assertEquals(before.transportState, after.transportState)
            assertTrue(MessageDigest.isEqual(before.payloadSha256, after.payloadSha256))
        }

        evidence(
            "JC_SMS_OWNER_SESSION hosted_owner_proof=true persisted_owner_session=true " +
                "local_journey_unchanged=true binding_unchanged=true attempts_unchanged=true",
        )
        database.close()
        client.close()
    }

    @Test
    fun productionStoredOwnerSessionPreflight() = runBlocking {
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive())
        val client = createSupabaseClient(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth) {
                autoLoadFromStorage = true
                autoSaveToStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
        }
        val identity = TravellerIdentityCoordinator(
            SupabaseTravellerAuthBackend(client),
            SharedPreferencesTravellerIdentityStore(context),
        )
        identity.requireAuthenticatedTraveller()
        val visibleJourney = client.from("journeys").select {
            filter { eq("id", activeJourney.id) }
        }.decodeList<HostedJourneyProof>()
        assertEquals(activeJourney.id, visibleJourney.single().id)
        evidence("JC_SMS_STORED_OWNER_SESSION owner_match=true hosted_owner_proof=true")
        database.close()
        client.close()
    }

    @Test
    fun transportIsReadyWithForegroundPermissionsSelectedSimAndExternalRoute() {
        val route = externalRoute()
        val status = AndroidSmsFallbackConfiguration(context) { route }.status()

        assertTrue("SEND_SMS must be granted through foreground UI.", status.sendPermissionGranted)
        assertTrue("READ_PHONE_STATE must be granted through foreground UI.", status.phoneStatePermissionGranted)
        assertTrue("Telephony messaging must be supported.", status.telephonyMessagingSupported)
        assertTrue("At least one active subscription is required.", status.activeSubscriptions.isNotEmpty())
        assertNotNull("An active SIM must be explicitly selected.", status.selectedSubscriptionId)
        assertTrue(
            "The selected SIM must still be active.",
            status.activeSubscriptions.any { it.subscriptionId == status.selectedSubscriptionId },
        )
        assertTrue(status.destinationConfigured)
        assertTrue(status.unavailableReason.orEmpty(), status.ready)

        evidence(
            "JC_SMS_READY active_subscriptions=${status.activeSubscriptions.size} " +
                "selected_sim=${safeSelectedSim(status)} ready=true",
        )
    }

    @Test
    fun recipientCopyMatchesHandedOffPersistedText() = runBlocking {
        val encodedReceivedText = requireNotNull(
            InstrumentationRegistry.getArguments().getString(RECEIVED_SMS_ARGUMENT),
        ) { "The Base64-encoded received SMS text must be supplied as an instrumentation argument." }
        val receivedText = String(
            Base64.decode(encodedReceivedText, Base64.NO_WRAP),
            Charsets.UTF_8,
        )
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive())
        val attempts = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        val handedOff = attempts.single { it.transportState == FallbackTransportState.HANDED_OFF }
        val persistedText = handedOff.protectedPayloadText
        val receivedDigest = MessageDigest.getInstance("SHA-256")
            .digest(receivedText.toByteArray(Charsets.UTF_8))

        assertTrue("Received SMS must begin with JC1.", receivedText.startsWith("JC1."))
        assertEquals(persistedText.length, receivedText.length)
        assertEquals(persistedText, receivedText)
        assertTrue(MessageDigest.isEqual(handedOff.payloadSha256, receivedDigest))
        val frame = FallbackEnvelopeV1.decode(receivedText)
        assertEquals(handedOff.envelopeSequence, frame.header.envelopeSequence)
        assertEquals(receivedText, FallbackEnvelopeV1.encode(frame).value)
        assertEquals(1, attempts.count { it.transportState == FallbackTransportState.HANDED_OFF })
        assertEquals(0, attempts.count { it.transportState == FallbackTransportState.ALLOCATED })

        evidence(
            "JC_SMS_RECEIVED exact_match=true prefix=JC1 length=${receivedText.length} " +
                "digest=${hex(receivedDigest)} envelope_sequence=${handedOff.envelopeSequence} " +
                "segments=1 no_second_allocation=true",
        )
        database.close()
    }

    @Test
    fun recoverInternetWithoutSupersedingHandedOffAttempt() = runBlocking {
        val encodedToken = requireNotNull(
            InstrumentationRegistry.getArguments().getString(OWNER_TOKEN_ARGUMENT),
        ) { "The Base64-encoded controlled owner token is required." }
        val token = String(Base64.decode(encodedToken, Base64.NO_WRAP), Charsets.UTF_8)
        val client = authenticatedClient(token)
        val ownerId = requireNotNull(client.auth.currentSessionOrNull()?.user?.id)
        val identity = TravellerIdentityCoordinator(
            FixedAuthenticatedBackend(ownerId),
            FixedIdentityStore(ownerId),
        )
        identity.requireAuthenticatedTraveller()
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive())
        val attemptsBefore = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        val handedOff = attemptsBefore.single { it.transportState == FallbackTransportState.HANDED_OFF }
        val degraded = coordinator(database, AndroidKeystoreFallbackKeyMaterialStore(context))

        degraded.validatedInternetAvailable(activeJourney.id)
        assertEquals(
            ConnectivityPhase.RECOVERING,
            requireNotNull(database.degradedConnectivityDao().get(activeJourney.id)).connectivityPhase,
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
        assertEquals(
            HeartbeatAttemptResult.Sent,
            HeartbeatCoordinator(
                RoomHeartbeatLocalStore(database.heartbeatDao()),
                SupabaseHeartbeatGateway(client, identity),
            ).sendFreshHeartbeat(
                journeyId = activeJourney.id,
                batteryPercent = 75,
                charging = false,
                connectivity = ConnectivityState.WIFI,
                networkUsable = true,
            ),
        )
        degraded.freshHeartbeatSucceeded(activeJourney.id)
        assertEquals(
            ConnectivityPhase.HEALTHY,
            requireNotNull(database.degradedConnectivityDao().get(activeJourney.id)).connectivityPhase,
        )
        assertEquals(
            FallbackTransportState.HANDED_OFF,
            requireNotNull(
                database.fallbackAttemptDao().getByLocalAttemptId(handedOff.localAttemptId),
            ).transportState,
        )
        assertEquals(attemptsBefore.size, database.fallbackAttemptDao().allForJourney(activeJourney.id).size)
        assertTrue(
            hasUsableFallbackBinding(
                database.fallbackAttemptDao().getBinding(activeJourney.id),
                AndroidKeystoreFallbackKeyMaterialStore(context),
            ),
        )
        evidence(
            "JC_SMS_RECOVERY phase=HEALTHY sync=SUCCESS heartbeat=FRESH " +
                "attempt_state=HANDED_OFF binding=ACTIVE attempts_unchanged=true",
        )
        database.close()
        client.close()
    }

    @Test
    fun recoveringSuppressesAllocationAcrossTicksTelemetryAndCoordinatorRestart() = runBlocking {
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive())
        val attemptsBefore = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        val allocated = attemptsBefore.single { it.transportState == FallbackTransportState.ALLOCATED }
        val handedOff = attemptsBefore.single { it.transportState == FallbackTransportState.HANDED_OFF }
        val keyStore = AndroidKeystoreFallbackKeyMaterialStore(context)
        val degraded = coordinator(database, keyStore)

        degraded.validatedInternetAvailable(activeJourney.id)
        repeat(3) {
            degraded.timeAdvanced(activeJourney.id)
            database.telemetryDao().getLatest(activeJourney.id)?.toDomain()?.let { latest ->
                degraded.telemetryObserved(latest)
            }
        }
        val restarted = coordinator(database, keyStore)
        restarted.activate(activeJourney.id, validatedInternetAvailable = true)
        repeat(3) { restarted.timeAdvanced(activeJourney.id) }

        val state = requireNotNull(database.degradedConnectivityDao().get(activeJourney.id))
        val attemptsAfter = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        assertEquals(ConnectivityPhase.RECOVERING, state.connectivityPhase)
        assertEquals(attemptsBefore.size, attemptsAfter.size)
        assertEquals(
            FallbackTransportState.ALLOCATED,
            requireNotNull(
                database.fallbackAttemptDao().getByLocalAttemptId(allocated.localAttemptId),
            ).transportState,
        )
        assertEquals(
            FallbackTransportState.HANDED_OFF,
            requireNotNull(
                database.fallbackAttemptDao().getByLocalAttemptId(handedOff.localAttemptId),
            ).transportState,
        )
        evidence(
            "JC_SMS_RECOVERING_REGRESSION phase=RECOVERING evaluation_cycles=6 " +
                "coordinator_restart=true attempts_unchanged=true allocated=1 handed_off=1",
        )
        database.close()
    }

    @Test
    fun handoffExistingPersistedAttemptAndRecordRealSentCallback() = runBlocking {
        val route = externalRoute()
        val database = database()
        val activeJourney = requireNotNull(database.journeyDao().getActive()) {
            "A legitimately hosted-provisioned active Journey is required."
        }
        val before = database.fallbackAttemptDao().allForJourney(activeJourney.id)
        val allocated = before.filter { it.transportState == FallbackTransportState.ALLOCATED }
        assertEquals("Exactly one durable ALLOCATED attempt is required.", 1, allocated.size)
        val attempt = allocated.single()
        val persistedDigest = MessageDigest.getInstance("SHA-256")
            .digest(attempt.protectedPayloadText.toByteArray(Charsets.UTF_8))
        assertTrue(MessageDigest.isEqual(attempt.payloadSha256, persistedDigest))
        val frame = FallbackEnvelopeV1.decode(attempt.protectedPayloadText)
        assertEquals(attempt.envelopeSequence, frame.header.envelopeSequence)
        assertEquals(attempt.protectedPayloadText, FallbackEnvelopeV1.encode(frame).value)

        val configuration = AndroidSmsFallbackConfiguration(context) { route }
        val status = configuration.status()
        assertTrue(status.unavailableReason.orEmpty(), status.ready)
        val selectedSubscription = requireNotNull(status.selectedSubscriptionId)
        val callback = CompletableDeferred<Int>()
        val gateway = AcceptanceSmsGateway(context)
        assertEquals(1, gateway.divideMessage(selectedSubscription, attempt.protectedPayloadText).size)
        val coordinator = FallbackHandoffCoordinator(
            attempts = RoomFallbackHandoffAttemptStore(database.fallbackAttemptDao()),
            configuration = configuration,
            telephony = gateway,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                callback.complete(resultCode)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_ACCEPTANCE_SMS_SENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            assertEquals(
                FallbackHandoffResult.SubmittedAwaitingCallback,
                coordinator.handoff(attempt.localAttemptId),
            )
            val inProgress = requireNotNull(
                database.fallbackAttemptDao().getByLocalAttemptId(attempt.localAttemptId),
            )
            assertEquals(FallbackTransportState.HANDOFF_IN_PROGRESS, inProgress.transportState)
            assertEquals(1, inProgress.handoffGeneration)
            evidence(
                "JC_SMS_SUBMITTED attempt_id=${attempt.localAttemptId} " +
                    "envelope_sequence=${attempt.envelopeSequence} state=HANDOFF_IN_PROGRESS " +
                    "length=${attempt.protectedPayloadText.length} digest=${hex(persistedDigest)} segments=1",
            )

            val resultCode = withTimeoutOrNull(CALLBACK_TIMEOUT_MILLIS) { callback.await() }
            if (resultCode == null) {
                evidence("JC_SMS_CALLBACK outcome=TIMEOUT state=HANDOFF_IN_PROGRESS")
                error("Android sent-result callback timed out; outcome remains uncertain.")
            }
            val recorded = coordinator.sentResult(
                attempt.localAttemptId,
                inProgress.handoffGeneration,
                resultCode,
            )
            val afterCallback = requireNotNull(
                database.fallbackAttemptDao().getByLocalAttemptId(attempt.localAttemptId),
            )
            evidence(
                "JC_SMS_CALLBACK result_code=$resultCode classified=${classifyAndroidSmsResult(resultCode)} " +
                    "recorded=$recorded state=${afterCallback.transportState}",
            )
            assertEquals(Activity.RESULT_OK, resultCode)
            assertTrue(recorded)
            assertEquals(FallbackTransportState.HANDED_OFF, afterCallback.transportState)
            assertEquals(FallbackTransportOutcome.ANDROID_HANDOFF_SUCCEEDED, afterCallback.lastTransportOutcome)
            assertEquals(before.size, database.fallbackAttemptDao().allForJourney(activeJourney.id).size)
            evidence(
                "JC_SMS_HANDED_OFF attempt_id=${attempt.localAttemptId} " +
                    "envelope_sequence=${attempt.envelopeSequence} state=HANDED_OFF " +
                    "meaning=android_telephony_reported_successful_send_handoff",
            )
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            database.close()
        }
    }

    private fun externalRoute(): SmsFallbackRoute {
        val value = requireNotNull(
            InstrumentationRegistry.getArguments().getString(RECIPIENT_ARGUMENT),
        ) { "A controlled E.164 recipient must be supplied as an instrumentation argument." }
        return SmsFallbackRoute(value)
    }

    private fun safeSelectedSim(status: SmsFallbackStatus): String =
        status.activeSubscriptions.single { it.subscriptionId == status.selectedSubscriptionId }
            .safeDisplayName

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
    ).addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()

    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }

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

    private suspend fun authenticatedClient(
        token: String,
        persistSession: Boolean = false,
    ) = createSupabaseClient(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    ) {
        install(Auth) {
            autoLoadFromStorage = false
            autoSaveToStorage = persistSession
            alwaysAutoRefresh = false
        }
        install(Postgrest)
    }.also { client ->
        client.auth.importAuthToken(token, retrieveUser = true, autoRefresh = false)
    }

    private class AcceptanceSmsGateway(
        private val context: Context,
    ) : SmsTelephonyGateway {
        override fun divideMessage(subscriptionId: Int, text: String): List<String> =
            manager(subscriptionId).divideMessage(text)

        override fun send(request: SmsHandoffRequest) {
            val intent = Intent(ACTION_ACCEPTANCE_SMS_SENT).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                request.localAttemptId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager(request.subscriptionId).sendTextMessage(
                request.destination,
                null,
                request.exactPersistedText,
                pendingIntent,
                null,
            )
        }

        @Suppress("DEPRECATION")
        private fun manager(subscriptionId: Int): SmsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
    }

    private fun evidence(message: String) {
        Log.i(EVIDENCE_TAG, message)
    }

    private companion object {
        const val EVIDENCE_TAG = "JC_SMS_ACCEPTANCE"
        const val RECIPIENT_ARGUMENT = "sms_recipient"
        const val RECEIVED_SMS_ARGUMENT = "received_sms"
        const val OWNER_TOKEN_ARGUMENT = "owner_token"
        const val OWNER_REFRESH_TOKEN_ARGUMENT = "owner_refresh_token"
        const val DATABASE_NAME = "journey-continuity.db"
        const val PRODUCTION_IDENTITY_PREFERENCES = "journey-continuity-identity"
        const val PRODUCTION_IDENTITY_KEY = "expectedTravellerUserId"
        const val ACTION_ACCEPTANCE_SMS_SENT =
            "com.journeycontinuity.app.test.action.ACCEPTANCE_SMS_SENT"
        const val CALLBACK_TIMEOUT_MILLIS = 120_000L
    }

    private class FixedIdentityStore(private val ownerId: String) : TravellerIdentityStore {
        override fun expectedTravellerUserId(): String = ownerId
        override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean = userId == ownerId
    }

    private class FixedAuthenticatedBackend(private val ownerId: String) : TravellerAuthBackend {
        override suspend fun awaitInitialization() = Unit
        override fun sessionState() = TravellerSessionState.Authenticated(ownerId)
        override suspend fun signInAnonymously() = error("Acceptance owner identity must not change.")
    }

    @Serializable
    private data class HostedJourneyProof(val id: String)

}
