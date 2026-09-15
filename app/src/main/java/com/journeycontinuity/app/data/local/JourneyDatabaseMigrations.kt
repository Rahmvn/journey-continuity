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
