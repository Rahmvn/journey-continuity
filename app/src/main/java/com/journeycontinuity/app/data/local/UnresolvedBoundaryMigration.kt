package com.journeycontinuity.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `unresolved_boundaries` (
            `journeyId` TEXT NOT NULL, `admissionId` TEXT NOT NULL,
            `admittedAtMillis` INTEGER NOT NULL, `etaMillis` INTEGER NOT NULL,
            `boundaryMillis` INTEGER NOT NULL, `policyVersion` TEXT NOT NULL,
            `observedOwnerId` TEXT, `serverAcceptedAtMillis` INTEGER, `serverObservedAtMillis` INTEGER,
            `eligibility` TEXT, `reason` TEXT, `verificationCaseId` TEXT, `openingEventId` INTEGER,
            `verifyingStartedAtMillis` INTEGER, `guardVersion` INTEGER, `historyBaselineAtMillis` INTEGER,
            `firstBoundaryProofAtMillis` INTEGER, `receiptElapsedRealtimeMillis` INTEGER,
            `receiptBootCount` INTEGER, `receiptInstallationId` TEXT, `revision` INTEGER NOT NULL,
            `receiptElapsedSpanMillis` INTEGER, `utcUncertaintyMillis` INTEGER,
            PRIMARY KEY(`journeyId`), FOREIGN KEY(`journeyId`) REFERENCES `journeys`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_unresolved_boundaries_admissionId` ON `unresolved_boundaries` (`admissionId`)")
        // Deliberately empty: a legacy ETA is not an admission-authority fact.
    }
}
