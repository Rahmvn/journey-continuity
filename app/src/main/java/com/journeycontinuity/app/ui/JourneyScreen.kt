package com.journeycontinuity.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.CloudMonitoringState
import com.journeycontinuity.app.domain.JourneySyncState
import com.journeycontinuity.app.domain.SyncPhase
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import com.journeycontinuity.app.trusted.TrustedContactStatus
import com.journeycontinuity.app.trusted.TrustedContactSummary
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val dateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy • HH:mm")

data class LocationUiState(
    val access: ForegroundLocationAccess = ForegroundLocationAccess.NONE,
    val locationServicesEnabled: Boolean = true,
)

@Composable
fun JourneyScreen(
    viewModel: JourneyViewModel,
    notificationsVisible: Boolean,
    locationUiState: LocationUiState,
    onStartRequested: (String, Long) -> Unit,
    onRetryMonitoring: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    smsFallbackStatus: SmsFallbackStatus,
    onRequestSmsPermissions: () -> Unit,
    onSelectSmsSubscription: (Int) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "JourneyContinuity",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            when {
                state.isRestoring -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Restoring saved journey…", Modifier.padding(start = 16.dp))
                }
                state.activeJourney != null -> ActiveJourneyContent(
                    journey = checkNotNull(state.activeJourney),
                    notificationsVisible = notificationsVisible,
                    locationUiState = locationUiState,
                    telemetryCount = state.telemetryCount,
                    latestTelemetry = state.latestTelemetry,
                    syncState = state.syncState,
                    monitoringState = state.monitoringState,
                    actionInProgress = state.isActionInProgress,
                    onEnd = viewModel::endJourney,
                    onRetryMonitoring = onRetryMonitoring,
                    onOpenLocationSettings = onOpenLocationSettings,
                )
                else -> StartJourneyContent(
                    actionInProgress = state.isActionInProgress,
                    locationServicesEnabled = locationUiState.locationServicesEnabled,
                    onStartRequested = onStartRequested,
                    onOpenLocationSettings = onOpenLocationSettings,
                )
            }
            SmsFallbackSection(
                status = smsFallbackStatus,
                onRequestPermissions = onRequestSmsPermissions,
                onSelectSubscription = onSelectSmsSubscription,
            )
            TrustedContactsSection(
                contacts = state.trustedContacts,
                availability = state.trustedContactsAvailability,
                unavailableMessage = state.trustedContactsUnavailableMessage,
                actionInProgress = state.trustedContactActionInProgress,
                invitationShareUrl = state.invitationShareUrl,
                onCreate = viewModel::createTrustedContact,
                onRevoke = viewModel::revokeTrustedContact,
                onRefresh = viewModel::refreshTrustedContacts,
                onShared = viewModel::clearInvitationShareUrl,
                onMessage = viewModel::showMessage,
            )
        }
    }
}

@Composable
private fun SmsFallbackSection(
    status: SmsFallbackStatus,
    onRequestPermissions: () -> Unit,
    onSelectSubscription: (Int) -> Unit,
) {
    Spacer(Modifier.height(28.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))
    Text("SMS fallback", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    JourneyDetail("SMS permission", if (status.sendPermissionGranted) "Granted" else "Not granted")
    JourneyDetail(
        "Phone/subscription permission",
        if (status.phoneStatePermissionGranted) "Granted" else "Not granted",
    )
    if (!status.sendPermissionGranted || !status.phoneStatePermissionGranted) {
        OutlinedButton(onClick = onRequestPermissions) { Text("Enable SMS fallback permissions") }
        Spacer(Modifier.height(10.dp))
    }
    Text("Fallback SIM", style = MaterialTheme.typography.labelMedium)
    if (status.activeSubscriptions.isEmpty()) {
        Text("No active SIM choices available.", style = MaterialTheme.typography.bodyLarge)
    } else {
        status.activeSubscriptions.forEach { choice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = choice.subscriptionId == status.selectedSubscriptionId,
                    onClick = { onSelectSubscription(choice.subscriptionId) },
                )
                Text(choice.safeDisplayName)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    JourneyDetail(
        "Destination",
        if (status.destinationConfigured) "Configured" else "Not configured",
    )
    JourneyDetail("Transport", if (status.ready) "Ready" else "Unavailable")
    status.unavailableReason?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "SMS transport status does not indicate recipient delivery or fresh cloud evidence.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun TrustedContactsSection(
    contacts: List<TrustedContactSummary>,
    availability: TrustedContactsAvailability,
    unavailableMessage: String?,
    actionInProgress: Boolean,
    invitationShareUrl: String?,
    onCreate: (String, String) -> Unit,
    onRevoke: (String) -> Unit,
    onRefresh: () -> Unit,
    onShared: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    Spacer(Modifier.height(28.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))
    Text("Trusted contacts", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        "Accepted contacts are authorized explicitly for each new Journey. Precise device evidence is disclosed only while contact is being verified, or briefly after a case closes.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = displayName,
        onValueChange = { displayName = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Contact display name") },
        singleLine = true,
        enabled = !actionInProgress && availability == TrustedContactsAvailability.AVAILABLE,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Verified email") },
        singleLine = true,
        enabled = !actionInProgress && availability == TrustedContactsAvailability.AVAILABLE,
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { onCreate(displayName, email) },
        enabled = !actionInProgress && availability == TrustedContactsAvailability.AVAILABLE,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (actionInProgress) "Working…" else "Create invitation") }

    invitationShareUrl?.let { url ->
        Spacer(Modifier.height(14.dp))
        Text(
            "This one-time invitation link is shown only now. Send it only to the invited contact.",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("JourneyContinuity invitation", url))
                onMessage("Invitation link copied.")
            }) { Text("Copy link") }
            OutlinedButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "You have been invited as a JourneyContinuity trusted contact. Open this one-time link: $url",
                    )
                }
                context.startActivity(Intent.createChooser(intent, "Share trusted-contact invitation"))
                onShared()
            }) { Text("Share link") }
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Existing contacts", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = onRefresh,
            enabled = availability != TrustedContactsAvailability.LOADING && !actionInProgress,
        ) {
            Text("Refresh")
        }
    }
    when {
        availability == TrustedContactsAvailability.LOADING ->
            Text("Loading trusted contacts…", style = MaterialTheme.typography.bodySmall)
        availability == TrustedContactsAvailability.UNAVAILABLE -> Text(
            unavailableMessage ?: "Trusted contacts are currently unavailable.",
            style = MaterialTheme.typography.bodySmall,
        )
        contacts.isEmpty() -> Text("No trusted contacts yet.", style = MaterialTheme.typography.bodySmall)
        else -> contacts.forEach { contact ->
            TrustedContactRow(
                contact = contact,
                actionInProgress = actionInProgress,
                onRevoke = onRevoke,
            )
        }
    }
}

