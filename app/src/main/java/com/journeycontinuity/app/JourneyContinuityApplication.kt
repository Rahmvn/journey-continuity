package com.journeycontinuity.app

import android.app.Application
import androidx.room.Room
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.MIGRATION_1_2
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.data.repository.RoomJourneyRepository
import com.journeycontinuity.app.domain.JourneyLifecycle
import com.journeycontinuity.app.service.JourneyServiceController

class JourneyContinuityApplication : Application() {
    private val database: JourneyDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            JourneyDatabase::class.java,
            "journey-continuity.db",
        ).addMigrations(MIGRATION_1_2).build()
    }

    val journeyRepository: JourneyRepository by lazy {
        RoomJourneyRepository(database.journeyDao(), database.telemetryDao())
    }

    val journeyLifecycle: JourneyLifecycle by lazy {
        JourneyLifecycle(journeyRepository)
    }

    val journeyServiceController: JourneyServiceController by lazy {
        JourneyServiceController(applicationContext)
    }
}
