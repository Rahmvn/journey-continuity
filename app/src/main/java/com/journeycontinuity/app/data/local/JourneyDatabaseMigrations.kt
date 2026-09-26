package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `telemetry_observations` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `journeyId` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `eventTime` INTEGER NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `accuracyMeters` REAL NOT NULL,
                `batteryPercent` INTEGER,
                `isCharging` INTEGER,
                `connectivity` TEXT NOT NULL,
                FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_telemetry_observations_journeyId` " +
                "ON `telemetry_observations` (`journeyId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_telemetry_observations_journeyId_sequence` " +
                "ON `telemetry_observations` (`journeyId`, `sequence`)",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `journey_sync_states` (
                `journeyId` TEXT NOT NULL,
                `highestTelemetrySequenceSynced` INTEGER NOT NULL,
                `lastSuccessfulSyncAt` INTEGER,
                `lastAttemptAt` INTEGER,
                `phase` TEXT NOT NULL,
                `lastError` TEXT,
                `permanentlyBlocked` INTEGER NOT NULL,
                `changeVersion` INTEGER NOT NULL,
                `workRequested` INTEGER NOT NULL,
                PRIMARY KEY(`journeyId`),
                FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )""",
        )
        db.execSQL(
            """INSERT INTO `journey_sync_states` (
                `journeyId`, `highestTelemetrySequenceSynced`, `lastSuccessfulSyncAt`,
                `lastAttemptAt`, `phase`, `lastError`, `permanentlyBlocked`,
                `changeVersion`, `workRequested`
            )
            SELECT `id`, 0, NULL, NULL, 'PENDING', NULL, 0, 1, 1 FROM `journeys`""",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `journey_heartbeat_states` (
                `journeyId` TEXT NOT NULL,
                `lastAllocatedHeartbeatSequence` INTEGER NOT NULL,
                `latestCloudHeartbeatSequence` INTEGER NOT NULL,
                `lastHeartbeatAttemptAt` INTEGER,
                `lastSuccessfulHeartbeatAt` INTEGER,
                `monitoringPhase` TEXT,
                `lastCloudContactAt` INTEGER,
                `lastError` TEXT,
                PRIMARY KEY(`journeyId`),
                FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )""",
        )
        db.execSQL(
            """INSERT INTO `journey_heartbeat_states` (
                `journeyId`, `lastAllocatedHeartbeatSequence`, `latestCloudHeartbeatSequence`, `lastHeartbeatAttemptAt`,
                `lastSuccessfulHeartbeatAt`, `monitoringPhase`, `lastCloudContactAt`, `lastError`
            )
            SELECT `id`, 0, 0, NULL, NULL, NULL, NULL, NULL FROM `journeys`""",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `journey_degradation_states` (
                `journeyId` TEXT NOT NULL,
                `journeyActive` INTEGER NOT NULL,
                `connectivityPhase` TEXT NOT NULL,
                `fallbackDisposition` TEXT NOT NULL,
                `validatedInternetAvailable` INTEGER NOT NULL,
                `fallbackBindingProvisioned` INTEGER NOT NULL,
                `transportAvailable` INTEGER NOT NULL,
                `degradationEpisodeId` INTEGER,
                `lastDegradationEpisodeId` INTEGER NOT NULL,
                `interruptionStartedAtMillis` INTEGER,
                `lastAuthenticatedCloudSuccessAtMillis` INTEGER,
                `consecutiveRetryableCloudFailures` INTEGER NOT NULL,
                `recoveryStartedAtMillis` INTEGER,
                `nextFallbackEnvelopeSequence` INTEGER NOT NULL,
                `lastFallbackAttemptAtMillis` INTEGER,
                `lastFallbackAttemptEpisodeId` INTEGER,
                `lastCoveredTelemetrySequence` INTEGER,
                `rateWindowStartedAtMillis` INTEGER,
                `fallbackAttemptsInRateWindow` INTEGER NOT NULL,
                `latestTelemetrySequence` INTEGER,
                `latestBatteryPercent` INTEGER,
                PRIMARY KEY(`journeyId`),
                FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )""",
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `journey_fallback_bindings` (
                `journeyId` TEXT NOT NULL,
                `keyId` INTEGER NOT NULL CHECK(`keyId` BETWEEN 0 AND 4294967295),
                `journeyHandle` BLOB NOT NULL CHECK(length(`journeyHandle`) = 12),
                `status` TEXT NOT NULL CHECK(`status` IN ('PROVISIONED', 'REVOKED')),
                `provisionedAt` INTEGER NOT NULL,
                `revokedAt` INTEGER,
                PRIMARY KEY(`journeyId`),
                FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_journey_fallback_bindings_journeyHandle`
               ON `journey_fallback_bindings` (`journeyHandle`)""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `fallback_attempts` (
                `localAttemptId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `journeyId` TEXT NOT NULL,
                `degradationEpisodeId` INTEGER NOT NULL CHECK(`degradationEpisodeId` > 0),
                `envelopeSequence` INTEGER NOT NULL CHECK(`envelopeSequence` > 0),
                `telemetrySequence` INTEGER NOT NULL CHECK(`telemetrySequence` >= 0),
                `observationEventTime` INTEGER NOT NULL CHECK(`observationEventTime` >= 0),
                `eventType` TEXT NOT NULL CHECK(`eventType` IN ('OBSERVATION', 'JOURNEY_COMPLETED')),
                `protectedPayloadText` TEXT NOT NULL CHECK(length(`protectedPayloadText`) <= 160),
                `nonce` BLOB NOT NULL CHECK(length(`nonce`) = 12),
                `payloadSha256` BLOB NOT NULL CHECK(length(`payloadSha256`) = 32),
                `allocatedAt` INTEGER NOT NULL CHECK(`allocatedAt` >= 0),
                `transportState` TEXT NOT NULL CHECK(`transportState` IN ('ALLOCATED', 'HANDOFF_IN_PROGRESS', 'HANDED_OFF', 'RETRY_PENDING', 'PERMANENT_FAILURE', 'SUPERSEDED')),
                `transportAttemptCount` INTEGER NOT NULL CHECK(`transportAttemptCount` >= 0),
                `lastAttemptAt` INTEGER,
                `terminalAt` INTEGER,
                FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_fallback_attempts_journeyId_envelopeSequence`
               ON `fallback_attempts` (`journeyId`, `envelopeSequence`)""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_fallback_attempts_journeyId_degradationEpisodeId_telemetrySequence`
               ON `fallback_attempts` (`journeyId`, `degradationEpisodeId`, `telemetrySequence`)""",
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS `index_fallback_attempts_journeyId_transportState`
               ON `fallback_attempts` (`journeyId`, `transportState`)""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_fallback_attempts_journeyId_nonce`
               ON `fallback_attempts` (`journeyId`, `nonce`)""",
        )
        createFallbackAttemptInvariantTriggers(db)
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `fallback_attempts` ADD COLUMN `handoffGeneration` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `fallback_attempts` ADD COLUMN `handoffStartedAt` INTEGER")
        db.execSQL("ALTER TABLE `fallback_attempts` ADD COLUMN `nextRetryAt` INTEGER")
        db.execSQL("ALTER TABLE `fallback_attempts` ADD COLUMN `lastTransportOutcome` TEXT")
        db.execSQL("ALTER TABLE `fallback_attempts` ADD COLUMN `lastTransportResultCode` INTEGER")
        db.execSQL("ALTER TABLE `fallback_attempts` ADD COLUMN `uncertainSince` INTEGER")
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS `index_fallback_attempts_transportState_nextRetryAt`
               ON `fallback_attempts` (`transportState`, `nextRetryAt`)""",
        )
        createFallbackAttemptInvariantTriggers(db)
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE journey_degradation_states ADD COLUMN recoveryTargetTelemetrySequence INTEGER")
        db.execSQL("ALTER TABLE journey_degradation_states ADD COLUMN recoveryBacklogSatisfiedAtMillis INTEGER")
        db.execSQL("ALTER TABLE journey_sync_states ADD COLUMN legacyAuthorizationFailure TEXT")
        db.execSQL("ALTER TABLE journey_sync_states ADD COLUMN legacyAuthorizationFailureAtMillis INTEGER")
        db.execSQL("ALTER TABLE journey_sync_states ADD COLUMN legacyAuthorizationRecoveredAtMillis INTEGER")
        // Revalidate old HEALTHY claims with outstanding evidence. Never change attempt history.
        db.execSQL(
            """UPDATE journey_degradation_states
               SET connectivityPhase = 'RECOVERING', fallbackDisposition = 'INACTIVE',
                   recoveryStartedAtMillis = COALESCE(recoveryStartedAtMillis,
                       CAST(strftime('%s', 'now') AS INTEGER) * 1000),
                   recoveryTargetTelemetrySequence = COALESCE(
                       (SELECT MAX(sequence) FROM telemetry_observations t
                        WHERE t.journeyId = journey_degradation_states.journeyId), 0)
               WHERE journeyActive = 1 AND (connectivityPhase = 'RECOVERING' OR
                   (connectivityPhase = 'HEALTHY' AND COALESCE(
                       (SELECT highestTelemetrySequenceSynced FROM journey_sync_states s
                        WHERE s.journeyId = journey_degradation_states.journeyId), 0) < COALESCE(
                       (SELECT MAX(sequence) FROM telemetry_observations t
                        WHERE t.journeyId = journey_degradation_states.journeyId), 0)))""",
        )
    }
}

