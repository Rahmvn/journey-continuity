package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: no Journey/telemetry updates and no inferred historical backfill. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `probable_end_detector_states` (
            `journeyId` TEXT NOT NULL, `journeyStartedAtMillis` INTEGER NOT NULL,
            `policyVersion` TEXT NOT NULL, `generation` INTEGER NOT NULL,
            `evidenceFloorSequence` INTEGER NOT NULL, `evidenceFloorEventTimeMillis` INTEGER NOT NULL,
            `consumedSequence` INTEGER NOT NULL,
            `last_telemetrySequence` INTEGER, `last_observedAtMillis` INTEGER, `last_fingerprint` TEXT,
            `proximity_firstSequence` INTEGER, `proximity_firstTime` INTEGER, `proximity_firstFingerprint` TEXT,
            `proximity_latestSequence` INTEGER, `proximity_latestTime` INTEGER, `proximity_latestFingerprint` TEXT,
            `proximity_count` INTEGER,
            `exit_firstSequence` INTEGER, `exit_firstTime` INTEGER, `exit_firstFingerprint` TEXT,
            `exit_latestSequence` INTEGER, `exit_latestTime` INTEGER, `exit_latestFingerprint` TEXT, `exit_count` INTEGER,
            `requiresReliableExit` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `lastEvaluationAtMillis` INTEGER NOT NULL,
            PRIMARY KEY(`journeyId`), FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `probable_end_episodes` (
            `id` TEXT NOT NULL, `journeyId` TEXT NOT NULL, `policyVersion` TEXT NOT NULL,
            `evidenceClass` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `generation` INTEGER NOT NULL,
            `evidenceFloorSequence` INTEGER NOT NULL, `evidenceFloorEventTimeMillis` INTEGER NOT NULL,
            `lifecycle` TEXT NOT NULL, `liveSlot` INTEGER,
            `first_telemetrySequence` INTEGER NOT NULL, `first_observedAtMillis` INTEGER NOT NULL, `first_fingerprint` TEXT NOT NULL,
            `final_telemetrySequence` INTEGER NOT NULL, `final_observedAtMillis` INTEGER NOT NULL, `final_fingerprint` TEXT NOT NULL,
            `observationCount` INTEGER NOT NULL, `finalDistanceMeters` REAL NOT NULL, `finalAccuracyMeters` REAL NOT NULL,
            `destinationSlot` TEXT NOT NULL, `closedAtMillis` INTEGER, `closureReason` TEXT,
            PRIMARY KEY(`id`), FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_probable_end_episodes_journeyId_generation` ON `probable_end_episodes` (`journeyId`, `generation`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_probable_end_episodes_journeyId_liveSlot` ON `probable_end_episodes` (`journeyId`, `liveSlot`)")
    }
}
