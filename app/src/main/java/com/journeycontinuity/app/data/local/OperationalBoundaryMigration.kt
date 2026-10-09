package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `journey_operational_boundaries` (
            `journeyId` TEXT NOT NULL, `admissionId` TEXT NOT NULL, `state` TEXT NOT NULL,
            `pauseReason` TEXT, `lastAssessment` TEXT, `anchorElapsedRealtimeMillis` INTEGER,
            `anchorBootCount` INTEGER, `anchorInstallationId` TEXT,
            `pausedAtElapsedRealtimeMillis` INTEGER, `revision` INTEGER NOT NULL,
            PRIMARY KEY(`journeyId`), FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`)
            ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        // Existing Journeys receive no invented admission/boot anchor or operational pause.
    }
}