val FALLBACK_ATTEMPT_INVARIANT_CALLBACK = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        createFallbackAttemptInvariantTriggers(db)
    }
}

private fun createFallbackAttemptInvariantTriggers(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TRIGGER IF EXISTS `fallback_attempts_validate_insert`")
    db.execSQL("DROP TRIGGER IF EXISTS `fallback_attempts_validate_update`")
    db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS `fallback_attempts_validate_insert`
           BEFORE INSERT ON fallback_attempts
           WHEN NEW.degradationEpisodeId <= 0 OR NEW.envelopeSequence <= 0
             OR NEW.telemetrySequence < 0 OR NEW.observationEventTime < 0
             OR NEW.allocatedAt < 0 OR NEW.transportAttemptCount < 0 OR NEW.handoffGeneration < 0
             OR NEW.eventType NOT IN ('OBSERVATION', 'JOURNEY_COMPLETED')
             OR NEW.transportState NOT IN ('ALLOCATED', 'HANDOFF_IN_PROGRESS', 'HANDED_OFF',
                                           'RETRY_PENDING', 'PERMANENT_FAILURE', 'SUPERSEDED')
             OR length(NEW.protectedPayloadText) > 160
             OR substr(NEW.protectedPayloadText, 1, 4) <> 'JC1.'
             OR length(NEW.nonce) <> 12 OR length(NEW.payloadSha256) <> 32
             OR (NEW.lastTransportOutcome IS NOT NULL AND NEW.lastTransportOutcome NOT IN
                 ('ANDROID_HANDOFF_SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE',
                  'TRANSPORT_UNAVAILABLE', 'UNKNOWN_OUTCOME'))
           BEGIN
               SELECT RAISE(ABORT, 'invalid fallback attempt');
           END""",
    )
    db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS `fallback_attempts_immutable_payload`
           BEFORE UPDATE OF journeyId, degradationEpisodeId, envelopeSequence,
               telemetrySequence, observationEventTime, eventType, protectedPayloadText,
               nonce, payloadSha256, allocatedAt
           ON fallback_attempts
           BEGIN
               SELECT RAISE(ABORT, 'fallback attempt immutable fields cannot change');
           END""",
    )
    db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS `fallback_attempts_validate_update`
           BEFORE UPDATE ON fallback_attempts
           WHEN NEW.transportAttemptCount < 0 OR NEW.handoffGeneration < 0
             OR NEW.transportState NOT IN ('ALLOCATED', 'HANDOFF_IN_PROGRESS', 'HANDED_OFF',
                                           'RETRY_PENDING', 'PERMANENT_FAILURE', 'SUPERSEDED')
             OR (NEW.lastTransportOutcome IS NOT NULL AND NEW.lastTransportOutcome NOT IN
                 ('ANDROID_HANDOFF_SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE',
                  'TRANSPORT_UNAVAILABLE', 'UNKNOWN_OUTCOME'))
           BEGIN
               SELECT RAISE(ABORT, 'invalid fallback attempt state');
           END""",
    )
    db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS `fallback_bindings_validate_write`
           BEFORE INSERT ON journey_fallback_bindings
           WHEN NEW.keyId < 0 OR NEW.keyId > 4294967295 OR length(NEW.journeyHandle) <> 12
             OR NEW.status NOT IN ('PROVISIONED', 'REVOKED')
           BEGIN
               SELECT RAISE(ABORT, 'invalid fallback binding');
           END""",
    )
    db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS `fallback_bindings_validate_update`
           BEFORE UPDATE ON journey_fallback_bindings
           WHEN NEW.keyId < 0 OR NEW.keyId > 4294967295 OR length(NEW.journeyHandle) <> 12
             OR NEW.status NOT IN ('PROVISIONED', 'REVOKED')
           BEGIN
               SELECT RAISE(ABORT, 'invalid fallback binding');
           END""",
    )
}
