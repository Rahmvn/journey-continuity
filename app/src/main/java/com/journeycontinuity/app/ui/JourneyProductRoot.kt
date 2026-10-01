package com.journeycontinuity.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeycontinuity.app.R
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import com.journeycontinuity.app.trusted.TrustedContactStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val Background = Color(0xFFFBFBF8)
private val SheetBackground = Color(0xFFFEFDFB)
private val Brand = Color(0xFF1B3250)
private val Heading = Color(0xFF161B20)
private val Body = Color(0xFF676F74)
private val Border = Color(0xFFDFE3E2)
private val Warning = Color(0xFFB0822F)
private val ContextBlue = Color(0xFF3E5C74)
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)
private val InstrumentSans = FontFamily(Font(R.font.instrument_sans_semibold, FontWeight.SemiBold))
private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val ArrivalFormat = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")

/** The route lives only in the Activity-scoped ViewModel: recreation retains it, process restart opens Home. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun JourneyProductRoot(
    viewModel: JourneyViewModel,
    locationUiState: LocationUiState,
    smsFallbackStatus: SmsFallbackStatus,
    accountEmail: String?,
    onStartRequested: (String, Long) -> Unit,
    onRetryMonitoring: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRequestSmsPermissions: () -> Unit,
    onSelectSmsSubscription: (Int) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var accountOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    BackHandler(state.productRoute != JourneyProductRoute.HOME) {
        if (state.productRoute == JourneyProductRoute.CHECKPOINT) viewModel.backToStart()
        else if (state.productRoute == JourneyProductRoute.CONTACTS ||
            state.productRoute == JourneyProductRoute.RESILIENCE) viewModel.closeUtility()
        else viewModel.backToHome()
    }

    Box(Modifier.fillMaxSize().background(Background).safeDrawingPadding().imePadding()) {
        when {
            state.isRestoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
            state.productRoute == JourneyProductRoute.HOME -> JourneyHome(
                state = state,
                accountEmail = accountEmail,
                smsFallbackReady = smsFallbackStatus.ready,
                onPrimary = {
                    if (state.activeJourney == null) viewModel.openStart() else viewModel.openActive()
                },
                onContacts = viewModel::openContacts,
                onResilience = viewModel::openResilience,
                onAccount = { accountOpen = true },
            )
            state.productRoute == JourneyProductRoute.START ||
                state.productRoute == JourneyProductRoute.CHECKPOINT -> {
                JourneyStartScreen(
                    state = state,
                    onDestinationChange = viewModel::updateDestination,
                    onArrivalChange = viewModel::updateExpectedArrival,
                    onBack = viewModel::backToHome,
                    onStart = { viewModel.requestStart(smsFallbackStatus.ready, onStartRequested) },
                )
                if (state.productRoute == JourneyProductRoute.CHECKPOINT) {
                    val accepted = state.trustedContacts.any {
                        it.status == TrustedContactStatus.ACCEPTED
                    }
                    ModalBottomSheet(
                        onDismissRequest = viewModel::backToStart,
                        containerColor = SheetBackground,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ) {
                        JourneyCheckpoint(
                            acceptedContact = accepted,
                            onStartAnyway = { viewModel.startAnyway(onStartRequested) },
                            onSetup = {
                                if (accepted) viewModel.openResilience()
                                else viewModel.openContacts()
                            },
                        )
                    }
                }
            }
            state.productRoute == JourneyProductRoute.ACTIVE -> state.activeJourney?.let { journey ->
                JourneyActiveScreen(
                    journey = journey,
                    state = state,
                    locationUiState = locationUiState,
                    smsFallbackReady = smsFallbackStatus.ready,
                    onBack = viewModel::backToHome,
                    onRetry = onRetryMonitoring,
                    onEndCompleted = viewModel::endJourney,
                )
            }
            state.productRoute == JourneyProductRoute.CONTACTS -> JourneyUtilityScreen(
                title = "Trusted Contacts", onBack = viewModel::closeUtility,
            ) { LegacyTrustedContactsContent(viewModel) }
            state.productRoute == JourneyProductRoute.RESILIENCE -> JourneyUtilityScreen(
                title = "Resilience", onBack = viewModel::closeUtility,
            ) {
                LegacySmsFallbackContent(smsFallbackStatus, onRequestSmsPermissions,
                    onSelectSmsSubscription)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (accountOpen) {
        AlertDialog(
            onDismissRequest = { accountOpen = false },
            title = { Text("Account") },
            text = { Text(accountEmail ?: "Signed in to Alabarin") },
            confirmButton = { TextButton(onClick = { accountOpen = false }) { Text("Close") } },
        )
    }
}

@Composable
internal fun JourneyHome(
    state: JourneyUiState,
    accountEmail: String?,
    smsFallbackReady: Boolean,
    onPrimary: () -> Unit,
    onContacts: () -> Unit,
    onResilience: () -> Unit,
    onAccount: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Alabarin", color = Brand, style = textStyle(18, 22, FontWeight.SemiBold,
                InstrumentSans), modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(50.dp))
            UtilityRow(R.drawable.alabarin_contacts, "Trusted Contacts",
                trustedContactsSummary(state), onContacts)
            Spacer(Modifier.height(12.dp))
            UtilityRow(R.drawable.alabarin_resilience, "Resilience",
                resilienceSummary(state, smsFallbackReady), onResilience)
            Spacer(Modifier.height(18.dp))
            UtilityRow(R.drawable.alabarin_account, "Account",
                accountEmail ?: "Signed in to Alabarin", onAccount)
        }
        state.activeJourney?.let { journey ->
            Column(Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp)).background(Color(0xFFFAFAF9)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.alabarin_pin), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(journey.destination, color = Heading,
                            style = textStyle(18, 22, FontWeight.Bold), maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text("Expected ${formatTime(journey.expectedArrivalAt)}", color = Body,
                            style = textStyle(13, 17))
                    }
                }
                Spacer(Modifier.height(14.dp))
                PrimaryAction("Open Journey", onPrimary, modifier = Modifier.height(52.dp))
            }
        } ?: PrimaryAction("Start Journey", onPrimary)
    }
}

private fun trustedContactsSummary(state: JourneyUiState): String {
    if (state.trustedContactsAvailability == TrustedContactsAvailability.LOADING) return "Checking contacts"
    if (state.trustedContactsAvailability == TrustedContactsAvailability.UNAVAILABLE) return "Currently unavailable"
    val accepted = state.trustedContacts.filter { it.status == TrustedContactStatus.ACCEPTED }
    val pending = state.trustedContacts.count { it.status == TrustedContactStatus.PENDING }
    if (accepted.isEmpty()) return if (pending == 0) "None added" else "$pending pending"
    val names = accepted.take(2).joinToString(", ") { it.displayName }
    val more = if (accepted.size > 2) " +${accepted.size - 2}" else ""
    val pendingText = if (pending > 0) " · $pending pending" else ""
    return "$names$more$pendingText"
}

private fun resilienceSummary(state: JourneyUiState, smsFallbackReady: Boolean): String {
    val accepted = state.trustedContacts.any { it.status == TrustedContactStatus.ACCEPTED }
    return if (accepted && smsFallbackReady) "Ready" else "Setup needed"
}

@Composable
private fun UtilityRow(icon: Int, title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(62.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Heading, style = textStyle(15, 19, FontWeight.Medium))
            Spacer(Modifier.height(2.dp))
            Text(summary, color = Body, style = textStyle(13, 18), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Body, style = textStyle(24, 24))
    }
}

@Composable
internal fun JourneyStartScreen(
    state: JourneyUiState,
    onDestinationChange: (String) -> Unit,
    onArrivalChange: (Long) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val ready = canSubmitJourneyStart(state.draftDestination, state.draftExpectedArrivalAt, now)
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            JourneyHeader("Start Journey", onBack)
            Spacer(Modifier.height(38.dp))
            Text("Where are you going?", color = Heading,
                style = textStyle(15, 20, FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            JourneyInput(state.draftDestination, "e.g. Ilorin", onDestinationChange)
            Spacer(Modifier.height(24.dp))
            Text("When do you expect to arrive?", color = Heading,
                style = textStyle(15, 20, FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(58.dp)
                .border(1.dp, Color(0xBF676F74), RoundedCornerShape(4.dp))
                .clickable(role = Role.Button) {
                    val suggested = Instant.ofEpochMilli(state.draftExpectedArrivalAt ?: now + 3_600_000L)
                        .atZone(zone)
                    DatePickerDialog(context, { _, year, month, day ->
                        val selectedDate = LocalDate.of(year, month + 1, day)
                        TimePickerDialog(context, { _, hour, minute ->
                            val timestamp = selectedDate.atTime(LocalTime.of(hour, minute))
                                .atZone(zone).toInstant().toEpochMilli()
                            onArrivalChange(timestamp)
                            now = System.currentTimeMillis()
                        }, suggested.hour, suggested.minute, false).show()
                    }, suggested.year, suggested.monthValue - 1, suggested.dayOfMonth).show()
                }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.draftExpectedArrivalAt?.let { formatArrival(it) } ?: "Choose date and time",
                    color = if (state.draftExpectedArrivalAt == null) Body else Heading,
                    style = textStyle(14, 19), modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Image(painterResource(R.drawable.alabarin_calendar), null, Modifier.size(19.dp))
            }
            if (state.draftExpectedArrivalAt != null && state.draftExpectedArrivalAt <= now) {
                Text("Choose a future date and time.", color = Warning,
                    style = textStyle(13, 18), modifier = Modifier.padding(top = 8.dp))
            }
        }
        PrimaryAction("Start Journey", onStart, enabled = ready && !state.isActionInProgress,
            loading = state.isActionInProgress)
    }
}

@Composable
private fun JourneyInput(value: String, hint: String, onValueChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(58.dp).testTag("journey_destination"),
        placeholder = { Text(hint, color = Body, style = textStyle(14, 19)) },
        singleLine = true,
        textStyle = textStyle(14, 19),
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Brand,
            unfocusedBorderColor = Color(0xBF676F74),
            cursorColor = Brand,
        ),
    )
}

@Composable
internal fun JourneyCheckpoint(
    acceptedContact: Boolean,
    onStartAnyway: () -> Unit,
    onSetup: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(382.dp).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text("Resilience setup needed", color = Heading,
            style = textStyle(21, 26, FontWeight.Bold), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        Text("You can still start this Journey, but fresh outward information may be harder to preserve if internet becomes limited.",
            color = Body, style = textStyle(13, 18))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (acceptedContact) "SMS fallback" else "Trusted contact", color = Heading,
                    style = textStyle(15, 19, FontWeight.Medium))
                Text(if (acceptedContact) "Unavailable" else "None added", color = Body,
                    style = textStyle(13, 17))
            }
            Text("Setup needed", color = Warning, style = textStyle(12, 16, FontWeight.Medium))
        }
        Spacer(Modifier.weight(1f))
        PrimaryAction("Start anyway", onStartAnyway)
        Box(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onSetup),
            contentAlignment = Alignment.Center) {
            Text(if (acceptedContact) "Set up resilience" else "Set up trusted contact",
                color = Brand, style = textStyle(15, 19, FontWeight.Medium))
        }
    }
}

@Composable
internal fun JourneyActiveScreen(
    journey: Journey,
    state: JourneyUiState,
    locationUiState: LocationUiState,
    smsFallbackReady: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEndCompleted: () -> Unit,
) {
    var confirmCompletion by remember { mutableStateOf(false) }
    val monitoringReady = backgroundMonitoringReady(journey, state.monitoringJourneyId) &&
        locationUiState.access != ForegroundLocationAccess.NONE &&
        locationUiState.locationServicesEnabled
    val accepted = state.trustedContacts.any { it.status == TrustedContactStatus.ACCEPTED }
    val connectivity = travellerConnectivityCopy(
        state.degradation, state.currentConnectivity, smsFallbackReady, accepted,
    )
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BackIcon(onBack)
            Spacer(Modifier.height(56.dp))
            Text(journey.destination, color = Heading, style = textStyle(40, 46, FontWeight.Bold),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            JourneyTimeRow("Expected arrival", formatTime(journey.expectedArrivalAt))
            Spacer(Modifier.height(12.dp))
            JourneyTimeRow("Started", formatTime(journey.startedAt))
            Spacer(Modifier.height(50.dp))
            Row {
                Image(painterResource(R.drawable.alabarin_monitoring), null,
                    Modifier.size(14.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (monitoringReady) "Monitoring in background" else "Monitoring needs attention",
                        color = Heading, style = textStyle(14, 19, FontWeight.Medium))
                    Spacer(Modifier.height(8.dp))
                    Text(if (monitoringReady)
                        "You can leave the app. Alabarin keeps monitoring this Journey."
                        else "Background monitoring is not running. Check Location Services and retry.",
                        color = Body, style = textStyle(12, 17))
                    if (!monitoringReady) TextButton(onClick = onRetry) {
                        Text("Retry monitoring", color = Brand)
                    }
                }
            }
            connectivity?.let { copy ->
                Spacer(Modifier.height(28.dp))
                Row {
                    Image(painterResource(when {
                        copy.title == "Connection restored" || copy.title == "Checking connection" ->
                            R.drawable.alabarin_recovering
                        copy.detail.startsWith("SMS fallback") -> R.drawable.alabarin_limited
                        else -> R.drawable.alabarin_unavailable
                    }), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(copy.title, color = if (copy.detail.startsWith("Fresh")) Warning else ContextBlue,
                            style = textStyle(13, 17, FontWeight.Medium))
                        Spacer(Modifier.height(6.dp))
                        Text(copy.detail, color = Body, style = textStyle(12, 17))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        Box(Modifier.fillMaxWidth().height(52.dp)
            .clickable(role = Role.Button) { confirmCompletion = true },
            contentAlignment = Alignment.Center) {
            Text("End Journey", color = Heading, style = textStyle(15, 19, FontWeight.Medium))
        }
    }
    if (confirmCompletion) AlertDialog(
        onDismissRequest = { confirmCompletion = false },
        title = { Text("Journey completed?") },
        text = { Text("Confirm only if this Journey has ended. This does not independently confirm safe arrival.") },
        dismissButton = { TextButton(onClick = { confirmCompletion = false }) {
            Text("Keep Journey running")
        } },
        confirmButton = { TextButton(onClick = {
            confirmCompletion = false
            onEndCompleted()
        }) { Text("Journey completed") } },
    )
}

@Composable
private fun JourneyTimeRow(label: String, value: String) {
    Row {
        Text(label, color = Body, style = textStyle(13, 18))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Heading, style = textStyle(13, 18, FontWeight.Medium))
    }
}

@Composable
private fun JourneyUtilityScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)) {
        JourneyHeader(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun JourneyHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackIcon(onBack)
        Spacer(Modifier.width(8.dp))
        Text(title, color = Heading, style = textStyle(22, 27, FontWeight.Bold),
            modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun BackIcon(onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.alabarin_back), "Return to Home", Modifier.size(20.dp))
    }
}

@Composable
private fun PrimaryAction(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, loading: Boolean = false,
) {
    Box(modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp))
        .background(if (enabled) Brand else Color(0xFFD5D7D7))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag("journey_primary_action"), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White,
            strokeWidth = 2.dp)
        else Text(title, color = if (enabled) Color.White else Color(0xFF808587),
            style = textStyle(16, 20, FontWeight.Bold))
    }
}

private fun textStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    platformStyle = NoFontPadding,
)

private fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(TimeFormat)

private fun formatArrival(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(ArrivalFormat)
