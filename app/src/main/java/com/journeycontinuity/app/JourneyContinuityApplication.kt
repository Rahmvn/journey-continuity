package com.journeycontinuity.app

import android.app.Application
import androidx.room.Room
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.local.MIGRATION_2_3
import com.journeycontinuity.app.data.local.MIGRATION_3_4
import com.journeycontinuity.app.data.local.MIGRATION_4_5
import com.journeycontinuity.app.data.local.MIGRATION_5_6
import com.journeycontinuity.app.data.local.MIGRATION_6_7
import com.journeycontinuity.app.data.local.MIGRATION_7_8
import com.journeycontinuity.app.data.local.MIGRATION_8_9
import com.journeycontinuity.app.data.local.MIGRATION_9_10
import com.journeycontinuity.app.data.local.MIGRATION_10_11
import com.journeycontinuity.app.data.local.MIGRATION_11_12
import com.journeycontinuity.app.data.local.MIGRATION_12_13
import com.journeycontinuity.app.data.local.MIGRATION_13_14
import com.journeycontinuity.app.data.local.MIGRATION_14_15
import com.journeycontinuity.app.data.local.MIGRATION_15_16
import com.journeycontinuity.app.data.local.MIGRATION_16_17
import com.journeycontinuity.app.data.local.MIGRATION_17_18
import com.journeycontinuity.app.data.local.MIGRATION_18_19
import com.journeycontinuity.app.data.local.MIGRATION_19_20
import com.journeycontinuity.app.data.local.MIGRATION_20_21
import com.journeycontinuity.app.data.repository.RoomProbableEndAutoEndCoordinator
import com.journeycontinuity.app.data.repository.WorkManagerProbableEndAutoEndScheduler
import com.journeycontinuity.app.data.probableend.ProbableEndAutoEndGateway
import com.journeycontinuity.app.data.probableend.ProbableEndFinalization
import com.journeycontinuity.app.data.probableend.ProbableEndRegistration
import com.journeycontinuity.app.data.probableend.ProbableEndServerWindowRequest
import com.journeycontinuity.app.data.probableend.SupabaseProbableEndAutoEndGateway
import com.journeycontinuity.app.data.repository.RoomProbableEndGuardStore
import com.journeycontinuity.app.data.repository.RoomProbableEndPresentationCoordinator
import com.journeycontinuity.app.data.repository.RoomProbableEndResponseCoordinator
import com.journeycontinuity.app.data.repository.RoomProbableEndDeadlineCoordinator
import com.journeycontinuity.app.data.repository.WorkManagerProbableEndDeadlineScheduler
import com.journeycontinuity.app.data.repository.ProbableEndResponseRequest
import com.journeycontinuity.app.data.repository.ProbableEndTravellerResponse
import com.journeycontinuity.app.data.repository.ProbableEndResponseOutcome
import com.journeycontinuity.app.domain.probableend.*
import com.journeycontinuity.app.data.probableend.SupabaseProbableEndVerificationGuardGateway
import com.journeycontinuity.app.data.repository.RoomProbableEndStore
import com.journeycontinuity.app.data.local.FALLBACK_ATTEMPT_INVARIANT_CALLBACK
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.data.repository.RoomJourneyRepository
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.DeviceHeartbeat
import com.journeycontinuity.app.auth.SharedPreferencesTravellerIdentityStore
import com.journeycontinuity.app.auth.SharedPreferencesInstallationIdentityStore
import com.journeycontinuity.app.auth.SupabaseTravellerAuthBackend
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.auth.OwnerAdmissionGate
import com.journeycontinuity.app.auth.GuardedTravellerNoActiveSignOutGateway
import com.journeycontinuity.app.auth.SupabaseAccountLocalSession
import com.journeycontinuity.app.auth.TravellerNoActiveSignOutGateway
import com.journeycontinuity.app.auth.TravellerActiveJourneySignOutGateway
import com.journeycontinuity.app.auth.GuardedTravellerActiveJourneySignOutGateway
import com.journeycontinuity.app.auth.RoomActiveJourneyStopLocal
import com.journeycontinuity.app.auth.SupabaseActiveJourneyStopRemote
import com.journeycontinuity.app.auth.AndroidActiveJourneyRuntimeStop
import com.journeycontinuity.app.auth.SharedPreferencesMonitoringStoppedResultStore
import com.journeycontinuity.app.auth.LocalOnlyMonitoringStoppedResultGateway
import com.journeycontinuity.app.auth.AndroidOwnerAdoptionSnapshotReader
import com.journeycontinuity.app.auth.FirstOwnerAdoptionBoundary
import com.journeycontinuity.app.auth.ReturningTravellerLoginEngine
import com.journeycontinuity.app.auth.SupabasePrimaryTravellerSession
import com.journeycontinuity.app.auth.SupabaseReturningLoginAttemptFactory
import com.journeycontinuity.app.auth.SupabaseTravellerCreateAccountGateway
import com.journeycontinuity.app.auth.TravellerCreateAccountEngine
import com.journeycontinuity.app.heartbeat.HeartbeatCoordinator
import com.journeycontinuity.app.heartbeat.HeartbeatGateway
import com.journeycontinuity.app.heartbeat.HeartbeatServerState
import com.journeycontinuity.app.heartbeat.RoomHeartbeatLocalStore
import com.journeycontinuity.app.heartbeat.SupabaseHeartbeatGateway
import com.journeycontinuity.app.degraded.DegradedConnectivityCoordinator
import com.journeycontinuity.app.degraded.DegradedConnectivityLabConfiguration
import com.journeycontinuity.app.degraded.DegradedConnectivityPolicy
import com.journeycontinuity.app.degraded.AndroidKeystoreFallbackKeyMaterialStore
import com.journeycontinuity.app.degraded.DurableFallbackAttemptAllocator
import com.journeycontinuity.app.degraded.FallbackCapabilityReader
import com.journeycontinuity.app.degraded.FallbackProvisioningCoordinator
import com.journeycontinuity.app.degraded.AuthenticatedFallbackProvisioningGateway
import com.journeycontinuity.app.degraded.SupabaseFallbackProvisioningGateway
import com.journeycontinuity.app.degraded.RoomFallbackProvisioningLocalStore
import com.journeycontinuity.app.degraded.hasUsableFallbackBinding
import com.journeycontinuity.app.degraded.RoomDegradedConnectivityStateStore
import com.journeycontinuity.app.degraded.AndroidSmsFallbackConfiguration
import com.journeycontinuity.app.degraded.AndroidSmsTelephonyGateway
import com.journeycontinuity.app.degraded.FallbackHandoffCoordinator
import com.journeycontinuity.app.degraded.UnconfiguredSmsFallbackRouteProvider
import com.journeycontinuity.app.degraded.WorkManagerFallbackHandoffScheduler
import com.journeycontinuity.app.degraded.RoomFallbackHandoffAttemptStore
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.service.JourneyServiceController
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.CloudSyncGateway
import com.journeycontinuity.app.sync.AndroidSyncDiagnosticLogger
import com.journeycontinuity.app.sync.ReliableSyncEngine
import com.journeycontinuity.app.sync.RoomLocalSyncStore
import com.journeycontinuity.app.sync.SupabaseCloudSyncGateway
import com.journeycontinuity.app.sync.SupabaseConfiguration
import com.journeycontinuity.app.sync.SyncFailureKind
import com.journeycontinuity.app.sync.SyncRequestUrgency
import com.journeycontinuity.app.sync.WorkManagerSyncScheduler
import com.journeycontinuity.app.trusted.SupabaseTrustedContactGateway
import com.journeycontinuity.app.trusted.TrustedContactGateway
import com.journeycontinuity.app.trusted.UnavailableTrustedContactGateway
import com.journeycontinuity.app.ui.IntroCompletionStore
import com.journeycontinuity.app.ui.CreateAccountProgressStore
import com.journeycontinuity.app.ui.LoginEntryStore
import com.journeycontinuity.app.ui.CreateAccountEntryStore
import com.journeycontinuity.app.ui.ProductionTravellerRootAuth
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.launch

