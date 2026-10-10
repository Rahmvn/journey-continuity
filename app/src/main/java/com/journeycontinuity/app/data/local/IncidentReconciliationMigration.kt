package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `journey_terminal_reconciliations` (
            `journeyId` TEXT NOT NULL, `explicitActionId` TEXT, `explicitAction` TEXT,
            `explicitAtMillis` INTEGER, `explicitState` TEXT, `explicitOwnerId` TEXT,
            `claimJson` TEXT, `claimState` TEXT, `lastOutcome` TEXT, `retryAtMillis` INTEGER,
            `effectsPending` INTEGER NOT NULL, `claimId` TEXT, `claimDispatch` TEXT,
            `explicitDispatch` TEXT, `revision` INTEGER NOT NULL, PRIMARY KEY(`journeyId`),
            FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `journey_incident_finalizations` (
            `journeyId` TEXT NOT NULL, `provenanceJson` TEXT NOT NULL,
            `resultAcknowledgedAtMillis` INTEGER, PRIMARY KEY(`journeyId`),
            FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `journey_auto_end_finalizations` (
            `journeyId` TEXT NOT NULL, `provenanceJson` TEXT NOT NULL, PRIMARY KEY(`journeyId`),
            FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        // No invented action, claim, incident, automatic-end receipt or admission authority for existing rows.
    }
}
