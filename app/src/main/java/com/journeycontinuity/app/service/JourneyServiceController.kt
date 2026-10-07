package com.journeycontinuity.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

interface JourneyMonitoringController {
    fun start()
    fun stop()
}

class JourneyServiceController(
    private val context: Context,
) : JourneyMonitoringController {
    override fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, JourneyForegroundService::class.java),
        )
    }

    override fun stop() {
        context.stopService(Intent(context, JourneyForegroundService::class.java))
    }
}
