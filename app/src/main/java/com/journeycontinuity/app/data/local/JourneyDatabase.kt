package com.journeycontinuity.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [JourneyEntity::class, TelemetryObservationEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(JourneyStatusConverter::class, ConnectivityStateConverter::class)
abstract class JourneyDatabase : RoomDatabase() {
    abstract fun journeyDao(): JourneyDao
    abstract fun telemetryDao(): TelemetryDao
}
