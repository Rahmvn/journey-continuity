package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
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
