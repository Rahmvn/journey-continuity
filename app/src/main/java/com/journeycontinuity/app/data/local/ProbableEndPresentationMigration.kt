package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive: no historical candidate becomes a presentation opportunity. */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `probable_end_presentations` (
            `id` TEXT NOT NULL, `journeyId` TEXT NOT NULL, `candidateEpisodeId` TEXT NOT NULL,
            `generation` INTEGER NOT NULL, `policyVersion` TEXT NOT NULL,
            `historyBaselineAtMillis` INTEGER NOT NULL, `guardVersion` INTEGER NOT NULL,
            `status` TEXT NOT NULL, `liveSlot` INTEGER, `createdAtMillis` INTEGER NOT NULL,
            `presentedAtMillis` INTEGER, `closedAtMillis` INTEGER, `closureReason` TEXT,
            `revision` INTEGER NOT NULL, PRIMARY KEY(`id`),
            FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`candidateEpisodeId`) REFERENCES `probable_end_episodes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_probable_end_presentations_candidateEpisodeId` ON `probable_end_presentations` (`candidateEpisodeId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_probable_end_presentations_journeyId_liveSlot` ON `probable_end_presentations` (`journeyId`, `liveSlot`)")
    }
}
