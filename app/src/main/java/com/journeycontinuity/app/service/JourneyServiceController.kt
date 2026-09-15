package com.journeycontinuity.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class JourneyServiceController(
    private val context: Context,
) {
    fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, JourneyForegroundService::class.java),
        )
    }

    fun stop() {
        context.stopService(Intent(context, JourneyForegroundService::class.java))
    }
}
