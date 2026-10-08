package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Nullable columns preserve pre-v17 presentation history without inventing a window. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `probable_end_presentations` ADD COLUMN `confirmationDeadlineAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `probable_end_presentations` ADD COLUMN `deadlineStatus` TEXT")
        db.execSQL("ALTER TABLE `probable_end_presentations` ADD COLUMN `deadlineExpiredAtMillis` INTEGER")
    }
}
