package com.journeycontinuity.app.telemetry

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

object LocationTrackingConfig {
    const val DESIRED_INTERVAL_MILLIS = 30_000L
    const val MINIMUM_INTERVAL_MILLIS = 15_000L

    fun createRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, DESIRED_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MINIMUM_INTERVAL_MILLIS)
            .build()
}
