package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Response columns are nullable: historical presentations retain their original meaning. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `probable_end_presentations` ADD COLUMN `respondedAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `probable_end_presentations` ADD COLUMN `resultAcknowledgedAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `probable_end_presentations` ADD COLUMN `completionEffectsAppliedAtMillis` INTEGER")
    }
}
