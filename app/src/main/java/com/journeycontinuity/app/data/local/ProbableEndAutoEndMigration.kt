package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Historical episodes gain no invented server window or automatic outcome. */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "serverWindowRegisteredAtMillis", "serverWindowNotBeforeAtMillis",
            "finalizationClaimedAtMillis", "automaticEndedAtMillis",
            "automaticEffectsAppliedAtMillis", "automaticResultAcknowledgedAtMillis",
        ).forEach { db.execSQL("ALTER TABLE `probable_end_presentations` ADD COLUMN `$it` INTEGER") }
    }
}
