package com.journeycontinuity.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        JourneyEntity::class,
        TelemetryObservationEntity::class,
        JourneySyncStateEntity::class,
        JourneyHeartbeatStateEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(
    JourneyStatusConverter::class,
    ConnectivityStateConverter::class,
    SyncPhaseConverter::class,
    CloudMonitoringPhaseConverter::class,
)
abstract class JourneyDatabase : RoomDatabase() {
    abstract fun journeyDao(): JourneyDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun heartbeatDao(): HeartbeatDao
}
