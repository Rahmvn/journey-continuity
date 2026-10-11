package com.journeycontinuity.app.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import java.util.concurrent.CancellationException

class FusedJourneyLocationSource(
    context: Context,
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context),
    onRelease: (LocationReleaseEvidence) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = context.mainExecutor
    private val registrations = LocationRegistrationLifecycle<LocationCallback>(
        request = { callback, succeeded, failed ->
            client.requestLocationUpdates(LocationTrackingConfig.createRequest(), callback,
                Looper.getMainLooper()).addOnCompleteListener(mainExecutor) { task ->
                    when {
                        task.isSuccessful -> succeeded()
                        task.isCanceled -> failed(CancellationException("Location registration canceled"))
                        else -> failed(task.exception ?: IllegalStateException("Location registration failed"))
                    }
                }
        },
        remove = { callback, succeeded, failed ->
            client.removeLocationUpdates(callback).addOnCompleteListener(mainExecutor) { task ->
                when {
                    task.isSuccessful -> succeeded()
                    task.isCanceled -> failed(CancellationException("Location removal canceled"))
                    else -> failed(task.exception ?: IllegalStateException("Location removal failed"))
                }
            }
        },
        scheduleRetry = { delay, action -> main.postDelayed(action, delay) },
        onRelease = onRelease,
    )

    @SuppressLint("MissingPermission")
    fun start(
        owner: LocationRegistrationOwner,
        onLocation: (Location) -> Unit,
        onFailure: (Exception) -> Unit,
        onReady: () -> Unit = {},
    ) {
        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (registrations.isCurrent(owner, this)) result.locations.forEach(onLocation)
            }
        }
        registrations.start(owner, newCallback, onReady, onFailure)
    }

    fun stop(owner: LocationRegistrationOwner? = null) = registrations.stop(owner)
}
