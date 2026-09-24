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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.MainActivity
import com.journeycontinuity.app.R
import com.journeycontinuity.app.domain.TelemetrySample
import com.journeycontinuity.app.heartbeat.HeartbeatConfiguration
import com.journeycontinuity.app.heartbeat.HeartbeatAttemptResult
import com.journeycontinuity.app.sync.AndroidSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncRequestUrgency
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

class JourneyForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJourneyJob: Job? = null
    private var persistenceJob: Job? = null
    private var heartbeatJob: Job? = null
    private var heartbeatTimerJob: Job? = null
    private var heartbeatSignal: Channel<Unit>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var locationChannel: Channel<Location>? = null
    private var currentDestination: String? = null
    private lateinit var locationSource: FusedJourneyLocationSource
    private lateinit var deviceContextReader: DeviceContextReader
    private var receiverRegistered = false

    private val repository
        get() = (application as JourneyContinuityApplication).journeyRepository

    private val heartbeatCoordinator
        get() = (application as JourneyContinuityApplication).heartbeatCoordinator

    private val degradedConnectivityCoordinator
        get() = (application as JourneyContinuityApplication).degradedConnectivityCoordinator

    private val fallbackHandoffCoordinator
        get() = (application as JourneyContinuityApplication).fallbackHandoffCoordinator

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
        startHeartbeats(journeyId)
    }

    private fun startHeartbeats(journeyId: String) {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val degradedStateInitialized = CompletableDeferred<Unit>()
        heartbeatSignal = signal
        heartbeatJob = serviceScope.launch(Dispatchers.IO) {
            degradedConnectivityCoordinator.activate(
                journeyId = journeyId,
                validatedInternetAvailable = deviceContextReader.usableInternet(),
            )
            degradedStateInitialized.complete(Unit)
            for (ignored in signal) {
                val battery = deviceContextReader.battery()
                AndroidSyncDiagnosticLogger.info(
                    "Heartbeat battery snapshot: percentage=${battery.percent}, charging=${battery.isCharging}",
                )
                when (heartbeatCoordinator.sendFreshHeartbeat(
                    journeyId = journeyId,
                    batteryPercent = battery.percent,
                    charging = battery.isCharging,
                    connectivity = deviceContextReader.connectivity(),
                    networkUsable = deviceContextReader.usableInternet(),
                )) {
                    HeartbeatAttemptResult.Sent ->
                        degradedConnectivityCoordinator.freshHeartbeatSucceeded(journeyId)
                    HeartbeatAttemptResult.RetryableFailure ->
                        degradedConnectivityCoordinator.retryableCloudFailure(journeyId)
                    HeartbeatAttemptResult.Failed,
                    HeartbeatAttemptResult.Skipped,
                    -> Unit
                }
            }
        }
        heartbeatTimerJob = serviceScope.launch {
            degradedStateInitialized.await()
            while (isActive) {
                degradedConnectivityCoordinator.timeAdvanced(journeyId)
                fallbackHandoffCoordinator.processNextReady(journeyId)
                signal.trySend(Unit)
                delay(HeartbeatConfiguration.INTERVAL_MILLIS)
            }
        }
        val manager = getSystemService(ConnectivityManager::class.java)
        val usable = AtomicBoolean(deviceContextReader.usableInternet())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val nowUsable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (nowUsable && usable.compareAndSet(false, true)) {
                    serviceScope.launch(Dispatchers.IO) {
                        degradedConnectivityCoordinator.validatedInternetAvailable(journeyId)
                        runCatching {
                            (application as JourneyContinuityApplication).syncScheduler.schedule(
                                SyncRequestUrgency.URGENT,
                            )
                        }
                        signal.trySend(Unit)
                    }
                }
                if (!nowUsable && usable.compareAndSet(true, false)) {
                    serviceScope.launch(Dispatchers.IO) {
                        degradedConnectivityCoordinator.validatedInternetLost(journeyId)
                    }
                }
            }

            override fun onLost(network: Network) {
                if (usable.compareAndSet(true, false)) {
                    serviceScope.launch(Dispatchers.IO) {
                        degradedConnectivityCoordinator.validatedInternetLost(journeyId)
                    }
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { networkCallback = null }
    }

    private fun stopCollecting() {
        locationSource.stop()
        locationChannel?.close()
        locationChannel = null
        persistenceJob?.cancel()
        persistenceJob = null
        heartbeatTimerJob?.cancel()
        heartbeatTimerJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatSignal?.close()
        heartbeatSignal = null
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
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