@Composable
private fun TrustedContactRow(
    contact: TrustedContactSummary,
    actionInProgress: Boolean,
    onRevoke: (String) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    Spacer(Modifier.height(12.dp))
    Text(contact.displayName, style = MaterialTheme.typography.titleSmall)
    Text(contact.email, style = MaterialTheme.typography.bodyMedium)
    JourneyDetail("Status", contact.status.name.lowercase().replaceFirstChar(Char::uppercase))
    contact.expiresAt?.let { JourneyDetail("Invitation expires", formatTimestamp(it, zone)) }
    if (contact.status == TrustedContactStatus.PENDING || contact.status == TrustedContactStatus.ACCEPTED) {
        OutlinedButton(
            onClick = { onRevoke(contact.id) },
            enabled = !actionInProgress,
        ) { Text("Revoke") }
    }
}

@Composable
private fun StartJourneyContent(
    actionInProgress: Boolean,
    locationServicesEnabled: Boolean,
    onStartRequested: (String, Long) -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var destination by rememberSaveable { mutableStateOf("") }
    var etaMillis by rememberSaveable {
        mutableLongStateOf(System.currentTimeMillis() + 60 * 60 * 1_000L)
    }

    Spacer(Modifier.height(24.dp))
    Text("Start a local journey session", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = destination,
        onValueChange = { destination = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Destination") },
        singleLine = true,
        enabled = !actionInProgress,
    )
    Spacer(Modifier.height(16.dp))
    Text("Expected arrival", style = MaterialTheme.typography.labelLarge)
    Text(formatTimestamp(etaMillis, zone), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = !actionInProgress,
            onClick = {
                val current = Instant.ofEpochMilli(etaMillis).atZone(zone)
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        etaMillis = combineInZone(
                            LocalDate.of(year, month + 1, day),
                            current.toLocalTime(),
                            zone,
                        )
                    },
                    current.year,
                    current.monthValue - 1,
                    current.dayOfMonth,
                ).show()
            },
        ) { Text("Choose date") }
        OutlinedButton(
            enabled = !actionInProgress,
            onClick = {
                val current = Instant.ofEpochMilli(etaMillis).atZone(zone)
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        etaMillis = combineInZone(
                            current.toLocalDate(),
                            LocalTime.of(hour, minute),
                            zone,
                        )
                    },
                    current.hour,
                    current.minute,
                    true,
                ).show()
            },
        ) { Text("Choose time") }
    }
    Spacer(Modifier.height(24.dp))
    if (!locationServicesEnabled) {
        Text(
            "Device Location Services are off. Turn them on before starting a Journey.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onOpenLocationSettings) { Text("Open Location settings") }
        Spacer(Modifier.height(12.dp))
    }
    Button(
        onClick = { onStartRequested(destination, etaMillis) },
        enabled = !actionInProgress,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (actionInProgress) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        } else {
            Text("Start Journey")
        }
    }
}

