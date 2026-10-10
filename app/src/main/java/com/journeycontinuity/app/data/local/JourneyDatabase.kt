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
        JourneyDegradationStateEntity::class,
        JourneyFallbackBindingEntity::class,
        FallbackAttemptEntity::class,
        ProbableEndDetectorEntity::class,
        ProbableEndEpisodeEntity::class,
        ProbableEndGuardBindingEntity::class,
        ProbableEndCandidateContinuityEntity::class,
        ProbableEndPresentationEntity::class,
        UnresolvedBoundaryEntity::class,
        OperationalBoundaryEntity::class,
        JourneyTerminalReconciliationEntity::class,
        JourneyIncidentFinalizationEntity::class,
        JourneyAutoEndFinalizationEntity::class,
    ],
    version = 21,
    exportSchema = true,
)
@TypeConverters(
    JourneyStatusConverter::class,
    ConnectivityStateConverter::class,
    SyncPhaseConverter::class,
    CloudMonitoringPhaseConverter::class,
    DegradedConnectivityConverters::class,
)
abstract class JourneyDatabase : RoomDatabase() {
    abstract fun journeyDao(): JourneyDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun degradedConnectivityDao(): DegradedConnectivityDao
    abstract fun fallbackAttemptDao(): FallbackAttemptDao
    abstract fun ownerAdoptionStateDao(): OwnerAdoptionStateDao
    abstract fun probableEndDao(): ProbableEndDao
    abstract fun probableEndGuardDao(): ProbableEndGuardDao
    abstract fun probableEndPresentationDao(): ProbableEndPresentationDao
    abstract fun unresolvedBoundaryDao(): UnresolvedBoundaryDao
    abstract fun operationalBoundaryDao(): OperationalBoundaryDao
    abstract fun incidentReconciliationDao(): IncidentReconciliationDao
    abstract fun travellerAuthorityDao(): TravellerAuthorityDao
}
