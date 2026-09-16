package com.journeycontinuity.app

import android.app.Application
import androidx.room.Room
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.local.MIGRATION_2_3
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.data.repository.RoomJourneyRepository
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.service.JourneyServiceController
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.CloudSyncGateway
import com.journeycontinuity.app.sync.AndroidSyncDiagnosticLogger
import com.journeycontinuity.app.sync.ReliableSyncEngine
import com.journeycontinuity.app.sync.RoomLocalSyncStore
import com.journeycontinuity.app.sync.SupabaseCloudSyncGateway
import com.journeycontinuity.app.sync.SupabaseConfiguration
import com.journeycontinuity.app.sync.SyncFailureKind
import com.journeycontinuity.app.sync.WorkManagerSyncScheduler
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

class JourneyContinuityApplication : Application() {
    private val database: JourneyDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            JourneyDatabase::class.java,
            "journey-continuity.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }

    private val syncScheduler by lazy { WorkManagerSyncScheduler(applicationContext) }

    val journeyRepository: JourneyRepository by lazy {
        RoomJourneyRepository(
            journeyDao = database.journeyDao(),
            telemetryDao = database.telemetryDao(),
            syncStateDao = database.syncStateDao(),
            syncScheduler = syncScheduler,
        )
    }

    val journeyLifecycle: JourneyLifecycle by lazy {
        JourneyLifecycle(journeyRepository)
    }

    val journeyServiceController: JourneyServiceController by lazy {
        JourneyServiceController(applicationContext)
    }

    val syncEngine: ReliableSyncEngine by lazy {
        val logger = AndroidSyncDiagnosticLogger
        val configuration = SupabaseConfiguration.fromBuildConfig()
        logger.info("Supabase URL configured: ${configuration.url.isNotBlank()}")
        logger.info("Supabase URL parses as HTTPS: ${configuration.isHttpsUrl}")
        logger.info("Supabase hostname/project-ref shape valid: ${configuration.hasExpectedProjectHostShape}")
        logger.info("Publishable key configured: ${configuration.publishableKey.isNotBlank()}")
        logger.info("Publishable key category: ${configuration.keyCategory}")
        val configurationError = configuration.validationError
        val remote = if (configurationError == null) {
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
                SupabaseCloudSyncGateway(client, logger)
            } catch (error: Throwable) {
                val exceptionName = error::class.simpleName ?: "Exception"
                val safeError = "Supabase client initialization failed ($exceptionName)."
                logger.warning(safeError)
                ConfigurationFailureCloudGateway(safeError)
            }
        } else {
            logger.warning("Supabase configuration invalid: $configurationError")
            ConfigurationFailureCloudGateway(configurationError)
        }
        ReliableSyncEngine(
            local = RoomLocalSyncStore(
                journeyDao = database.journeyDao(),
                telemetryDao = database.telemetryDao(),
                syncStateDao = database.syncStateDao(),
            ),
            remote = remote,
            logger = logger,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Re-enqueue any durable requested state after an app/process restart. KEEP
        // coalesces this with an already-persisted WorkManager request.
        runCatching(syncScheduler::schedule)
    }

    private class ConfigurationFailureCloudGateway(
        private val safeError: String,
    ) : CloudSyncGateway {
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
    }
}
