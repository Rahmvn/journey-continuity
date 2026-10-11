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
import com.journeycontinuity.app.domain.BoundaryAdmission
import com.journeycontinuity.app.domain.BoundaryReceipt
import com.journeycontinuity.app.domain.approximateOperationalCapReached
import com.journeycontinuity.app.heartbeat.HeartbeatConfiguration
import com.journeycontinuity.app.heartbeat.HeartbeatAttemptResult
import com.journeycontinuity.app.domain.probableend.GuardReconciliationTrigger
import com.journeycontinuity.app.sync.AndroidSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncRequestUrgency
import com.journeycontinuity.app.telemetry.DeviceContextReader
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import com.journeycontinuity.app.telemetry.FusedJourneyLocationSource
import com.journeycontinuity.app.telemetry.LocationRegistrationOwner
import com.journeycontinuity.app.telemetry.LocationReleaseEvidence
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

class JourneyForegroundService : Service() {
    private var serviceGeneration = 0L
    private var latestStartId = 0
    private var collectionGeneration: Long? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJourneyJob: Job? = null
    private var persistenceJob: Job? = null
    private var probableEndResumeJob: Job? = null
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

    private val journeyApp get() = application as JourneyContinuityApplication

    private data class BoundaryWatch(val admission: BoundaryAdmission, val anchor: BoundaryReceipt) {
        fun remaining(now: BoundaryReceipt): Long? {
            if (approximateOperationalCapReached(admission, anchor, now) == null) return null
            return (admission.boundaryMillis - admission.admittedAtMillis) -
                (now.elapsedRealtimeMillis - anchor.elapsedRealtimeMillis)
        }
    }

    private suspend fun preflight(journeyId: String): BoundaryWatch? {
        if (!assessBoundary(journeyId)) return null
        val (admission, anchor) = journeyApp.monitoringBoundaryWatch(journeyId) ?: return null
        val watch = BoundaryWatch(admission, anchor)
        return watch.takeIf { it.remaining(journeyApp.operationalBoundaryReceipt())?.let { remaining -> remaining > 0 } == true }
    }

    private suspend fun assessBoundary(journeyId: String): Boolean = try {
        journeyApp.evaluateMonitoringBoundary(journeyId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A failed Room preflight cannot grant collection permission.
        journeyApp.stopMonitoringExact(journeyId)
        false
    }

    private fun callbackMayCollect(journeyId: String, registration: Long, watch: BoundaryWatch): Boolean {
        if (!runtimeSignals.isRegistrationCurrent(registration, journeyId)) return false
        val remaining = runCatching { watch.remaining(journeyApp.operationalBoundaryReceipt()) }.getOrNull()
        if (remaining != null && remaining > 0) return true
        serviceScope.launch { assessBoundary(journeyId) }
        return false
    }

    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!LocationPrerequisites.locationServicesEnabled(this@JourneyForegroundService)) {
                stopServiceCompletely()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceGeneration = runtimeSignals.serviceStarted()
        liveService = this
        locationSource = FusedJourneyLocationSource(this, onRelease = { evidence ->
            lastLocationReleaseMutable.value = evidence
            if (evidence is LocationReleaseEvidence.RemovalFailed)
                AndroidSyncDiagnosticLogger.warning("Location removal failed; the old callback remains disabled.")
        })
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
        latestStartId = startId
        runtimeSignals.startCommand(startId)
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
        } catch (_: IllegalStateException) {
            // Android can refuse a location foreground service during a background restart.
            // Keep the Journey durable; a later visible entry rechecks its boundary.
            stopServiceCompletely()
            return START_NOT_STICKY
        }

        currentDestination?.let(::showRecordingNotification)

