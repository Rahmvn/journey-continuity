package com.journeycontinuity.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.MainActivity
import com.journeycontinuity.app.R
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.telemetry.DeviceContextReader
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import com.journeycontinuity.app.telemetry.FusedJourneyLocationSource
import com.journeycontinuity.app.telemetry.LocationPrerequisites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class JourneyForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJourneyJob: Job? = null
    private var persistenceJob: Job? = null
    private var locationChannel: Channel<Location>? = null
    private var currentDestination: String? = null
    private lateinit var locationSource: FusedJourneyLocationSource
    private lateinit var deviceContextReader: DeviceContextReader
    private var receiverRegistered = false

    private val repository
        get() = (application as JourneyContinuityApplication).journeyRepository

    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!LocationPrerequisites.locationServicesEnabled(this@JourneyForegroundService)) {
                stopServiceCompletely()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationSource = FusedJourneyLocationSource(this)
        deviceContextReader = DeviceContextReader(this)
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            locationModeReceiver,
            IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPrerequisites()) {
            stopServiceCompletely()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.journey_notification_waiting)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        } catch (_: SecurityException) {
            stopServiceCompletely()
            return START_NOT_STICKY
        }

        currentDestination?.let(::showRecordingNotification)

        if (activeJourneyJob == null) {
            activeJourneyJob = serviceScope.launch {
                repository.activeJourney.collectLatest { journey ->
                    if (journey == null) {
                        currentDestination = null
                        stopServiceCompletely()
                    } else {
                        startCollecting(journey.id)
                        currentDestination = journey.destination
                        showRecordingNotification(journey.destination)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startCollecting(journeyId: String) {
        stopCollecting()
        val channel = Channel<Location>(Channel.UNLIMITED)
        locationChannel = channel
        persistenceJob = serviceScope.launch {
            for (location in channel) {
                val battery = deviceContextReader.battery()
                repository.recordTelemetry(
                    TelemetrySample(
                        journeyId = journeyId,
                        eventTime = location.time,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        batteryPercent = battery.percent,
                        isCharging = battery.isCharging,
                        connectivity = deviceContextReader.connectivity(),
                    ),
                )
            }
        }
        locationSource.start(
            onLocation = { location ->
                if (location.isUsableObservation()) channel.trySend(location)
            },
            onFailure = { stopServiceCompletely() },
        )
    }

    private fun stopCollecting() {
        locationSource.stop()
        locationChannel?.close()
        locationChannel = null
        persistenceJob?.cancel()
        persistenceJob = null
    }

    private fun showRecordingNotification(destination: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("Recording location for $destination"),
        )
    }

    private fun hasLocationPrerequisites(): Boolean =
        LocationPrerequisites.access(this) != ForegroundLocationAccess.NONE &&
            LocationPrerequisites.locationServicesEnabled(this)

    private fun stopServiceCompletely() {
        stopCollecting()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCollecting()
        activeJourneyJob?.cancel()
        if (receiverRegistered) {
            unregisterReceiver(locationModeReceiver)
            receiverRegistered = false
        }
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.journey_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.journey_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(body: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.journey_notification_title))
            .setContentText(body)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun Location.isUsableObservation(): Boolean =
        time > 0L &&
            hasAccuracy() &&
            latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            accuracy.isFinite() && accuracy >= 0f

    companion object {
        private const val CHANNEL_ID = "active_journey"
        private const val NOTIFICATION_ID = 1001
    }
}
