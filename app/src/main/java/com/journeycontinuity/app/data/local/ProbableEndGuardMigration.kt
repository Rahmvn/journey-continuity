package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** No historical receipts or continuity claims are fabricated. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `probable_end_guard_bindings` (
            `journeyId` TEXT NOT NULL, `status` TEXT NOT NULL, `historyBaselineAtMillis` INTEGER NOT NULL,
            `guardVersion` INTEGER NOT NULL, `establishedServerAtMillis` INTEGER NOT NULL,
            `evidenceFloorSequence` INTEGER NOT NULL, `evidenceFloorEventTimeMillis` INTEGER NOT NULL,
            `generation` INTEGER NOT NULL, `revision` INTEGER NOT NULL,
            `lastServerEvaluatedAtMillis` INTEGER NOT NULL, `lastEvidenceNotBeforeMillis` INTEGER NOT NULL,
            `lastFreshUntilMillis` INTEGER, `lastDecision` TEXT NOT NULL, `lastVerification` TEXT NOT NULL, `lastReason` TEXT NOT NULL, `lastAttemptAtMillis` INTEGER,
            PRIMARY KEY(`journeyId`), FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `probable_end_candidate_continuity` (
            `episodeId` TEXT NOT NULL, `journeyId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `status` TEXT NOT NULL,
            `attachedHistoryBaselineAtMillis` INTEGER, `attachedGuardVersion` INTEGER, `requestRevision` INTEGER NOT NULL,
            `attemptedAtMillis` INTEGER, `checkedHistoryBaselineAtMillis` INTEGER, `checkedGuardVersion` INTEGER,
            `checkedServerAtMillis` INTEGER, `lastUnavailableReason` TEXT,
            PRIMARY KEY(`episodeId`), FOREIGN KEY(`episodeId`) REFERENCES `probable_end_episodes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_probable_end_candidate_continuity_journeyId` ON `probable_end_candidate_continuity` (`journeyId`)")
    }
}
