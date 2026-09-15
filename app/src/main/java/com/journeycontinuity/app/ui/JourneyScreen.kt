package com.journeycontinuity.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.journeycontinuity.app.domain.TelemetryObservation
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
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
        }
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