@Composable
private fun ActiveJourneyContent(
    journey: Journey,
    notificationsVisible: Boolean,
    locationUiState: LocationUiState,
    telemetryCount: Long,
    latestTelemetry: TelemetryObservation?,
    syncState: JourneySyncState?,
    monitoringState: CloudMonitoringState?,
    actionInProgress: Boolean,
    onEnd: () -> Unit,
    onRetryMonitoring: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var now by remember(journey.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(journey.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Spacer(Modifier.height(24.dp))
    Text(
        text = "Journey Active",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
    JourneyDetail("Destination", journey.destination)
    JourneyDetail("Started", formatTimestamp(journey.startedAt, zone))
    JourneyDetail("Expected arrival", formatTimestamp(journey.expectedArrivalAt, zone))
    JourneyDetail("Elapsed", formatElapsed((now - journey.startedAt).coerceAtLeast(0)))
    if (!notificationsVisible) {
        Text(
            "Notifications are disabled. Android exposes the foreground service through system Active apps controls.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    when {
        locationUiState.access == ForegroundLocationAccess.NONE -> {
            Text(
                "Location permission is missing. Monitoring is not running.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onRetryMonitoring) { Text("Grant location access") }
        }
        !locationUiState.locationServicesEnabled -> {
            Text(
                "Device Location Services are off. Monitoring is not running.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenLocationSettings) { Text("Open Location settings") }
        }
        locationUiState.access == ForegroundLocationAccess.APPROXIMATE -> {
            Text(
                "Approximate location only — evidence precision is reduced. Android-reported accuracy is shown below.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        else -> Unit
    }
    Spacer(Modifier.height(20.dp))
    Text("Local telemetry evidence", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    JourneyDetail("Telemetry count", telemetryCount.toString())
    if (latestTelemetry == null) {
        Text("Waiting for first location…", style = MaterialTheme.typography.bodyLarge)
    } else {
        JourneyDetail("Latest sequence", latestTelemetry.sequence.toString())
        JourneyDetail("Observation time", formatTimestamp(latestTelemetry.eventTime, zone))
        JourneyDetail(
            "Latitude / longitude",
            String.format(Locale.US, "%.6f, %.6f", latestTelemetry.latitude, latestTelemetry.longitude),
        )
        JourneyDetail(
            "Horizontal accuracy",
            String.format(Locale.US, "%.1f m", latestTelemetry.accuracyMeters),
        )
        JourneyDetail(
            "Battery",
            latestTelemetry.batteryPercent?.let { percent ->
                val charging = when (latestTelemetry.isCharging) {
                    true -> ", charging"
                    false -> ", not charging"
                    null -> ""
                }
                "$percent%$charging"
            } ?: "Unavailable",
        )
        JourneyDetail("Connectivity", latestTelemetry.connectivity.name)
    }
    Spacer(Modifier.height(20.dp))
    Text("Cloud synchronization", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    val latestSequence = latestTelemetry?.sequence ?: 0L
    val syncedThrough = syncState?.highestTelemetrySequenceSynced ?: 0L
    val pending = (latestSequence - syncedThrough).coerceAtLeast(0L)
    val syncLabel = when (syncState?.phase) {
        SyncPhase.SYNCING -> "Syncing"
        SyncPhase.ERROR -> "Error"
        SyncPhase.IDLE -> if (pending == 0L) "Synced" else "Pending"
        SyncPhase.PENDING -> "Waiting for network / worker"
        null -> "Preparing local checkpoint"
    }
    JourneyDetail("Cloud sync", syncLabel)
    JourneyDetail("Local observations", telemetryCount.toString())
    JourneyDetail("Cloud-synced through sequence", syncedThrough.toString())
    JourneyDetail("Pending", pending.toString())
    syncState?.lastError?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
    }
    Text(
        "Sync status describes data transport only; it does not verify Journey safety.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(20.dp))
    Text("Cloud monitoring", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    val monitoringLabel = when (monitoringState?.phase) {
        CloudMonitoringPhase.EVIDENCE_FRESH -> "Evidence fresh"
        CloudMonitoringPhase.VERIFYING -> "Verifying device contact"
        CloudMonitoringPhase.CLOSED -> "Closed"
        null -> "Waiting for first fresh heartbeat"
    }
    JourneyDetail("Monitoring phase", monitoringLabel)
    JourneyDetail(
        "Last cloud contact",
        monitoringState?.lastCloudContactAt?.let { formatTimestamp(it, zone) } ?: "Not yet available",
    )
    JourneyDetail(
        "Latest heartbeat sequence",
        monitoringState?.latestCloudHeartbeatSequence?.toString() ?: "0",
    )
    monitoringState?.lastError?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
    }
    Text(
        "Monitoring reports device/cloud contact only; it does not verify traveller status.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onEnd,
        enabled = !actionInProgress,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (actionInProgress) "Ending…" else "End Journey")
    }
}

@Composable
private fun JourneyDetail(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(10.dp))
}

private fun combineInZone(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
    LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

private fun formatTimestamp(timestamp: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(timestamp).atZone(zone).format(dateTimeFormatter)

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