class JourneyContinuityApplication : Application() {
    private val ownerAdmissionGate = OwnerAdmissionGate()
    private val terminalHandoffGate = com.journeycontinuity.app.degraded.JourneyTerminalHandoffGate()
    private val database: JourneyDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            JourneyDatabase::class.java,
            "journey-continuity.db",
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
        ).addCallback(FALLBACK_ATTEMPT_INVARIANT_CALLBACK).build()
    }

    private val degradedConnectivityPolicy by lazy {
        DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig())
    }

    private val fallbackKeyMaterialStore by lazy {
        AndroidKeystoreFallbackKeyMaterialStore(applicationContext)
    }

    private val fallbackAttemptAllocator by lazy {
        DurableFallbackAttemptAllocator(
            journeyDao = database.journeyDao(),
            telemetryDao = database.telemetryDao(),
            degradationDao = database.degradedConnectivityDao(),
            fallbackAttemptDao = database.fallbackAttemptDao(),
            keyMaterialStore = fallbackKeyMaterialStore,
        )
    }

    val smsFallbackConfiguration by lazy {
        AndroidSmsFallbackConfiguration(applicationContext, UnconfiguredSmsFallbackRouteProvider)
    }

    val fallbackHandoffCoordinator by lazy {
        FallbackHandoffCoordinator(
            attempts = RoomFallbackHandoffAttemptStore(database.fallbackAttemptDao()),
            configuration = smsFallbackConfiguration,
            telephony = AndroidSmsTelephonyGateway(applicationContext),
            scheduler = WorkManagerFallbackHandoffScheduler(applicationContext),
            terminalHandoffGate = terminalHandoffGate,
        )
    }

    private val installationIdentityStore by lazy {
        SharedPreferencesInstallationIdentityStore(applicationContext)
    }

    val degradedConnectivityCoordinator: DegradedConnectivityCoordinator by lazy {
        DegradedConnectivityCoordinator(
            store = RoomDegradedConnectivityStateStore(
                database = database,
                dao = database.degradedConnectivityDao(),
                fallbackAttemptDao = database.fallbackAttemptDao(),
                allocator = fallbackAttemptAllocator,
                policy = degradedConnectivityPolicy,
            ),
            latestTelemetryReader = { journeyId ->
                database.telemetryDao().getLatest(journeyId)?.toDomain()
            },
            fallbackCapabilityReader = FallbackCapabilityReader { journeyId ->
                hasUsableFallbackBinding(
                    database.fallbackAttemptDao().getBinding(journeyId),
                    fallbackKeyMaterialStore,
                )
            },
            policy = degradedConnectivityPolicy,
            logger = AndroidSyncDiagnosticLogger,
        )
    }

    val syncScheduler by lazy { WorkManagerSyncScheduler(applicationContext) }

    private val probableEndStore by lazy { RoomProbableEndStore(database) }
    private val probableEndDeadlineScheduler by lazy { WorkManagerProbableEndDeadlineScheduler(applicationContext) }
    val probableEndDeadlineCoordinator by lazy {
        RoomProbableEndDeadlineCoordinator(database, probableEndDeadlineScheduler)
    }
    val probableEndAutoEndCoordinator by lazy {
        RoomProbableEndAutoEndCoordinator(database, cloudGateways.autoEnd,
            WorkManagerProbableEndAutoEndScheduler(applicationContext),
            onTerminalEffects = { journeyId ->
                check(exactJourneyRuntimeTeardown.stop(journeyId)) { "Exact automatic-end teardown pending" }
                syncScheduler.schedule(SyncRequestUrgency.URGENT)
            },
            onGuardBlocked = { journeyId ->
                probableEndGuardCoordinator.reconcile(journeyId,
                    GuardReconciliationTrigger.RESTORE)
            }, terminalHandoffGate = terminalHandoffGate)
    }
    private val probableEndGuardCoordinator by lazy {
        ProbableEndGuardCoordinator(RoomProbableEndGuardStore(database), cloudGateways.verificationGuard)
    }
    private val probableEndPresentationCoordinator by lazy {
        RoomProbableEndPresentationCoordinator(database, RoomProbableEndGuardStore(database),
            cloudGateways.verificationGuard, deadlineScheduler = probableEndDeadlineScheduler)
    }
    private val probableEndResponseCoordinator by lazy {
        RoomProbableEndResponseCoordinator(database, probableEndStore,
            onExplicitCompletion = { journeyId, completedAt ->
                var needsRetry = false
                try { journeyServiceController.stop() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { needsRetry = true }
                try { degradedConnectivityCoordinator.journeyCompleted(journeyId, completedAt) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { needsRetry = true }
                try { syncScheduler.schedule(SyncRequestUrgency.URGENT) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { needsRetry = true }
                if (needsRetry) error("Completion effects pending")
            })
    }
    val probableEndProvenCandidateId by lazy { database.probableEndPresentationDao().observeProvenCandidateId() }
    val probableEndPresentationEpisode by lazy { database.probableEndPresentationDao().observeActionableLocalEpisode() }
    val probableEndCompletedResult by lazy { database.probableEndPresentationDao().observePendingCompletedResult() }
    val probableEndAutomaticResult by lazy { database.probableEndPresentationDao().observePendingAutomaticResult() }

    suspend fun recheckProbableEndForForeground(journeyId: String, candidateId: String): String? =
        probableEndPresentationCoordinator.recheckAndPromote(journeyId, candidateId)

    suspend fun recordProbableEndForegroundOpportunity(journeyId: String, episodeId: String): Boolean =
        probableEndPresentationCoordinator.recordForegroundOpportunity(journeyId, episodeId).also { recorded ->
            if (recorded) localInferenceScope.launch {
                try { probableEndAutoEndCoordinator.register(episodeId) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Secondary registration retries from durable work. */ }
            }
        }

    suspend fun respondToProbableEnd(request: ProbableEndResponseRequest,
        response: ProbableEndTravellerResponse): ProbableEndResponseOutcome =
        probableEndResponseCoordinator.respond(request, response)

    suspend fun acknowledgeProbableEndCompletion(episodeId: String): Boolean =
        probableEndResponseCoordinator.acknowledgeCompletedResult(episodeId)

    suspend fun acknowledgeProbableEndAutomaticResult(episodeId: String): Boolean =
        probableEndAutoEndCoordinator.acknowledgeResult(episodeId)
    // Service identity/display needs no structured-destination decoding. No fallback Journey is fabricated.
    internal val activeMonitoringJourney by lazy { database.journeyDao().observeCollectable() }
    private val localInferenceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    val journeyRepository: JourneyRepository by lazy {
        RoomJourneyRepository(
            journeyDao = database.journeyDao(),
            telemetryDao = database.telemetryDao(),
            syncStateDao = database.syncStateDao(),
            heartbeatDao = database.heartbeatDao(),
            syncScheduler = syncScheduler,
            degradedConnectivityCoordinator = degradedConnectivityCoordinator,
            admissionGate = ownerAdmissionGate,
            terminalHandoffGate = terminalHandoffGate,
            probableEndObserver = { resumeLocalProbableEnd(it) },
            admissionReceipt = { runCatching { operationalBoundaryReceipt() }.getOrNull() },
        )
    }

    val journeyLifecycle: JourneyLifecycle by lazy {
        JourneyLifecycle(journeyRepository, catalogue = adminCatalogue.snapshot())
    }

    private val monitoringStoppedResultStore by lazy {
        SharedPreferencesMonitoringStoppedResultStore(applicationContext)
    }

    val journeyServiceController: JourneyServiceController by lazy {
        JourneyServiceController(applicationContext)
    }

    private val fallbackProvisioningCoordinator: FallbackProvisioningCoordinator by lazy {
        FallbackProvisioningCoordinator(
            localStore = RoomFallbackProvisioningLocalStore(database, database.fallbackAttemptDao()),
            installationIdentityStore = installationIdentityStore,
            keyMaterialStore = fallbackKeyMaterialStore,
            gateway = cloudGateways.fallbackProvisioning,
            capabilityChanged = { journeyId ->
                degradedConnectivityCoordinator.refreshFallbackCapability(journeyId)
            },
            logger = AndroidSyncDiagnosticLogger,
        )
    }

    private val cloudGateways: CloudGateways by lazy {
        val logger = AndroidSyncDiagnosticLogger
        val configuration = SupabaseConfiguration.fromBuildConfig()
        logger.info("Supabase URL configured: ${configuration.url.isNotBlank()}")
        logger.info("Supabase URL parses as HTTPS: ${configuration.isHttpsUrl}")
        logger.info("Supabase hostname/project-ref shape valid: ${configuration.hasExpectedProjectHostShape}")
        logger.info("Publishable key configured: ${configuration.publishableKey.isNotBlank()}")
        logger.info("Publishable key category: ${configuration.keyCategory}")
        val configurationError = configuration.validationError
        if (configurationError == null) {
            try {
                val client = createSupabaseClient(
                    supabaseUrl = configuration.url,
                    supabaseKey = configuration.publishableKey,
                ) {
                    install(Auth) {
                        autoLoadFromStorage = true
                        autoSaveToStorage = true
                        alwaysAutoRefresh = true
                    }
                    install(Postgrest)
                }
                logger.info("Supabase client initialized")
                val authBackend = SupabaseTravellerAuthBackend(client)
                val identityStore = SharedPreferencesTravellerIdentityStore(applicationContext)
                val firstOwnerBoundary = FirstOwnerAdoptionBoundary(
                    AndroidOwnerAdoptionSnapshotReader(applicationContext, database, authBackend),
                )
                val identityCoordinator = TravellerIdentityCoordinator(
                    backend = authBackend,
                    identityStore = identityStore,
                    logger = logger,
                    admissionGate = ownerAdmissionGate,
                    canEstablishAnonymousOwner = firstOwnerBoundary::canEstablishAnonymousOwner,
                )
                CloudGateways(
                    authBackend = authBackend,
                    ownerStore = identityStore,
                    accountEmail = {
                        (client.auth.sessionStatus.value as? SessionStatus.Authenticated)
                            ?.session?.user?.email
                    },
                    identityCoordinator = identityCoordinator,
                    account = com.journeycontinuity.app.auth.SupabaseTravellerAccountGateway(
                        client, identityCoordinator),
                    noActiveSignOut = GuardedTravellerNoActiveSignOutGateway(
                        journeyRepository, identityCoordinator, identityStore, authBackend,
                        ownerAdmissionGate, SupabaseAccountLocalSession(client)),
                    activeSignOut = GuardedTravellerActiveJourneySignOutGateway(
                        local = RoomActiveJourneyStopLocal(database.journeyDao(), journeyRepository,
                            database.degradedConnectivityDao(), database.fallbackAttemptDao()),
                        remote = SupabaseActiveJourneyStopRemote(client, authBackend),
                        identityCoordinator = identityCoordinator,
                        identityStore = identityStore,
                        authBackend = authBackend,
                        localSession = SupabaseAccountLocalSession(client),
                        runtime = AndroidActiveJourneyRuntimeStop(journeyServiceController),
                        resultStore = monitoringStoppedResultStore,
                        admissionGate = ownerAdmissionGate,
                        reconciliationRequested = {
                            syncScheduler.schedule(SyncRequestUrgency.URGENT)
                        },
                        reservations = com.journeycontinuity.app.data.repository.RoomAccountStopReservations(database, terminalHandoffGate),
                    ),
                    sync = SupabaseCloudSyncGateway(client, identityCoordinator, logger,
                        boundaryAdmission = { database.unresolvedBoundaryDao().get(it)?.admission() }),
                    unresolvedBoundary = com.journeycontinuity.app.data.unresolved.SupabaseUnresolvedBoundaryGateway(client, identityCoordinator),
                    incidentAuthority = com.journeycontinuity.app.data.unresolved.SupabaseIncidentAuthorityGateway(client, identityCoordinator),
                    journeyStop = SupabaseActiveJourneyStopRemote(client, authBackend),
                    heartbeat = SupabaseHeartbeatGateway(client, identityCoordinator, logger),
                    verificationGuard = SupabaseProbableEndVerificationGuardGateway(client, identityCoordinator),
                    autoEnd = SupabaseProbableEndAutoEndGateway(client, identityCoordinator),
                    trustedContacts = SupabaseTrustedContactGateway(
                        client = client,
                        identityCoordinator = identityCoordinator,
                        trustedViewerBaseUrl = com.journeycontinuity.app.BuildConfig.TRUSTED_VIEWER_BASE_URL,
                        logger = logger,
                    ),
                    fallbackProvisioning = SupabaseFallbackProvisioningGateway(
                        client = client,
                        identityCoordinator = identityCoordinator,
                        supabaseUrl = configuration.url,
                        publishableKey = configuration.publishableKey,
                    ),
                    destinationSearch = com.journeycontinuity.app.data.search.SupabaseDestinationSearchGateway(
                        client, identityCoordinator, configuration.url, configuration.publishableKey,
                    ),
                    destinationMap = com.journeycontinuity.app.data.search.SupabaseDestinationMapGateway(
                        client, identityCoordinator, configuration.url, configuration.publishableKey,
                    ),
                    returningLogin = ReturningTravellerLoginEngine(
                        attempts = SupabaseReturningLoginAttemptFactory(configuration),
                        boundary = firstOwnerBoundary,
                        ownerStore = identityStore,
                        primary = SupabasePrimaryTravellerSession(client),
                        identityCoordinator = identityCoordinator,
                        admissionGate = ownerAdmissionGate,
                    ),
                    createAccount = TravellerCreateAccountEngine(
                        gateway = SupabaseTravellerCreateAccountGateway(client),
                        ownerStore = identityStore,
                        identityCoordinator = identityCoordinator,
                    ),
                )
            } catch (error: Throwable) {
                val exceptionName = error::class.simpleName ?: "Exception"
                val safeError = "Supabase client initialization failed ($exceptionName)."
                logger.warning(safeError)
                ConfigurationFailureCloudGateway(safeError).asGateways()
            }
        } else {
            logger.warning("Supabase configuration invalid: $configurationError")
            ConfigurationFailureCloudGateway(configurationError).asGateways()
        }
    }

    val syncEngine: ReliableSyncEngine by lazy {
        ReliableSyncEngine(
            local = RoomLocalSyncStore(
                journeyDao = database.journeyDao(),
                telemetryDao = database.telemetryDao(),
                syncStateDao = database.syncStateDao(),
            ),
            remote = cloudGateways.sync,
            logger = AndroidSyncDiagnosticLogger,
            attemptObserver = { journeyId ->
                degradedConnectivityCoordinator.retryableCloudFailure(journeyId)
            },
            provisioningObserver = { journeyId ->
                fallbackProvisioningCoordinator.provisionIfEligible(journeyId)
            },
            reconcileCancelled = { journeyId, endedAt ->
                journeyRepository.stopMonitoringActive(journeyId, endedAt) != null
            },
            reconcileAutoEnded = { journeyId, endedAt ->
                probableEndAutoEndCoordinator.reconcileRemoteAutoEnded(journeyId, endedAt)
            },
            reconcileAuthoritativeTerminal = { incidentReconciliationCoordinator.reconcileServerTerminal(it) },
            reconcileExplicitIntent = { incidentReconciliationCoordinator.reconcilePendingExplicit(it) },
        )
    }

    val heartbeatCoordinator: HeartbeatCoordinator by lazy {
        HeartbeatCoordinator(
            local = RoomHeartbeatLocalStore(database.heartbeatDao()),
            remote = cloudGateways.heartbeat,
            logger = AndroidSyncDiagnosticLogger,
            terminalHandoffGate = terminalHandoffGate,
            provisioningObserver = { journeyId ->
                fallbackProvisioningCoordinator.provisionIfEligible(journeyId)
            },
        )
    }

    val trustedContactGateway: TrustedContactGateway
        get() = cloudGateways.trustedContacts

    val currentAccountEmail: String?
        get() = cloudGateways.accountEmail()

    val travellerAccountGateway: com.journeycontinuity.app.auth.TravellerAccountGateway
        get() = cloudGateways.account

    val adminCatalogue by lazy {
        com.journeycontinuity.app.data.catalogue.BundledAdminCatalogue(applicationContext)
    }

    val destinationSearchGateway: com.journeycontinuity.app.ui.destination.DestinationSearchGateway
        get() = cloudGateways.destinationSearch
    val destinationMapGateway: com.journeycontinuity.app.ui.destination.DestinationMapGateway
        get() = cloudGateways.destinationMap

    val travellerNoActiveSignOutGateway: TravellerNoActiveSignOutGateway?
        get() = cloudGateways.noActiveSignOut

    val travellerActiveJourneySignOutGateway: TravellerActiveJourneySignOutGateway?
        get() = cloudGateways.activeSignOut

    val returningLoginEngine: ReturningTravellerLoginEngine?
        get() = cloudGateways.returningLogin

    val createAccountEngine: TravellerCreateAccountEngine?
        get() = cloudGateways.createAccount

    val introCompletionStore by lazy { IntroCompletionStore(applicationContext) }
    val createAccountProgressStore by lazy { CreateAccountProgressStore(applicationContext) }
    val loginEntryStore by lazy { LoginEntryStore(applicationContext) }
    val createAccountEntryStore by lazy { CreateAccountEntryStore(applicationContext) }

    val travellerRootAuth by lazy {
        ProductionTravellerRootAuth(cloudGateways.identityCoordinator,
            cloudGateways.createAccount, cloudGateways.returningLogin,
            cloudGateways.authBackend, cloudGateways.ownerStore, journeyRepository)
    }

    override fun onCreate() {
        super.onCreate()
        // Re-evaluate durable requested state after process restart. An urgent wake can
        // bypass a legacy/retrying worker without cancelling work that is already running.
        runCatching { syncScheduler.schedule(SyncRequestUrgency.URGENT) }
        localInferenceScope.launch { resumeLocalProbableEnd() }
        localInferenceScope.launch { probableEndResponseCoordinator.resumeCompletionEffects() }
        localInferenceScope.launch { probableEndDeadlineCoordinator.restore() }
        localInferenceScope.launch { probableEndAutoEndCoordinator.restore() }
        localInferenceScope.launch { incidentReconciliationCoordinator.restoreEffects() }
    }

    /** Also isolates lazy store initialization; only optional inference failures are contained here. */
    internal suspend fun resumeLocalProbableEnd(journeyId: String? = null, drain: Boolean = false) {
        try {
            when {
                journeyId == null -> probableEndStore.resumeActive()
                drain -> probableEndStore.resume(journeyId)
                else -> probableEndStore.consumePending(journeyId)
            }
            val exactJourneyId = journeyId ?: database.journeyDao().getActive()?.id
            if (exactJourneyId != null) requestProbableEndGuard(exactJourneyId,
                if (journeyId == null || drain) GuardReconciliationTrigger.RESTORE else GuardReconciliationTrigger.NEW_CANDIDATE)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* Optional local inference unavailable. */ }
    }

    /** Launch outside telemetry/heartbeat persistence; no guard read blocks primary monitoring. */
    internal fun requestProbableEndGuard(journeyId: String, trigger: GuardReconciliationTrigger) {
        localInferenceScope.launch {
            try { probableEndGuardCoordinator.reconcile(journeyId, trigger) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Includes optional lazy gateway/database initialization. */ }
        }
    }

    private inner class ConfigurationFailureCloudGateway(
        private val safeError: String,
    ) : CloudSyncGateway, HeartbeatGateway {
        override suspend fun authenticatedOwnerId(): String = throw CloudSyncException(
            SyncFailureKind.PERMANENT,
            safeError,
            "Cloud configuration failed: $safeError",
        )

        override suspend fun upsertJourney(
            journey: com.journeycontinuity.app.domain.Journey,
            ownerId: String,
        ) = Unit

        override suspend fun upsertTelemetry(
            observations: List<com.journeycontinuity.app.domain.TelemetryObservation>,
        ) = Unit

        override suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState =
            throw CloudSyncException(
                SyncFailureKind.PERMANENT,
                safeError,
                "Cloud configuration failed: $safeError",
            )

        fun asGateways() = CloudGateways(
            identityCoordinator = null,
            account = com.journeycontinuity.app.auth.UnavailableTravellerAccountGateway(),
            activeSignOut = LocalOnlyMonitoringStoppedResultGateway(
                RoomActiveJourneyStopLocal(database.journeyDao(), journeyRepository,
                    database.degradedConnectivityDao(), database.fallbackAttemptDao()),
                monitoringStoppedResultStore,
                AndroidActiveJourneyRuntimeStop(journeyServiceController)),
            sync = this,
            heartbeat = this,
            trustedContacts = UnavailableTrustedContactGateway(safeError),
            fallbackProvisioning = AuthenticatedFallbackProvisioningGateway { _, _ ->
                throw CloudSyncException(SyncFailureKind.PERMANENT, safeError)
            },
            destinationSearch = com.journeycontinuity.app.ui.destination.DestinationSearchGateway {
                throw com.journeycontinuity.app.ui.destination.DestinationSearchException(
                    com.journeycontinuity.app.ui.destination.DestinationSearchFailure.UNAVAILABLE)
            },
            returningLogin = null,
            createAccount = null,
        )

        override suspend fun ownerJourneyState(
            journeyId: String,
            ownerId: String,
        ): com.journeycontinuity.app.sync.RemoteJourneyState? = throw CloudSyncException(
            SyncFailureKind.PERMANENT,
            safeError,
            "Cloud configuration failed: $safeError",
        )
    }

    private fun operationalBoundaryReceipt(): com.journeycontinuity.app.domain.BoundaryReceipt {
        val preferences = getSharedPreferences("unresolved-boundary-observation", MODE_PRIVATE)
        val installation = preferences.getString("installationId", null) ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString("installationId", it).commit())
        }
        return com.journeycontinuity.app.domain.BoundaryReceipt(android.os.SystemClock.elapsedRealtime(),
            runCatching { android.provider.Settings.Global.getInt(contentResolver,
                android.provider.Settings.Global.BOOT_COUNT) }.getOrNull(), installation)
    }

    /** 10C-1 machinery is deliberately not scheduled or called by production startup. */
    val operationalBoundaryCoordinator by lazy {
        com.journeycontinuity.app.data.repository.RoomOperationalBoundaryCoordinator(database,
            terminalHandoffGate, afterPause = { journeyServiceController.stop() })
    }

    val unresolvedBoundaryStore by lazy {
        com.journeycontinuity.app.data.repository.RoomUnresolvedBoundaryStore(database,
            cloudGateways.unresolvedBoundary, ::operationalBoundaryReceipt)
    }

    /** No production caller of advance/restore/requestStop: 10C-3 owns activation and truthful UI. */
    internal val incidentReconciliationCoordinator by lazy {
        com.journeycontinuity.app.data.repository.RoomIncidentReconciliationCoordinator(database,
            cloudGateways.incidentAuthority, cloudGateways.sync, cloudGateways.journeyStop,
            terminalHandoffGate = terminalHandoffGate,
            onStoppedEffects = { journeyId ->
                check(exactJourneyRuntimeTeardown.stop(journeyId)) { "Exact runtime teardown pending" }
                syncScheduler.schedule(SyncRequestUrgency.URGENT)
            }, requestRetry = { _, _ -> syncScheduler.schedule(SyncRequestUrgency.ROUTINE) })
    }

    private val exactJourneyRuntimeTeardown by lazy {
        com.journeycontinuity.app.service.ExactJourneyRuntimeTeardown(ownerAdmissionGate,
            { database.journeyDao().getActive()?.id }, journeyServiceController::stopExact)
    }

    private data class CloudGateways(
        val incidentAuthority: com.journeycontinuity.app.data.unresolved.IncidentAuthorityGateway =
            object : com.journeycontinuity.app.data.unresolved.IncidentAuthorityGateway {
                override suspend fun eligibility(journeyId: String) = unavailableIncidentAuthority()
                override suspend fun finalize(claim: com.journeycontinuity.app.domain.IncidentClaim,
                    beforeDispatch: suspend () -> Boolean) = unavailableIncidentAuthority()
                override suspend fun provenance(journeyId: String) = unavailableIncidentAuthority()
            },
        val journeyStop: com.journeycontinuity.app.auth.ActiveJourneyStopRemote = object : com.journeycontinuity.app.auth.ActiveJourneyStopRemote {
            override suspend fun stop(journeyId: String, ownerId: String): com.journeycontinuity.app.auth.MonitoringStopAcknowledgement =
                throw CloudSyncException(SyncFailureKind.PERMANENT, "Cloud configuration unavailable.")
            override suspend fun ownerJourneyState(journeyId: String, ownerId: String): com.journeycontinuity.app.sync.RemoteJourneyState? =
                throw CloudSyncException(SyncFailureKind.PERMANENT, "Cloud configuration unavailable.")
        },
        val unresolvedBoundary: com.journeycontinuity.app.data.unresolved.UnresolvedBoundaryGateway =
            com.journeycontinuity.app.data.unresolved.UnresolvedBoundaryGateway { null },
        val authBackend: com.journeycontinuity.app.auth.TravellerAuthBackend? = null,
        val ownerStore: com.journeycontinuity.app.auth.TravellerIdentityStore? = null,
        val accountEmail: () -> String? = { null },
        val identityCoordinator: TravellerIdentityCoordinator?,
        val account: com.journeycontinuity.app.auth.TravellerAccountGateway,
        val noActiveSignOut: TravellerNoActiveSignOutGateway? = null,
        val activeSignOut: TravellerActiveJourneySignOutGateway? = null,
        val sync: CloudSyncGateway,
        val heartbeat: HeartbeatGateway,
        val verificationGuard: ProbableEndVerificationGuardGateway = object : ProbableEndVerificationGuardGateway {
            override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) =
                VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.UNAVAILABLE)
        },
        val autoEnd: ProbableEndAutoEndGateway = object : ProbableEndAutoEndGateway {
            override suspend fun register(request: ProbableEndServerWindowRequest) = ProbableEndRegistration.Unknown
            override suspend fun finalize(journeyId: String, presentationEpisodeId: String) =
                ProbableEndFinalization.Unknown
        },
        val trustedContacts: TrustedContactGateway,
        val fallbackProvisioning: AuthenticatedFallbackProvisioningGateway,
        val destinationSearch: com.journeycontinuity.app.ui.destination.DestinationSearchGateway,
        val destinationMap: com.journeycontinuity.app.ui.destination.DestinationMapGateway =
            com.journeycontinuity.app.data.search.UnavailableDestinationMapGateway,
        val returningLogin: ReturningTravellerLoginEngine?,
        val createAccount: TravellerCreateAccountEngine?,
    )
}

private fun unavailableIncidentAuthority() = com.journeycontinuity.app.data.unresolved.AuthorityRead.Failed(
    com.journeycontinuity.app.domain.IncidentReconciliationOutcome.UNAVAILABLE)