        if (activeJourneyJob == null) {
            activeJourneyJob = serviceScope.launch {
                (application as JourneyContinuityApplication).activeMonitoringJourney.collectLatest { journey ->
                    if (journey == null) {
                        currentDestination = null
                        // Room's collectable projection drops a committed privacy pause. This
                        // service-owned observer survives cancellation of the evaluating worker.
                        // Recheck the exact runtime's durable state before touching it: a stale
                        // null emission from A must never stop a newly admitted B.
                        runtimeSignals.activeRuntimeJourneyId.value?.let { runtimeJourneyId ->
                            if (!journeyApp.mayStartContinuousMonitoring(runtimeJourneyId))
                                journeyApp.stopMonitoringExact(runtimeJourneyId)
                        }
                        journeyApp.stopMonitoringServiceIfIdle(::stopServiceCompletely)
                    } else {
                        val watch = preflight(journey.id)
                        if (watch == null) {
                            journeyApp.stopMonitoringExact(journey.id)
                            journeyApp.stopMonitoringServiceIfIdle(::stopServiceCompletely)
                            return@collectLatest
                        }
                        startCollecting(journey.id, watch)
                        currentDestination = journey.destination
                        showRecordingNotification(journey.destination)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startCollecting(journeyId: String, watch: BoundaryWatch) {
        stopCollecting()
        val registration = runtimeSignals.begin(journeyId)
        collectionGeneration = registration
        val channel = Channel<Location>(Channel.UNLIMITED)
        locationChannel = channel
        persistenceJob = serviceScope.launch {
            for (location in channel) {
                if (!runtimeSignals.isRegistrationCurrent(registration, journeyId) ||
                    !assessBoundary(journeyId)) break
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
        if (!callbackMayCollect(journeyId, registration, watch)) return
        locationSource.start(
            owner = LocationRegistrationOwner(journeyId, registration),
            onLocation = { location ->
                if (callbackMayCollect(journeyId, registration, watch) && location.isUsableObservation())
                    channel.trySend(location)
            },
            onFailure = {
                if (runtimeSignals.isRegistrationCurrent(registration, journeyId))
                    serviceScope.launch { journeyApp.stopMonitoringExact(journeyId) }
            },
            onReady = { runtimeSignals.listenerRegistered(registration, journeyId) },
        )
        // Historical catch-up stays off the fresh telemetry channel and is canceled on teardown.
        probableEndResumeJob = serviceScope.launch(Dispatchers.IO) {
            if (assessBoundary(journeyId)) journeyApp.resumeLocalProbableEnd(journeyId, drain = true)
        }
        startHeartbeats(journeyId, registration, watch)
    }

    private fun startHeartbeats(journeyId: String, registration: Long, watch: BoundaryWatch) {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val degradedStateInitialized = CompletableDeferred<Unit>()
        heartbeatSignal = signal
        heartbeatJob = serviceScope.launch(Dispatchers.IO) {
            if (!assessBoundary(journeyId)) return@launch
            val initiallyUsable = deviceContextReader.usableInternet()
            degradedConnectivityCoordinator.activate(
                journeyId = journeyId,
                validatedInternetAvailable = initiallyUsable,
            )
            val currentlyUsable = deviceContextReader.usableInternet()
            if (currentlyUsable != initiallyUsable) {
                if (currentlyUsable) {
                    degradedConnectivityCoordinator.validatedInternetAvailable(journeyId)
                } else {
                    degradedConnectivityCoordinator.validatedInternetLost(journeyId)
                }
            }
            runtimeSignals.initialConnectivityReconciled(
                registration, journeyId, currentlyUsable,
            )
            degradedStateInitialized.complete(Unit)
            for (ignored in signal) {
                if (!runtimeSignals.isRegistrationCurrent(registration, journeyId) ||
                    !assessBoundary(journeyId)) break
                // A cloud failure can interrupt recovery without a NetworkCallback transition.
                // Reconcile the currently validated network before capturing the heartbeat start.
                val usableInternet = deviceContextReader.usableInternet()
                if (usableInternet) {
                    degradedConnectivityCoordinator.validatedInternetAvailable(journeyId)
                }
                runtimeSignals.connectivityReconciled(
                    registration, journeyId, deviceContextReader.usableInternet(),
                )
                degradedConnectivityCoordinator.timeAdvanced(journeyId)
                val heartbeatStartedAt = System.currentTimeMillis()
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
                    permitNow = {
                        runtimeSignals.isRegistrationCurrent(registration, journeyId) &&
                            runCatching { watch.remaining(journeyApp.operationalBoundaryReceipt()) }
                                .getOrNull()?.let { it > 0 } == true
                    },
                )) {
                    HeartbeatAttemptResult.Sent -> {
                        degradedConnectivityCoordinator.freshHeartbeatSucceeded(journeyId, heartbeatStartedAt)
                        if (callbackMayCollect(journeyId, registration, watch))
                            (application as JourneyContinuityApplication).requestProbableEndGuard(
                                journeyId, GuardReconciliationTrigger.HEARTBEAT_SUCCEEDED)
                    }
                    HeartbeatAttemptResult.RetryableFailure -> {
                        runtimeSignals.connectivityReconciled(registration, journeyId, false)
                        degradedConnectivityCoordinator.retryableCloudFailure(journeyId)
                    }
                    HeartbeatAttemptResult.Failed,
                    HeartbeatAttemptResult.Skipped,
                    -> Unit
                }
                if (!callbackMayCollect(journeyId, registration, watch)) break
            }
        }
        heartbeatTimerJob = serviceScope.launch {
            degradedStateInitialized.await()
            while (isActive) {
                if (!runtimeSignals.isRegistrationCurrent(registration, journeyId) ||
                    !assessBoundary(journeyId)) break
                degradedConnectivityCoordinator.timeAdvanced(journeyId)
                fallbackHandoffCoordinator.processNextReady(journeyId)
                signal.trySend(Unit)
                val remaining = runCatching { watch.remaining(journeyApp.operationalBoundaryReceipt()) }.getOrNull()
                delay(minOf(HeartbeatConfiguration.INTERVAL_MILLIS, (remaining ?: 0L).coerceAtLeast(1L)))
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
                        if (!runtimeSignals.isRegistrationCurrent(registration, journeyId) ||
                            !assessBoundary(journeyId)) return@launch
                        degradedConnectivityCoordinator.validatedInternetAvailable(journeyId)
                        runtimeSignals.connectivityReconciled(
                            registration, journeyId, deviceContextReader.usableInternet(),
                        )
                        runCatching {
                            (application as JourneyContinuityApplication).syncScheduler.schedule(
                                SyncRequestUrgency.URGENT,
                            )
                        }
                        signal.trySend(Unit)
                    }
                }
                if (!nowUsable) {
                    runtimeSignals.connectivityReconciled(registration, journeyId, false)
                    if (usable.compareAndSet(true, false)) {
                        serviceScope.launch(Dispatchers.IO) {
                            if (!runtimeSignals.isRegistrationCurrent(registration, journeyId) ||
                                !assessBoundary(journeyId)) return@launch
                            degradedConnectivityCoordinator.validatedInternetLost(journeyId)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                runtimeSignals.connectivityReconciled(registration, journeyId, false)
                if (usable.compareAndSet(true, false)) {
                    serviceScope.launch(Dispatchers.IO) {
                        if (!runtimeSignals.isRegistrationCurrent(registration, journeyId) ||
                            !assessBoundary(journeyId)) return@launch
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
        probableEndResumeJob?.cancel()
        probableEndResumeJob = null
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
        // Publish teardown only after location, heartbeat and network work has been stopped.
        runtimeSignals.stopRegistration(collectionGeneration)
        collectionGeneration = null
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
        stopSelfResult(latestStartId)
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
        if (liveService === this) liveService = null
        runtimeSignals.serviceStopped(serviceGeneration)
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
        private val lastLocationReleaseMutable = MutableStateFlow<LocationReleaseEvidence?>(null)
        val lastLocationRelease: StateFlow<LocationReleaseEvidence?> = lastLocationReleaseMutable
        private var liveService: JourneyForegroundService? = null
        /** Process-local evidence that the location foreground service started for this Journey. */
        private val runtimeSignals = JourneyRuntimeSignals()
        val monitoringJourneyId: StateFlow<String?> = runtimeSignals.monitoringJourneyId
        val activeRuntimeJourneyId: StateFlow<String?> = runtimeSignals.activeRuntimeJourneyId
        val serviceRunning: StateFlow<Boolean> = runtimeSignals.serviceRunning
        val currentConnectivity: StateFlow<CurrentJourneyConnectivity?> =
            runtimeSignals.currentConnectivity
        val isMonitoringInThisProcess: Boolean
            get() = monitoringJourneyId.value != null
        /** Exact asynchronous stop; never sends a global stopService request. */
        internal suspend fun stopExact(journeyId: String): Boolean {
            val ticket = runtimeSignals.stopTicket(journeyId)
                ?: return !serviceRunning.value || activeRuntimeJourneyId.value?.let { it != journeyId } == true
            val accepted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val service = liveService ?: return@withContext true
                runtimeSignals.executeStop(ticket, service::stopSelfResult) {
                    service.stopCollecting()
                    ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
            if (!accepted) return false
            return kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                kotlinx.coroutines.flow.combine(activeRuntimeJourneyId, serviceRunning) { id, running ->
                    !running || id != null && id != journeyId
                }.first { it }
                true
            } == true
        }

        /** Close the exact listener immediately; a newer Android start ID retains its service. */
        internal suspend fun stopPrivacyExact(journeyId: String): Boolean {
            val ticket = runtimeSignals.stopTicket(journeyId)
                ?: return activeRuntimeJourneyId.value != journeyId
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val service = liveService ?: return@withContext true
                runtimeSignals.executePrivacyStop(ticket,
                    stopCollection = { service.stopCollecting() },
                    stopSelfResult = service::stopSelfResult,
                    stopForeground = {
                        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    })
            }
        }
        private const val CHANNEL_ID = "active_journey"
        private const val NOTIFICATION_ID = 1001
    }
}
