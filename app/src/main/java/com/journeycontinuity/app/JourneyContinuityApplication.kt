package com.journeycontinuity.app

import android.app.Application
import androidx.room.Room
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.local.MIGRATION_2_3
import com.journeycontinuity.app.data.local.MIGRATION_3_4
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.data.repository.RoomJourneyRepository
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.domain.DeviceHeartbeat
import com.journeycontinuity.app.auth.SharedPreferencesTravellerIdentityStore
import com.journeycontinuity.app.auth.SupabaseTravellerAuthBackend
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.heartbeat.HeartbeatCoordinator
import com.journeycontinuity.app.heartbeat.HeartbeatGateway
import com.journeycontinuity.app.heartbeat.HeartbeatServerState
import com.journeycontinuity.app.heartbeat.RoomHeartbeatLocalStore
import com.journeycontinuity.app.heartbeat.SupabaseHeartbeatGateway
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
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

class JourneyContinuityApplication : Application() {
    private val database: JourneyDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            JourneyDatabase::class.java,
            "journey-continuity.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }

    val syncScheduler by lazy { WorkManagerSyncScheduler(applicationContext) }

    val journeyRepository: JourneyRepository by lazy {
        RoomJourneyRepository(
            journeyDao = database.journeyDao(),
            telemetryDao = database.telemetryDao(),
            syncStateDao = database.syncStateDao(),
            heartbeatDao = database.heartbeatDao(),
            syncScheduler = syncScheduler,
        )
    }

    val journeyLifecycle: JourneyLifecycle by lazy {
        JourneyLifecycle(journeyRepository)
    }

    val journeyServiceController: JourneyServiceController by lazy {
        JourneyServiceController(applicationContext)
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
                val identityCoordinator = TravellerIdentityCoordinator(
                    backend = SupabaseTravellerAuthBackend(client),
                    identityStore = SharedPreferencesTravellerIdentityStore(applicationContext),
                    logger = logger,
                )
                CloudGateways(
                    sync = SupabaseCloudSyncGateway(client, identityCoordinator, logger),
                    heartbeat = SupabaseHeartbeatGateway(client, identityCoordinator, logger),
                    trustedContacts = SupabaseTrustedContactGateway(
                        client = client,
                        identityCoordinator = identityCoordinator,
                        trustedViewerBaseUrl = com.journeycontinuity.app.BuildConfig.TRUSTED_VIEWER_BASE_URL,
                        logger = logger,
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
        )
    }

    val heartbeatCoordinator: HeartbeatCoordinator by lazy {
        HeartbeatCoordinator(
            local = RoomHeartbeatLocalStore(database.heartbeatDao()),
            remote = cloudGateways.heartbeat,
            logger = AndroidSyncDiagnosticLogger,
        )
    }

    val trustedContactGateway: TrustedContactGateway
        get() = cloudGateways.trustedContacts

    override fun onCreate() {
        super.onCreate()
        // Re-evaluate durable requested state after process restart. An urgent wake can
        // bypass a legacy/retrying worker without cancelling work that is already running.
        runCatching { syncScheduler.schedule(SyncRequestUrgency.URGENT) }
    }

    private class ConfigurationFailureCloudGateway(
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
            sync = this,
            heartbeat = this,
            trustedContacts = UnavailableTrustedContactGateway(safeError),
        )
    }

    private data class CloudGateways(
        val sync: CloudSyncGateway,
        val heartbeat: HeartbeatGateway,
        val trustedContacts: TrustedContactGateway,
    )
}
