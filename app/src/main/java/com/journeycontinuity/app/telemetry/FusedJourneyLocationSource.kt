package com.journeycontinuity.app.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

class FusedJourneyLocationSource(
    context: Context,
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context),
) {
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(
        onLocation: (Location) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        stop()
        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(onLocation)
            }
        }
        callback = newCallback
        client.requestLocationUpdates(
            LocationTrackingConfig.createRequest(),
            newCallback,
            Looper.getMainLooper(),
        ).addOnFailureListener { error ->
            if (callback === newCallback) {
                callback = null
                onFailure(error)
            }
        }
    }

    fun stop() {
        callback?.let(client::removeLocationUpdates)
        callback = null
    }
}
